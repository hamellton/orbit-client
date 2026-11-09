package orbit.sniffer;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Listens on loopback ports and forwards each accepted connection to the same port
 * on a fixed upstream address, decoding it with {@link GameRelay}.
 *
 * <p>Used when the Flash plugin bypasses the browser proxy: the client is started with
 * {@code --host-resolver-rules="MAP *.prod.darkorbit.com 127.0.0.1"}, so game sockets land here.
 * The upstream is an IP address on purpose — resolving the game host names locally could
 * loop back to us if they are redirected via the hosts file.
 */
final class DirectForwarder {

    private static final Logger log = LoggerFactory.getLogger(DirectForwarder.class);

    private final String upstreamIp;
    private final List<Integer> ports;
    private final PacketNames names;
    private final Path recordDir;

    DirectForwarder(String upstreamIp, List<Integer> ports, PacketNames names, Path recordDir) {
        this.upstreamIp = upstreamIp;
        this.ports = List.copyOf(ports);
        this.names = names;
        this.recordDir = recordDir;
    }

    /** Parses {@code "6001-6007"} or {@code "6003,6005"} into a list of ports. */
    static List<Integer> parsePorts(String spec) {
        List<Integer> out = new ArrayList<>();
        for (String part : spec.split(",")) {
            String p = part.trim();
            int dash = p.indexOf('-');
            if (dash > 0) {
                int from = Integer.parseInt(p.substring(0, dash));
                int to = Integer.parseInt(p.substring(dash + 1));
                for (int i = from; i <= to; i++) {
                    out.add(i);
                }
            } else {
                out.add(Integer.parseInt(p));
            }
        }
        return out;
    }

    /** Binds all ports and serves them on virtual threads; returns immediately. */
    List<ServerSocket> start() throws IOException {
        List<ServerSocket> sockets = new ArrayList<>();
        for (int port : ports) {
            ServerSocket ss = new ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
            sockets.add(ss);
            Thread.ofVirtual().name("direct-" + port).start(() -> acceptLoop(ss, port));
        }
        log.info("Прямой режим: 127.0.0.1:{} → {}", ports, upstreamIp);
        return sockets;
    }

    private void acceptLoop(ServerSocket ss, int port) {
        while (!ss.isClosed()) {
            try {
                Socket client = ss.accept();
                Thread.ofVirtual().start(() -> handle(client, port));
            } catch (IOException e) {
                if (!ss.isClosed()) {
                    log.warn("Порт {}: ошибка accept: {}", port, e.getMessage());
                }
            }
        }
    }

    private void handle(Socket client, int port) {
        String target = upstreamIp + ":" + port;
        try (client; Socket server = new Socket()) {
            client.setTcpNoDelay(true);
            server.setTcpNoDelay(true);
            server.connect(new InetSocketAddress(upstreamIp, port), 15_000);
            new GameRelay(client, server, target, names, recordDir).run();
        } catch (IOException e) {
            log.warn("Не удалось переслать соединение на {}: {}", target, e.getMessage());
        }
    }
}
