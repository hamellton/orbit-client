package orbit.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.net.Recording.PacketRecord;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.AudioSettingsModule;
import orbit.protocol.gen.AudioSettingsRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.LoginResponse;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.UserSettingsCommand;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Checks the client-like startup against the official client's recordings. */
class StartupSequenceTest {

    /** Scheduling noise allowed around the recorded pauses. */
    private static final int TOLERANCE_MS = 5;

    @Test
    void audioComesFromServerSettingsAndMissingProfilePartsAreSkipped() {
        UserSettingsCommand us = new UserSettingsCommand();
        us.audioSettingsModule = new AudioSettingsModule();
        us.audioSettingsModule.voice = 2;
        us.audioSettingsModule.music = 1;
        List<StartupSequence.Step> steps = StartupSequence.ClientLike.steps(ClientProfile.minimal(),
                new StartupSequence.Context(false, us));
        AudioSettingsRequest audio = (AudioSettingsRequest) packet(steps, "AudioSettingsRequest");
        assertEquals(2, audio.voice);
        assertEquals(1, audio.music);
        assertNull(packet(steps, "WindowSettingsRequest"), "no layout without a profile");
        assertNull(packet(steps, "UserKeyBindingsUpdate"), "no key table without a profile");

        List<StartupSequence.Step> noSettings = StartupSequence.ClientLike.steps(ClientProfile.minimal(),
                new StartupSequence.Context(true, null));
        assertNull(packet(noSettings, "AudioSettingsRequest"));
    }

    @Test
    void matchesRecordedOrderAndPauses() throws Exception {
        List<Path> files = recordings();
        Assumptions.assumeFalse(files.isEmpty(), "no recordings");
        List<PacketRecord> all = new ArrayList<>();
        for (Path f : files) {
            all.addAll(Recording.readPackets(f));
        }
        ClientProfile profile = ClientProfile.fromRecording(all);
        assertNotNull(profile.windowSettings(), "recordings include map changes, so the layout is known");
        assertNotNull(profile.keyBindings());

        int checked = 0;
        int jumps = 0;
        List<String> violations = new ArrayList<>();
        for (Path f : files) {
            List<PacketRecord> packets = Recording.readPackets(f);
            boolean cold = packets.stream().filter(PacketRecord::up).map(StartupSequenceTest::name)
                    .takeWhile(n -> !n.equals("ReadyRequest")).anyMatch(n -> n.equals("HangarListRequest"));
            // starts: after LoginResponse, and after every further ShipInitializationCommand (portal jump on the same server)
            List<Integer> anchors = new ArrayList<>();
            List<StartupSequence.Context> contexts = new ArrayList<>();
            boolean shipSeen = false;
            for (int i = 0; i < packets.size(); i++) {
                PacketRecord r = packets.get(i);
                if (r.up()) {
                    continue;
                }
                Packet p = PacketCodec.decode(r.bytes());
                if (p instanceof LoginResponse && anchors.isEmpty()) {
                    anchors.add(i);
                    contexts.add(new StartupSequence.Context(cold, settings(packets, i)));
                } else if (p instanceof ShipInitializationCommand && shipSeen && !anchors.isEmpty()) {
                    anchors.add(i);
                    contexts.add(new StartupSequence.Context(false, settings(packets, i), true));
                    jumps++;
                } else if (p instanceof ShipInitializationCommand) {
                    shipSeen = true;
                }
            }
            for (int k = 0; k < anchors.size(); k++) {
                StartupSequence.Context ctx = contexts.get(k);
                String label = f.getFileName() + " " + (ctx.portalJump() ? "jump" : cold ? "cold" : "warm");
                // the recording must match one of the observed variants in order and pauses
                List<String> attempts = new ArrayList<>();
                boolean matched = false;
                for (List<StartupSequence.Step> steps : StartupSequence.ClientLike.variants(profile, ctx)) {
                    List<Timed> recorded = recordedStartup(packets, steps, anchors.get(k));
                    List<String> expected = steps.stream().map(StartupSequence.Step::name).toList();
                    List<String> got = recorded.stream().map(Timed::name).toList();
                    if (!expected.equals(got)) {
                        attempts.add("order " + got);
                        continue;
                    }
                    List<String> bad = new ArrayList<>();
                    for (int i = 0; i < steps.size(); i++) {
                        StartupSequence.Step s = steps.get(i);
                        long gap = recorded.get(i).gapMs();
                        if (gap < s.minDelayMs() - TOLERANCE_MS || gap > s.stallMaxMs() + TOLERANCE_MS) {
                            bad.add("#" + i + " " + s.name() + " pause " + gap + " ms, expected " + s.minDelayMs() + "–"
                                    + s.maxDelayMs() + " (stalls up to " + s.stallMaxMs() + ")");
                        }
                    }
                    int audioAt = expected.indexOf("AudioSettingsRequest");
                    if (audioAt >= 0 && !String.valueOf(packet(steps, "AudioSettingsRequest"))
                            .equals(String.valueOf(recorded.get(audioAt).packet()))) {
                        bad.add("audio settings differ");
                    }
                    if (bad.isEmpty()) {
                        matched = true;
                        break;
                    }
                    attempts.add(String.join("; ", bad));
                }
                if (!matched) {
                    violations.add(label + ": " + String.join(" | ", attempts));
                }
                checked++;
            }
        }
        assertTrue(checked > 0);
        assertTrue(jumps > 0, "recordings contain a portal jump on the same server");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private record Timed(String name, long gapMs, Packet packet) {
    }

    /**
     * Client packets after the server packet at {@code anchor}, restricted to the kinds the sequence
     * sends (UI reactions such as moves, chat and ship info queries are left out), cut to its length.
     */
    private static List<Timed> recordedStartup(List<PacketRecord> packets, List<StartupSequence.Step> steps, int anchor) {
        Set<String> kinds = steps.stream().map(StartupSequence.Step::name).collect(Collectors.toSet());
        List<Timed> out = new ArrayList<>();
        long prev = packets.get(anchor).nanos();
        for (int i = anchor + 1; i < packets.size() && out.size() < steps.size(); i++) {
            PacketRecord r = packets.get(i);
            if (!r.up()) {
                continue;
            }
            Packet p = PacketCodec.decode(r.bytes());
            String n = p.getClass().getSimpleName();
            if (!kinds.contains(n) || (p instanceof LegacyModule lm && !lm.message.equals("LF|w"))) {
                continue;
            }
            out.add(new Timed(n, (r.nanos() - prev) / 1_000_000, p));
            prev = r.nanos();
        }
        return out;
    }

    /** The last {@code UserSettingsCommand} before {@code index}. */
    private static UserSettingsCommand settings(List<PacketRecord> packets, int index) {
        UserSettingsCommand last = null;
        for (int i = 0; i < index; i++) {
            PacketRecord r = packets.get(i);
            if (!r.up() && PacketCodec.decode(r.bytes()) instanceof UserSettingsCommand us) {
                last = us;
            }
        }
        return last;
    }

    private static Packet packet(List<StartupSequence.Step> steps, String name) {
        return steps.stream().filter(s -> s.name().equals(name)).findFirst().orElseThrow().packet().get();
    }

    private static String name(PacketRecord r) {
        try {
            return PacketCodec.decode(r.bytes()).getClass().getSimpleName();
        } catch (RuntimeException e) {
            return "?";
        }
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
