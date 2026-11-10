package orbit.auth.proxy;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * An HTTP proxy on {@code 127.0.0.1} without a login that forwards through one account's
 * {@link ProxyConnector}. Java's HTTP clients then use it as a plain {@link Proxy.Type#HTTP} proxy: they
 * send {@code CONNECT host:443} with the host name (no local DNS lookup) and need no credentials, while
 * the connector does the real proxy's SOCKS5 or HTTP login. Plain {@code http://} requests are
 * forwarded too (request line rewritten to the origin form, one request per connection).
 *
 * <p>Listens on the loopback interface only.
 */
public final class LocalProxy implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(LocalProxy.class);
    private static final int CONNECT_TIMEOUT_MS = 20_000;

    private final ProxyConnector upstream;
    private final ServerSocket server;
    private final Map<String, String> logContext;
    private volatile String lastError;

    private LocalProxy(ProxyConnector upstream) throws IOException {
        this.upstream = upstream;
        this.server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        this.logContext = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("local-proxy-" + server.getLocalPort()).start(this::acceptLoop);
    }

    /** Starts a relay for the connector (which must not be direct). */
    public static LocalProxy start(ProxyConnector upstream) throws IOException {
        if (upstream.direct()) {
            throw new IllegalArgumentException("для прямого соединения посредник не нужен");
        }
        return new LocalProxy(upstream);
    }

    /** For {@code HttpURLConnection.openConnection(proxy)}. */
    public Proxy proxy() {
        return new Proxy(Proxy.Type.HTTP, address());
    }

    /** For {@code java.net.http.HttpClient.Builder.proxy}. */
    public ProxySelector selector() {
        return ProxySelector.of(address());
    }

    public InetSocketAddress address() {
        return new InetSocketAddress(InetAddress.getLoopbackAddress(), server.getLocalPort());
    }

    /** The last upstream failure in words (an HTTP client only sees a 502), or {@code null}. */
    public String lastError() {
        return lastError;
    }

    @Override
    public void close() {
        try {
            server.close();
        } catch (IOException ignored) {
            // closing anyway
        }
    }

    private void acceptLoop() {
        while (!server.isClosed()) {
            try {
                Socket client = server.accept();
                Thread.ofVirtual().name("local-proxy-conn").start(() -> handle(client));
            } catch (IOException e) {
                if (!server.isClosed()) {
                    log.debug("Локальный прокси: {}", e.getMessage());
                }
            }
        }
    }

    private void handle(Socket client) {
        if (logContext != null) {
            org.slf4j.MDC.setContextMap(logContext);
        }
        Socket target = null;
        try (client) {
            client.setSoTimeout(CONNECT_TIMEOUT_MS);
            InputStream in = client.getInputStream();
            OutputStream out = client.getOutputStream();
            String head = ProxyConnector.readHead(in);
            int lineEnd = head.indexOf("\r\n");
            String requestLine = lineEnd < 0 ? head : head.substring(0, lineEnd);
            String[] parts = requestLine.split(" ");
            if (parts.length != 3) {
                reply(out, "400 Bad Request");
                return;
            }
            String method = parts[0].toUpperCase(Locale.ROOT);
            byte[] forward;
            String host;
            int port;
            if (method.equals("CONNECT")) {
                String hp = parts[1];
                int colon = hp.lastIndexOf(':');
                if (colon < 0) {
                    reply(out, "400 Bad Request");
                    return;
                }
                host = hp.substring(0, colon);
                if (host.startsWith("[") && host.endsWith("]")) {
                    host = host.substring(1, host.length() - 1);
                }
                port = Integer.parseInt(hp.substring(colon + 1));
                forward = null;
            } else {
                URI uri = URI.create(parts[1]);
                if (!"http".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null) {
                    reply(out, "400 Bad Request");
                    return;
                }
                host = uri.getHost();
                port = uri.getPort() < 0 ? 80 : uri.getPort();
                forward = originRequest(parts, uri, head.substring(lineEnd + 2));
            }
            try {
                target = upstream.open(host, port, CONNECT_TIMEOUT_MS);
            } catch (IOException e) {
                lastError = e.getMessage();
                log.warn("Прокси: {}", e.getMessage());
                reply(out, "502 Bad Gateway");
                return;
            }
            client.setSoTimeout(0);
            if (forward == null) {
                out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush();
            } else {
                target.getOutputStream().write(forward);
                target.getOutputStream().flush();
            }
            pipe(client, target);
        } catch (IOException | RuntimeException e) {
            log.debug("Локальный прокси, соединение: {}", e.toString());
        } finally {
            if (target != null) {
                try {
                    target.close();
                } catch (IOException ignored) {
                    // done
                }
            }
        }
    }

    /** {@code GET http://h/p HTTP/1.1} → {@code GET /p HTTP/1.1}, no proxy headers, one request per connection. */
    static byte[] originRequest(String[] parts, URI uri, String headers) {
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) {
            path += "?" + uri.getRawQuery();
        }
        StringBuilder sb = new StringBuilder(parts[0]).append(' ').append(path).append(' ').append(parts[2]).append("\r\n");
        for (String line : headers.split("\r\n")) {
            if (line.isEmpty()) {
                continue;
            }
            String name = line.substring(0, Math.max(0, line.indexOf(':'))).strip().toLowerCase(Locale.ROOT);
            if (name.startsWith("proxy-") || name.equals("connection") || name.equals("keep-alive")) {
                continue;
            }
            sb.append(line).append("\r\n");
        }
        sb.append("Connection: close\r\n\r\n");
        return sb.toString().getBytes(StandardCharsets.ISO_8859_1);
    }

    private static void reply(OutputStream out, String status) throws IOException {
        out.write(("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.ISO_8859_1));
        out.flush();
    }

    /** Copies both ways until both sides are done. */
    private static void pipe(Socket a, Socket b) {
        Thread back = Thread.ofVirtual().name("local-proxy-down").start(() -> copy(b, a));
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
        } catch (IOException ignored) {
            // the other side closed
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }
}
