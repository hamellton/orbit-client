package orbit.sniffer;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.function.Predicate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Minimal SOCKS5 server (RFC 1928, CONNECT only, no auth) bound to localhost.
 * Game connections are decoded by {@link GameRelay}; everything else is piped as-is.
 */
final class Socks5Server {

    private static final Logger log = LoggerFactory.getLogger(Socks5Server.class);

    private final int port;
    private final PacketNames names;
    private final PacketDecoder decoder;
    private final Path recordDir;
    private final Predicate<String> isGameTarget;

    Socks5Server(int port, PacketNames names, PacketDecoder decoder, Path recordDir, Predicate<String> isGameTarget) {
        this.port = port;
        this.names = names;
        this.decoder = decoder;
        this.recordDir = recordDir;
        this.isGameTarget = isGameTarget;
    }

    void serve() throws IOException {
        try (ServerSocket ss = bind()) {
            serve(ss);
        }
    }

    /** Binds to loopback on the configured port (0 = any free port). */
    ServerSocket bind() throws IOException {
        ServerSocket ss = new ServerSocket();
        ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
        log.info("SOCKS5-прокси слушает 127.0.0.1:{}", ss.getLocalPort());
        return ss;
    }

    /** Accepts connections until the socket is closed. */
    void serve(ServerSocket ss) throws IOException {
        while (!ss.isClosed()) {
            Socket s;
            try {
                s = ss.accept();
            } catch (IOException e) {
                if (ss.isClosed()) {
                    return;
                }
                throw e;
            }
            Thread.ofVirtual().name("socks-" + s.getPort()).start(() -> handle(s));
        }
    }

    private void handle(Socket client) {
        Socket upstream = null;
        try {
            client.setTcpNoDelay(true);
            DataInputStream in = new DataInputStream(client.getInputStream());
            OutputStream out = client.getOutputStream();

            // greeting: VER NMETHODS METHODS...
            if (in.readUnsignedByte() != 5) {
                throw new IOException("not a SOCKS5 client");
            }
            in.skipNBytes(in.readUnsignedByte());
            out.write(new byte[] {5, 0}); // no authentication

            // request: VER CMD RSV ATYP DST.ADDR DST.PORT
            int ver = in.readUnsignedByte();
            int cmd = in.readUnsignedByte();
            in.readUnsignedByte();
            int atyp = in.readUnsignedByte();
            String host = switch (atyp) {
                case 1 -> InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
                case 3 -> new String(in.readNBytes(in.readUnsignedByte()), StandardCharsets.US_ASCII);
                case 4 -> InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
                default -> throw new IOException("bad address type " + atyp);
            };
            int dstPort = in.readUnsignedShort();
            if (ver != 5 || cmd != 1) {
                reply(out, 7);
                throw new IOException("unsupported SOCKS command " + cmd);
            }

            String target = host + ":" + dstPort;
            try {
                upstream = new Socket();
                upstream.setTcpNoDelay(true);
                upstream.connect(new InetSocketAddress(host, dstPort), 15_000);
            } catch (IOException e) {
                reply(out, 5); // connection refused
                log.warn("Не удалось подключиться к {}: {}", target, e.getMessage());
                return;
            }
            reply(out, 0);

            if (isGameTarget.test(target)) {
                new GameRelay(client, upstream, target, names, decoder, recordDir).run();
            } else {
                log.info("Туннель к {}", target);
                pipe(client, upstream);
            }
        } catch (IOException e) {
            log.debug("SOCKS-сессия завершена: {}", e.toString());
        } finally {
            close(client);
            close(upstream);
        }
    }

    private static void reply(OutputStream out, int status) throws IOException {
        out.write(new byte[] {5, (byte) status, 0, 1, 0, 0, 0, 0, 0, 0});
        out.flush();
    }

    static void pipe(Socket a, Socket b) {
        Thread t = Thread.ofVirtual().start(() -> copy(a, b));
        copy(b, a);
        try {
            t.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
        } catch (IOException ignored) {
            // one side closed
        } finally {
            close(from);
            close(to);
        }
    }

    private static void close(Socket s) {
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
