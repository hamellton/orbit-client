package orbit.sniffer;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Usage:
 * <pre>
 *   sniffer [--port 1080] [--packets reference/protocol/packets-*.json] [--out recordings]
 * </pre>
 * Then start the official client with {@code --proxy-server=socks5://127.0.0.1:1080}.
 */
public final class SnifferMain {

    private static final Logger log = LoggerFactory.getLogger(SnifferMain.class);

    /** Game and arena servers listen on 6000–6999 under *.darkorbit.com, or on 5001 by default. */
    static final Pattern GAME_TARGET =
            Pattern.compile("(?i)^(?:[a-z0-9-]+\\.)*darkorbit\\.com:(?:6\\d{3}|5001)$");

    private SnifferMain() {
    }

    public static void main(String[] args) throws Exception {
        int port = 1080;
        Path packets = null;
        Path out = Path.of("recordings");
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--packets" -> packets = Path.of(args[++i]);
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("Неизвестный параметр: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (packets == null) {
            packets = latestPacketsJson(Path.of("reference/protocol"));
        }
        PacketNames names = PacketNames.load(packets);
        log.info("Загружено {} описаний пакетов из {}", names.size(), packets);
        new Socks5Server(port, names, out, t -> GAME_TARGET.matcher(t).matches()).serve();
    }

    private static Path latestPacketsJson(Path dir) throws Exception {
        try (var s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().matches("packets-.*\\.json"))
                    .max(Path::compareTo)
                    .orElseThrow(() -> new IllegalStateException("Нет packets-*.json в " + dir));
        }
    }
}
