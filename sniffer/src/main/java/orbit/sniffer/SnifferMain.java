package orbit.sniffer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.regex.Pattern;
import orbit.protocol.gen.ProtocolVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Entry point. Usage:
 * <pre>
 *   sniffer [--port 1080] [--direct 170.33.12.50:6001-6007]
 *           [--out recordings]
 * </pre>
 * Web traffic goes through the SOCKS5 port ({@code --proxy-server=socks5://127.0.0.1:1080}).
 * The Flash plugin ignores the proxy, so game sockets are captured in direct mode: the client
 * is started with {@code --host-resolver-rules="MAP *.prod.darkorbit.com 127.0.0.1"}.
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
        Path out = Path.of("recordings");
        String direct = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--port" -> port = Integer.parseInt(args[++i]);
                case "--direct" -> direct = args[++i];
                case "--out" -> out = Path.of(args[++i]);
                default -> {
                    System.err.println("Неизвестный параметр: " + args[i]);
                    System.exit(2);
                }
            }
        }
        if (direct != null && direct.lastIndexOf(':') <= 0) {
            System.err.println("Формат --direct: IP:порты, например 170.33.12.50:6001-6007");
            System.exit(2);
        }
        start(port, direct, out).join();
    }

    /**
     * Starts the SOCKS5 server (and the direct-port relay when {@code direct} is {@code IP:ports})
     * in the background, e.g. inside the bot window's observer mode. Packets reach {@link PacketTap}s.
     *
     * @return the thread serving SOCKS5 connections
     */
    public static Thread start(int port, String direct, Path out) throws IOException {
        log.info("Протокол: сборка клиента {}, {} пакетов", ProtocolVersion.BUILD, ProtocolVersion.PACKET_COUNT);
        if (direct != null) {
            int colon = direct.lastIndexOf(':');
            new DirectForwarder(direct.substring(0, colon),
                    DirectForwarder.parsePorts(direct.substring(colon + 1)), out).start();
        }
        Socks5Server socks = new Socks5Server(port, out, t -> GAME_TARGET.matcher(t).matches());
        return Thread.ofPlatform().name("socks5").start(() -> {
            try {
                socks.serve();
            } catch (IOException e) {
                log.error("SOCKS5-сервер остановлен: {}", e.getMessage());
            }
        });
    }
}
