package orbit.sniffer;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import orbit.net.Downstream;

/**
 * Prints a summary of recorded sessions: packets per name and direction, unknown ids,
 * the opening sequence and keep-alive timing.
 *
 * <pre>
 *   session-report [--packets reference/protocol/packets-*.json] recordings/*.dorec | recordings
 * </pre>
 */
public final class SessionReport {

    /** One decoded packet of a recording. */
    record Entry(long nanos, boolean up, short id, int length, byte[] bytes) {
    }

    private SessionReport() {
    }

    public static void main(String[] args) throws Exception {
        Path packets = null;
        boolean decode = false;
        java.util.Set<String> show = new java.util.HashSet<>();
        Map<String, Integer> shown = new java.util.HashMap<>();
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--packets")) {
                packets = Path.of(args[++i]);
            } else if (args[i].equals("--decode")) {
                decode = true;
            } else if (args[i].equals("--show")) {
                decode = true;
                show.addAll(List.of(args[++i].split(",")));
            } else {
                inputs.add(Path.of(args[i]));
            }
        }
        if (inputs.isEmpty()) {
            inputs.add(Path.of("recordings"));
        }
        Path json = packets != null ? packets : latest(Path.of("reference/protocol"));
        PacketNames names = PacketNames.load(json);
        PacketDecoder decoder = decode ? PacketDecoder.load(json) : null;
        Map<String, long[]> decodeFailures = new TreeMap<>();
        Map<String, String> failureExample = new TreeMap<>();
        long decoded = 0;

        List<Path> files = new ArrayList<>();
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> s = Files.list(in)) {
                    s.filter(p -> p.toString().endsWith(".dorec")).sorted().forEach(files::add);
                }
            } else {
                files.add(in);
            }
        }

        Map<String, long[]> perName = new TreeMap<>();
        long total = 0;
        long unknown = 0;
        for (Path f : files) {
            List<Entry> entries = read(f);
            System.out.printf("%n== %s: %d пакетов, %.1f с%n", f.getFileName(), entries.size(),
                    entries.isEmpty() ? 0.0 : entries.getLast().nanos() / 1e9);
            System.out.println("Начало сессии:");
            entries.stream().limit(12).forEach(e -> System.out.printf("  %7.3f с %s %s(%d) %d байт%n",
                    e.nanos() / 1e9, e.up() ? "→" : "←", names.name(e.id()), e.id(), e.length()));
            printKeepAlive(entries, names);
            for (Entry e : entries) {
                total++;
                if (!names.known(e.id())) {
                    unknown++;
                }
                String key = (e.up() ? "→ " : "← ") + names.name(e.id());
                perName.computeIfAbsent(key, k -> new long[1])[0]++;
                if (decoder != null) {
                    try {
                        PacketDecoder.Module m = decoder.decode(e.bytes());
                        decoded++;
                        if (show.contains(m.name()) && shown.merge(m.name(), 1, Integer::sum) <= 4) {
                            System.out.println("  " + (e.up() ? "→ " : "← ") + m);
                        }
                    } catch (RuntimeException ex) {
                        decodeFailures.computeIfAbsent(key, k -> new long[1])[0]++;
                        failureExample.putIfAbsent(key, ex.getMessage());
                    }
                }
            }
        }

        System.out.printf("%n== Всего: %d пакетов, неизвестных: %d, разных типов: %d%n", total, unknown, perName.size());
        if (decoder != null) {
            long failed = decodeFailures.values().stream().mapToLong(v -> v[0]).sum();
            System.out.printf("Разбор полей: успешно %d, с ошибкой %d (%d типов)%n", decoded, failed, decodeFailures.size());
            decodeFailures.forEach((k, v) -> System.out.printf("  %6d  %s — %s%n", v[0], k, failureExample.get(k)));
        }
        System.out.println("Клиент → сервер:");
        print(perName, "→ ");
        System.out.println("Сервер → клиент (топ-40):");
        perName.entrySet().stream()
                .filter(e -> e.getKey().startsWith("← "))
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(40)
                .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue()[0], e.getKey().substring(2)));
    }

    private static void print(Map<String, long[]> perName, String prefix) {
        perName.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue()[0], e.getKey().substring(2)));
    }

    private static void printKeepAlive(List<Entry> entries, PacketNames names) {
        List<Double> times = entries.stream()
                .filter(e -> e.up() && names.name(e.id()).equals("KeepAlive"))
                .map(e -> e.nanos() / 1e9)
                .toList();
        if (times.size() < 2) {
            System.out.println("KeepAlive: " + times.size() + " шт.");
            return;
        }
        double min = Double.MAX_VALUE;
        double max = 0;
        for (int i = 1; i < times.size(); i++) {
            double d = times.get(i) - times.get(i - 1);
            min = Math.min(min, d);
            max = Math.max(max, d);
        }
        System.out.printf("KeepAlive: %d шт., интервал %.2f–%.2f с%n", times.size(), min, max);
    }

    /** Reads a DOREC1 file and splits every frame into packets. */
    static List<Entry> read(Path file) throws IOException {
        List<Entry> out = new ArrayList<>();
        boolean compression = false;
        try (InputStream raw = Files.newInputStream(file);
                DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(raw))) {
            if (!new String(in.readNBytes(6), StandardCharsets.US_ASCII).equals("DOREC1")) {
                throw new IOException("not a DOREC1 file: " + file);
            }
            boolean first = true;
            while (true) {
                long nanos;
                try {
                    nanos = in.readLong();
                } catch (EOFException end) {
                    break;
                }
                boolean up = in.readByte() == SessionRecorder.UP;
                byte[] payload = in.readNBytes(in.readInt());
                if (up && first) {
                    first = false; // handshake byte
                    continue;
                }
                if (payload.length < 2) {
                    continue;
                }
                if (up) {
                    String v = Secrets.loginVersion(payload);
                    if (v != null) {
                        compression = v.startsWith("do-lz4-");
                    }
                    out.add(new Entry(nanos, true, Downstream.packetId(payload), payload.length, payload));
                } else {
                    for (byte[] p : Downstream.split(payload, compression)) {
                        out.add(new Entry(nanos, false, Downstream.packetId(p), p.length, p));
                    }
                }
            }
        }
        return out;
    }

    private static Path latest(Path dir) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("packets-.*\\.json"))
                    .max(Path::compareTo)
                    .orElseThrow(() -> new IOException("Нет packets-*.json в " + dir));
        }
    }
}
