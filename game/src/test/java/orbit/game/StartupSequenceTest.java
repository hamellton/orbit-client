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
        List<String> violations = new ArrayList<>();
        for (Path f : files) {
            List<PacketRecord> packets = Recording.readPackets(f);
            boolean cold = packets.stream().filter(PacketRecord::up).map(StartupSequenceTest::name)
                    .takeWhile(n -> !n.equals("ReadyRequest")).anyMatch(n -> n.equals("HangarListRequest"));
            List<StartupSequence.Step> steps = StartupSequence.ClientLike.steps(profile,
                    new StartupSequence.Context(cold, settings(packets)));
            List<Timed> recorded = recordedStartup(packets, steps);
            List<String> expected = steps.stream().map(StartupSequence.Step::name).toList();
            assertEquals(expected, recorded.stream().map(Timed::name).toList(), f.getFileName() + " order");

            for (int i = 0; i < steps.size(); i++) {
                StartupSequence.Step s = steps.get(i);
                long gap = recorded.get(i).gapMs();
                if (gap < s.minDelayMs() - TOLERANCE_MS || gap > s.stallMaxMs() + TOLERANCE_MS) {
                    violations.add(f.getFileName() + ": " + (cold ? "cold" : "warm") + " #" + i + " " + s.name()
                            + " pause " + gap + " ms, expected " + s.minDelayMs() + "–" + s.maxDelayMs()
                            + " (stalls up to " + s.stallMaxMs() + ")");
                }
            }
            Packet audio = packet(steps, "AudioSettingsRequest");
            assertEquals(String.valueOf(audio), String.valueOf(recorded.get(expected.indexOf("AudioSettingsRequest")).packet()),
                    f.getFileName() + " audio settings");
            checked++;
        }
        assertTrue(checked > 0);
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }

    private record Timed(String name, long gapMs, Packet packet) {
    }

    /**
     * Client packets after {@code LoginResponse} restricted to the kinds the sequence sends
     * (UI reactions such as moves, chat and ship info queries are left out), cut to its length.
     */
    private static List<Timed> recordedStartup(List<PacketRecord> packets, List<StartupSequence.Step> steps) {
        Set<String> kinds = steps.stream().map(StartupSequence.Step::name).collect(Collectors.toSet());
        List<Timed> out = new ArrayList<>();
        long prev = -1;
        for (PacketRecord r : packets) {
            if (prev < 0) {
                if (!r.up() && PacketCodec.decode(r.bytes()) instanceof LoginResponse) {
                    prev = r.nanos();
                }
                continue;
            }
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
            if (out.size() == steps.size()) {
                break;
            }
        }
        return out;
    }

    private static UserSettingsCommand settings(List<PacketRecord> packets) {
        for (PacketRecord r : packets) {
            if (!r.up() && PacketCodec.decode(r.bytes()) instanceof UserSettingsCommand us) {
                return us;
            }
        }
        return null;
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
