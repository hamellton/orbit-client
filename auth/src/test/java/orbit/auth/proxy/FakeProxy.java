package orbit.auth.proxy;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * A SOCKS5 or HTTP CONNECT proxy for tests. Whatever target is asked for, it connects to
 * {@code target} (so names like {@code www.darkorbit.com} work offline) and records what was asked.
 * A target host {@code refused.example} is refused.
 */
final class FakeProxy implements AutoCloseable {

    enum Kind { SOCKS5, HTTP }

    /** What a client asked for: SOCKS address type (1 IPv4, 3 name, 4 IPv6; 0 for HTTP) and host:port. */
    record Request(int atyp, String host, int port) {
    }

    final Kind kind;
    final String user;
    final String password;
    final InetSocketAddress target;
    final List<Request> requests = new CopyOnWriteArrayList<>();
    private final ServerSocket server;

    FakeProxy(Kind kind, String user, String password, InetSocketAddress target) throws IOException {
        this.kind = kind;
        this.user = user;
        this.password = password;
        this.target = target;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            while (!server.isClosed()) {
                try {
                    Socket s = server.accept();
                    Thread.ofVirtual().start(() -> serve(s));
                } catch (IOException e) {
                    return;
                }
            }
        });
    }

    int port() {
        return server.getLocalPort();
    }

    String url(String scheme, boolean withLogin) {
        return scheme + "://" + (withLogin ? user + ":" + password + "@" : "") + "127.0.0.1:" + port();
    }

    private void serve(Socket s) {
        try (s) {
            if (kind == Kind.SOCKS5) {
                socks(s);
            } else {
                http(s);
            }
        } catch (IOException ignored) {
            // client went away
        }
    }

    private void socks(Socket s) throws IOException {
        DataInputStream in = new DataInputStream(s.getInputStream());
        OutputStream out = s.getOutputStream();
        if (in.read() != 5) {
            out.write("HTTP/1.1 400 Bad Request\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        byte[] methods = in.readNBytes(in.read());
        boolean wantsLogin = user != null;
        boolean offered = false;
        for (byte m : methods) {
            offered |= m == (wantsLogin ? 2 : 0);
        }
        if (!offered) {
            out.write(new byte[] {5, (byte) 0xFF});
            return;
        }
        out.write(new byte[] {5, (byte) (wantsLogin ? 2 : 0)});
        if (wantsLogin) {
            in.read();
            String u = new String(in.readNBytes(in.read()), StandardCharsets.UTF_8);
            String p = new String(in.readNBytes(in.read()), StandardCharsets.UTF_8);
            boolean ok = u.equals(user) && p.equals(password);
            out.write(new byte[] {1, (byte) (ok ? 0 : 1)});
            if (!ok) {
                return;
            }
        }
        in.read();
        in.read();
        in.read();
        int atyp = in.read();
        String host = switch (atyp) {
            case 1 -> InetAddress.getByAddress(in.readNBytes(4)).getHostAddress();
            case 4 -> InetAddress.getByAddress(in.readNBytes(16)).getHostAddress();
            default -> new String(in.readNBytes(in.read()), StandardCharsets.US_ASCII);
        };
        int port = in.readUnsignedShort();
        requests.add(new Request(atyp, host, port));
        if (host.equals("refused.example")) {
            out.write(new byte[] {5, 5, 0, 1, 0, 0, 0, 0, 0, 0});
            return;
        }
        try (Socket t = new Socket()) {
            t.connect(target, 2_000);
            out.write(new byte[] {5, 0, 0, 1, 127, 0, 0, 1, 0, 0});
            out.flush();
            pipe(s, t);
        }
    }

    private void http(Socket s) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        String head = ProxyConnector.readHead(in);
        String[] line = head.substring(0, head.indexOf("\r\n")).split(" ");
        if (!line[0].equals("CONNECT")) {
            out.write("HTTP/1.1 405 Method Not Allowed\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        int colon = line[1].lastIndexOf(':');
        String host = line[1].substring(0, colon);
        requests.add(new Request(0, host, Integer.parseInt(line[1].substring(colon + 1))));
        if (user != null) {
            String expected = "Proxy-Authorization: Basic " + Base64.getEncoder()
                    .encodeToString((user + ":" + password).getBytes(StandardCharsets.UTF_8));
            if (!head.contains(expected + "\r\n")) {
                out.write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic\r\n\r\n"
                        .getBytes(StandardCharsets.ISO_8859_1));
                return;
            }
        }
        if (host.equals("refused.example")) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            return;
        }
        try (Socket t = new Socket()) {
            t.connect(target, 2_000);
            out.write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
            out.flush();
            pipe(s, t);
        }
    }

    static void pipe(Socket a, Socket b) {
        Thread back = Thread.ofVirtual().start(() -> copy(b, a));
        copy(a, b);
        try {
            back.join();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static void copy(Socket from, Socket to) {
        try {
            from.getInputStream().transferTo(to.getOutputStream());
            to.shutdownOutput();
        } catch (IOException ignored) {
            // closed
        }
    }

    @Override
    public void close() throws IOException {
        server.close();
    }

    /** A TCP server that echoes every connection back. */
    static final class Echo implements AutoCloseable {
        final ServerSocket server;

        Echo() throws IOException {
            server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
            Thread.ofVirtual().start(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket s = server.accept();
                        Thread.ofVirtual().start(() -> {
                            try (s) {
                                s.getInputStream().transferTo(s.getOutputStream());
                            } catch (IOException ignored) {
                                // closed
                            }
                        });
                    } catch (IOException e) {
                        return;
                    }
                }
            });
        }

        InetSocketAddress address() {
            return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
        }

        @Override
        public void close() throws IOException {
            server.close();
        }
    }
}
