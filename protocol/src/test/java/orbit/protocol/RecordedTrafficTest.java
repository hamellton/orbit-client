package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.protocol.gen.Packets;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Decodes every packet of the local session recordings with the generated classes, encodes it
 * again and requires identical bytes. Recordings stay out of git (they contain account data),
 * so the test is skipped when the directory is empty.
 */
class RecordedTrafficTest {

    @Test
    void everyRecordedPacketRoundTripsByteForByte() throws Exception {
        Path dir = Path.of(System.getProperty("orbit.recordings", "recordings"));
        List<Path> files = new ArrayList<>();
        if (Files.isDirectory(dir)) {
            try (Stream<Path> s = Files.list(dir)) {
                s.filter(p -> p.toString().endsWith(".dorec")).sorted().forEach(files::add);
            }
        }
        Assumptions.assumeFalse(files.isEmpty(), "no recordings in " + dir.toAbsolutePath());

        long total = 0;
        Map<String, Integer> failures = new TreeMap<>();
        List<String> examples = new ArrayList<>();
        for (Path f : files) {
            for (Recording.PacketRecord r : Recording.readPackets(f)) {
                total++;
                String name = String.valueOf(Packets.name(r.id()));
                try {
                    Packet p = PacketCodec.decode(r.bytes());
                    byte[] again = PacketCodec.encode(p);
                    if (!Arrays.equals(r.bytes(), again)) {
                        failures.merge(name, 1, Integer::sum);
                        if (examples.size() < 10) {
                            examples.add(name + ": bytes differ after re-encode");
                        }
                    }
                } catch (RuntimeException e) {
                    failures.merge(name, 1, Integer::sum);
                    if (examples.size() < 10) {
                        examples.add(name + ": " + e.getMessage());
                    }
                }
            }
        }
        System.out.printf("Записанный трафик: %d файлов, %d пакетов, ошибок %d%n", files.size(), total,
                failures.values().stream().mapToInt(Integer::intValue).sum());
        assertTrue(total > 0, "recordings contain no packets");
        assertEquals(Map.of(), failures, String.join("\n", examples));
    }
}
