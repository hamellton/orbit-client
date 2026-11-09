package orbit.sniffer;

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
        log.info("Протокол: сборка клиента {}, {} пакетов", ProtocolVersion.BUILD, ProtocolVersion.PACKET_COUNT);
        if (direct != null) {
            int colon = direct.lastIndexOf(':');
            if (colon <= 0) {
                System.err.println("Формат --direct: IP:порты, например 170.33.12.50:6001-6007");
                System.exit(2);
            }
            new DirectForwarder(direct.substring(0, colon),
                    DirectForwarder.parsePorts(direct.substring(colon + 1)), out).start();
        }
        new Socks5Server(port, out, t -> GAME_TARGET.matcher(t).matches()).serve();
    }
}
