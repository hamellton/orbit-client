package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import orbit.protocol.gen.MenuItemCooldownGroupTimerCommand;
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
                if (r.up()) {
                    continue;
                }
                Packet p = PacketCodec.decode(r.bytes());
                String key;
                TimerStateModule state;
                double time;
                if (p instanceof MenuItemTimerCommand t) {
                    key = t.menuItemId;
                    state = t.timerState;
                    time = t.time;
                } else if (p instanceof MenuItemCooldownGroupTimerCommand g && g.cooldownType != null) {
                    key = "cooldown group " + g.cooldownType.typeValue;
                    state = g.timerState;
                    time = g.time;
                } else {
                    continue;
                }
                if (state == null) {
                    continue;
                }
                double now = r.nanos() / 1e6;
                if (state.timerStateValue != TimerStateModule.TIMERSTATE_READY && time > 0) {
                    started.putIfAbsent(key, new double[] {now, time});
                } else if (state.timerStateValue == TimerStateModule.TIMERSTATE_READY && started.containsKey(key)) {
                    double[] s = started.remove(key);
                    double actual = now - s[0];
                    String line = String.format("%s: %s announced %.0f, ready after %.0f ms", f.getFileName(), key, s[1], actual);
                    checked.add(line);
                    // milliseconds: «ready» never comes much before the announced time (in seconds it would come a
                    // thousand times sooner). Later is fine: the server holds formations (group 47), ISH, rockets and
                    // the launcher well past their time (2026-11-11: 3 s announced, ready after 5–246 s)
                    if (actual < s[1] - Math.max(1500, s[1] * 0.15)) {
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

    /**
     * The hero's laser target and its attackers: the client shoots at the selected ship, and anyone
     * who hit the hero is listed as an attacker.
     */
    @Test
    void heroFightIsTracked() throws Exception {
        int attacking = 0;
        int attackingSelected = 0;
        int attacked = 0;
        int hitsWithoutAttacker = 0;
        for (Path f : recordings()) {
            World w = new World();
            for (PacketRecord r : Recording.readPackets(f)) {
                Packet p = PacketCodec.decode(r.bytes());
                if (r.up()) {
                    w.applyOutgoing(p, r.nanos());
                    continue;
                }
                w.apply(p, r.nanos());
                WorldSnapshot.Hero h = w.snapshot(r.nanos()).hero();
                if (p instanceof orbit.protocol.gen.AttackLaserRunCommand l && l.attackerId == h.id()) {
                    attacking++;
                    attackingSelected += h.attackTargetId() == h.selectedId() ? 1 : 0;
                }
                if (p instanceof AttackHitCommand hit && hit.victimId == h.id() && h.id() != 0) {
                    attacked++;
                    hitsWithoutAttacker += h.attackers().contains(hit.attackerId) ? 0 : 1;
                }
            }
        }
        Assumptions.assumeTrue(attacking > 0 && attacked > 0, "no recording with the hero fighting");
        System.out.printf("hero shots %d (at selected %d), hits on hero %d%n", attacking, attackingSelected, attacked);
        assertTrue(attackingSelected >= attacking * 0.9, "the hero should shoot at its selected target");
        assertTrue(hitsWithoutAttacker == 0, hitsWithoutAttacker + " hits whose attacker is not listed");
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

    /**
     * The group window's «target» of a member (ru1 2026-11-09: the leader on Cubikon) reaches the model from the
     * group's {@code TargetModule} updates with its name and bars.
     */
    @Test
    void groupMembersTargetsAreKept() throws Exception {
        int seen = 0;
        for (Path f : recordings()) {
            World w = new World();
            for (PacketRecord r : Recording.readPackets(f)) {
                if (r.up()) {
                    continue;
                }
                Packet p = PacketCodec.decode(r.bytes());
                w.apply(p, r.nanos());
                if (p instanceof GroupMemberUpdateCommand u
                        && u.updates.stream().anyMatch(m -> m instanceof orbit.protocol.gen.TargetModule t
                                && t.name != null && !t.name.isEmpty())) {
                    WorldSnapshot.Member m = w.snapshot(r.nanos()).group().members().stream()
                            .filter(x -> x.id() == u.playerId).findFirst().orElseThrow();
                    assertFalse(m.targetName().isEmpty(), f.getFileName() + ": " + m);
                    seen++;
                }
            }
        }
        Assumptions.assumeTrue(seen > 0, "no recording with a member's target");
        System.out.println("members' targets: " + seen);
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

    /**
     * The trade window's prices (ru1 2026-11-11: at 2-8's refinery after {@code TradeRequest}, and with the pet's
     * trade pod with no request) reach the model as ore → price.
     */
    @Test
    void theTradeWindowsPricesAreKept() throws Exception {
        int windows = 0;
        for (Path f : recordings()) {
            World w = new World();
            for (PacketRecord r : Recording.readPackets(f)) {
                if (r.up()) {
                    continue;
                }
                Packet p = PacketCodec.decode(r.bytes());
                w.apply(p, r.nanos());
                if (p instanceof orbit.protocol.gen.OreTradeInfoCommand o) {
                    windows++;
                    var prices = w.snapshot(r.nanos()).inventory().tradePrices();
                    assertEquals((Integer) o.prometiumPrice, prices.get(0), f.getFileName() + ": prometium");
                    assertEquals((Integer) o.sepromPrice, prices.get(7), f.getFileName() + ": seprom");
                    assertTrue(!prices.containsKey(8), "palladium is exchanged, not bought");
                }
            }
        }
        Assumptions.assumeTrue(windows > 0, "no recording with a trade window");
    }
}
