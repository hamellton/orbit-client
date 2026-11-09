package orbit.sniffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.net.Recording.PacketRecord;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.Packets;

/**
 * Summary of recorded sessions using the generated protocol classes.
 *
 * <pre>
 *   session-report [--show Name1,Name2] [--upstream SECONDS] [recordings dir | *.dorec ...]
 * </pre>
 * Prints the opening sequence and keep-alive timing per file, counts per packet type and
 * direction, and every packet that fails to decode. {@code --show} prints up to four decoded
 * examples of the named packets. {@code --upstream} prints every client→server packet of the
 * first SECONDS after the login answer, decoded, with the time since that answer.
 */
public final class SessionReport {

    private SessionReport() {
    }

    public static void main(String[] args) throws Exception {
        Set<String> show = new HashSet<>();
        double upstreamSeconds = 0;
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--show")) {
                show.addAll(List.of(args[++i].split(",")));
            } else if (args[i].equals("--upstream")) {
                upstreamSeconds = Double.parseDouble(args[++i]);
            } else {
                inputs.add(Path.of(args[i]));
            }
        }
        if (inputs.isEmpty()) {
            inputs.add(Path.of("recordings"));
        }
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
        Map<String, long[]> failures = new TreeMap<>();
        Map<String, String> failureExample = new TreeMap<>();
        Map<String, Integer> shown = new HashMap<>();
        long total = 0;
        long unknown = 0;
        for (Path f : files) {
            List<PacketRecord> packets = Recording.readPackets(f);
            System.out.printf("%n== %s: %d пакетов, %.1f с%n", f.getFileName(), packets.size(),
                    packets.isEmpty() ? 0.0 : packets.getLast().nanos() / 1e9);
            System.out.println("Начало сессии:");
            packets.stream().limit(12).forEach(r -> System.out.printf("  %7.3f с %s %s(%d) %d байт%n",
                    r.nanos() / 1e9, r.up() ? "→" : "←", nameOf(r.id()), r.id(), r.bytes().length));
            printKeepAlive(packets);
            if (upstreamSeconds > 0) {
                printUpstream(packets, upstreamSeconds);
            }

            for (PacketRecord r : packets) {
                total++;
                if (!Packets.known(r.id())) {
                    unknown++;
                }
                String key = (r.up() ? "→ " : "← ") + nameOf(r.id());
                perName.computeIfAbsent(key, k -> new long[1])[0]++;
                try {
                    Packet p = PacketCodec.decode(r.bytes());
                    String name = p.getClass().getSimpleName();
                    if (show.contains(name) && shown.merge(name, 1, Integer::sum) <= 4) {
                        System.out.println("  " + (r.up() ? "→ " : "← ") + p);
                    }
                } catch (RuntimeException e) {
                    failures.computeIfAbsent(key, k -> new long[1])[0]++;
                    failureExample.putIfAbsent(key, e.getMessage());
                }
            }
        }

        long failed = failures.values().stream().mapToLong(v -> v[0]).sum();
        System.out.printf("%n== Всего: %d пакетов, неизвестных: %d, разных типов: %d, ошибок разбора: %d%n",
                total, unknown, perName.size(), failed);
        failures.forEach((k, v) -> System.out.printf("  %6d  %s — %s%n", v[0], k, failureExample.get(k)));
        System.out.println("Клиент → сервер:");
        print(perName, "→ ", Integer.MAX_VALUE);
        System.out.println("Сервер → клиент (топ-40):");
        print(perName, "← ", 40);
    }

    private static String nameOf(short id) {
        String n = Packets.name(id);
        return n != null ? n : "?" + id;
    }

    private static void print(Map<String, long[]> perName, String prefix, int limit) {
        perName.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .sorted(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0]).reversed())
                .limit(limit)
                .forEach(e -> System.out.printf("  %6d  %s%n", e.getValue()[0], e.getKey().substring(2)));
    }

    /** Client packets after the first server packet that follows {@code LoginRequest}. */
    private static void printUpstream(List<PacketRecord> packets, double seconds) {
        int login = -1;
        for (int i = 0; i < packets.size(); i++) {
            if (packets.get(i).up() && "LoginRequest".equals(Packets.name(packets.get(i).id()))) {
                login = i;
                break;
            }
        }
        if (login < 0) {
            System.out.println("Нет LoginRequest.");
            return;
        }
        long t0 = -1;
        for (int i = login + 1; i < packets.size(); i++) {
            if (!packets.get(i).up()) {
                t0 = packets.get(i).nanos();
                break;
            }
        }
        if (t0 < 0) {
            return;
        }
        System.out.printf("Исходящие за %.0f с после ответа на вход:%n", seconds);
        long prev = t0;
        for (PacketRecord r : packets) {
            if (!r.up() || r.nanos() < t0) {
                continue;
            }
            if (r.nanos() - t0 > seconds * 1e9) {
                break;
            }
            String text;
            try {
                text = PacketCodec.decode(r.bytes()).toString();
            } catch (RuntimeException e) {
                text = nameOf(r.id()) + " (не разобран: " + e.getMessage() + ")";
            }
            System.out.printf("  +%6.3f с (Δ%5.3f) %s%n", (r.nanos() - t0) / 1e9, (r.nanos() - prev) / 1e9, text);
            prev = r.nanos();
        }
    }

    private static void printKeepAlive(List<PacketRecord> packets) {
        List<Double> times = packets.stream()
                .filter(r -> r.up() && "KeepAlive".equals(Packets.name(r.id())))
                .map(r -> r.nanos() / 1e9)
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
}
