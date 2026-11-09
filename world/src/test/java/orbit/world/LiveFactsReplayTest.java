package orbit.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.net.Recording.PacketRecord;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.AttackHitCommand;
import orbit.protocol.gen.AttackHitNoLockCommand;
import orbit.protocol.gen.GroupInitializationCommand;
import orbit.protocol.gen.GroupMemberUpdateCommand;
import orbit.protocol.gen.MenuItemTimerCommand;
import orbit.protocol.gen.TimerStateModule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Checks parts of the model that need specific situations in a recording: item cooldowns, damage
 * taken by the hero and a group. Each test is skipped (with the reason) until such a session is
 * recorded with the sniffer.
 */
class LiveFactsReplayTest {

    /**
     * {@code time} is milliseconds if an item that is cooling down becomes ready about {@code time}
     * later. Timers sent as "ready" are ignored.
     */
    @Test
    void cooldownTimesAreMilliseconds() throws Exception {
        Map<String, double[]> started = new HashMap<>(); // item -> {receivedMs, timeMs}
        List<String> checked = new ArrayList<>();
        List<String> wrong = new ArrayList<>();
        for (Path f : recordings()) {
            started.clear();
            for (PacketRecord r : Recording.readPackets(f)) {
                if (r.up() || !(PacketCodec.decode(r.bytes()) instanceof MenuItemTimerCommand t) || t.timerState == null) {
                    continue;
                }
                double now = r.nanos() / 1e6;
                if (t.timerState.timerStateValue != TimerStateModule.TIMERSTATE_READY && t.time > 0) {
                    started.putIfAbsent(t.menuItemId, new double[] {now, t.time});
                } else if (t.timerState.timerStateValue == TimerStateModule.TIMERSTATE_READY && started.containsKey(t.menuItemId)) {
                    double[] s = started.remove(t.menuItemId);
                    double actual = now - s[0];
                    String line = String.format("%s: %s announced %.0f, ready after %.0f ms", f.getFileName(), t.menuItemId, s[1], actual);
                    checked.add(line);
                    if (Math.abs(actual - s[1]) > Math.max(1500, s[1] * 0.15)) {
                        wrong.add(line);
                    }
                }
            }
        }
        Assumptions.assumeFalse(checked.isEmpty(), "no recording with an item going from cooldown to ready");
        checked.forEach(System.out::println);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    /**
     * 50 ms after a hit on the hero its health must be within the maxima: on a configuration switch
     * the hit already carries the new shield while the new maximum ({@code AttributeShieldUpdateCommand})
     * follows a fraction of a millisecond later.
     */
    @Test
    void heroDamageKeepsHealthConsistent() throws Exception {
        int hits = 0;
        List<String> wrong = new ArrayList<>();
        for (Path f : recordings()) {
            World w = new World();
            long pendingBatch = -1;
            for (PacketRecord r : Recording.readPackets(f)) {
                if (pendingBatch >= 0 && r.nanos() > pendingBatch + 50_000_000L) {
                    Health h = w.snapshot(pendingBatch).hero().health();
                    if (h.hp() < 0 || h.hp() > h.hpMax() || h.shield() < 0 || (h.shieldMax() > 0 && h.shield() > h.shieldMax())) {
                        wrong.add(f.getFileName() + " " + pendingBatch / 1e9 + " s: " + h);
                    }
                    pendingBatch = -1;
                }
                Packet p = PacketCodec.decode(r.bytes());
                if (r.up()) {
                    w.applyOutgoing(p, r.nanos());
                    continue;
                }
                w.apply(p, r.nanos());
                int heroId = w.snapshot(r.nanos()).hero().id();
                boolean heroHit = (p instanceof AttackHitCommand h && h.victimId == heroId)
                        || (p instanceof AttackHitNoLockCommand n && n.victimId == heroId);
                if (heroHit) {
                    hits++;
                    if (pendingBatch < 0) {
                        pendingBatch = r.nanos();
                    }
                }
            }
        }
        Assumptions.assumeTrue(hits > 0, "no recording where the hero takes damage");
        System.out.println("hits on hero: " + hits);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    @Test
    void groupIsParsed() throws Exception {
        int groups = 0;
        int updates = 0;
        List<String> wrong = new ArrayList<>();
        for (Path f : recordings()) {
            World w = new World();
            for (PacketRecord r : Recording.readPackets(f)) {
                Packet p = PacketCodec.decode(r.bytes());
                if (r.up()) {
                    continue;
                }
                w.apply(p, r.nanos());
                if (p instanceof GroupInitializationCommand) {
                    groups++;
                    WorldSnapshot.Group g = w.snapshot(r.nanos()).group();
                    if (!g.exists() || g.members().isEmpty()
                            || g.members().stream().noneMatch(m -> m.id() == g.leaderId())) {
                        wrong.add(f.getFileName() + ": " + g);
                    }
                    System.out.println(f.getFileName() + ": " + g);
                }
                updates += p instanceof GroupMemberUpdateCommand ? 1 : 0;
            }
        }
        Assumptions.assumeTrue(groups > 0, "no recording with a group");
        System.out.println("group member updates: " + updates);
        assertTrue(wrong.isEmpty(), String.join("\n", wrong));
    }

    private static List<Path> recordings() throws Exception {
        Path dir = Path.of(System.getProperty("orbit.recordings", "recordings"));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".dorec")).sorted().toList();
        }
    }
}
