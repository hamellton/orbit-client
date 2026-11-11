package orbit.auth.proxy;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.regex.Pattern;
import orbit.secrets.ProxySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Opens TCP connections for one account: directly or through its SOCKS5 / HTTP proxy.
 *
 * <p>Written by hand instead of {@code new Socket(java.net.Proxy)}: Java resolves the target name
 * locally before a SOCKS connect (a DNS leak, and a CDN address picked for the wrong country), takes
 * SOCKS logins from one process-wide {@code Authenticator} (several accounts behind one gateway with
 * different logins would mix up) and does not authenticate HTTP {@code CONNECT} tunnels. Here the
 * proxy gets the host name, and every account has its own login.
 *
 * <p>Thread-safe.
 */
public final class ProxyConnector {

    private static final Logger log = LoggerFactory.getLogger(ProxyConnector.class);
    private static final Pattern IPV4 = Pattern.compile("\\d{1,3}(\\.\\d{1,3}){3}");
    private static final int MAX_HEAD = 16_384;

    public static final ProxyConnector DIRECT = new ProxyConnector(ProxySpec.DIRECT);

    private volatile ProxySpec spec;

    public ProxyConnector(ProxySpec spec) {
        this.spec = spec;
    }

    /** The proxy; after the first connection an {@link ProxySpec.Type#AUTO} proxy has its real type. */
    public ProxySpec spec() {
        return spec;
    }

    public boolean direct() {
        return spec.direct();
    }

    /** A new socket connected to {@code host:port} (through the proxy). */
    public Socket open(String host, int port, int timeoutMs) throws IOException {
        Socket s = new Socket();
        try {
            connect(s, host, port, timeoutMs);
            return s;
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
    }

    /**
     * Connects an unconnected socket to {@code host:port}: afterwards its streams talk to the target.
     * Lets the caller keep the socket to close it from another thread while this blocks.
     */
    public void connect(Socket s, String host, int port, int timeoutMs) throws IOException {
        ProxySpec p = spec;
        if (p.direct()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            return;
        }
        if (p.type() == ProxySpec.Type.AUTO) {
            p = detect(p, timeoutMs);
        }
        connectToProxy(s, p, timeoutMs);
        int before = s.getSoTimeout();
        s.setSoTimeout(timeoutMs);
        try {
            if (p.type() == ProxySpec.Type.SOCKS5) {
                socks5(s, p, host, port);
            } else {
                httpConnect(s, p, host, port);
            }
        } catch (SocketTimeoutException e) {
            throw new ProxyException(ProxyException.Kind.TARGET,
                    "прокси не ответил за " + timeoutMs / 1000 + " с", e);
        } catch (EOFException e) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL,
                    "прокси оборвал соединение", e);
        } finally {
            if (!s.isClosed()) {
                s.setSoTimeout(before);
            }
        }
    }

    private static void connectToProxy(Socket s, ProxySpec p, int timeoutMs) throws ProxyException {
        try {
            s.connect(new InetSocketAddress(p.host(), p.port()), timeoutMs);
        } catch (IOException e) {
            throw new ProxyException(ProxyException.Kind.UNREACHABLE,
                    "прокси недоступен: " + reason(e), e);
        }
    }

    /** Asks the proxy for SOCKS5 on a throw-away connection: a SOCKS5 proxy answers with byte 5. */
    private ProxySpec detect(ProxySpec p, int timeoutMs) throws IOException {
        ProxySpec.Type found = ProxySpec.Type.HTTP;
        try (Socket probe = new Socket()) {
            connectToProxy(probe, p, timeoutMs);
            probe.setSoTimeout(Math.min(timeoutMs, 5_000));
            OutputStream out = probe.getOutputStream();
            out.write(p.hasCredentials() ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
            out.flush();
            if (probe.getInputStream().read() == 5) {
                found = ProxySpec.Type.SOCKS5;
            }
        } catch (ProxyException e) {
            throw e;
        } catch (IOException e) {
            // closed or silent on SOCKS bytes: an HTTP proxy waits for a request line
        }
        ProxySpec resolved = p.withType(found);
        spec = resolved;
        log.info("Прокси {}: тип определён — {}", p.address(), found == ProxySpec.Type.SOCKS5 ? "SOCKS5" : "HTTP");
        return resolved;
    }

    // ---------------------------------------------------------------- SOCKS5 (RFC 1928, RFC 1929)

    private static void socks5(Socket s, ProxySpec p, String host, int port) throws IOException {
        InputStream in = s.getInputStream();
        OutputStream out = s.getOutputStream();
        out.write(p.hasCredentials() ? new byte[] {5, 2, 0, 2} : new byte[] {5, 1, 0});
        out.flush();
        int version;
        int method;
        try {
            version = readByte(in);
            method = readByte(in);
        } catch (SocketTimeoutException | EOFException e) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL, "прокси не SOCKS5 — укажите http://", e);
        }
        if (version != 5) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL, "это не SOCKS5-прокси" + (version == 'H' ? " — укажите http://" : ""));
        }
        if (method == 0xFF) {
            throw new ProxyException(ProxyException.Kind.AUTH, p.hasCredentials()
                    ? "прокси не принимает логин и пароль"
                    : "прокси требует логин и пароль");
        }
        if (method == 2) {
            if (!p.hasCredentials()) {
                throw new ProxyException(ProxyException.Kind.AUTH, "прокси требует логин и пароль");
            }
            byte[] user = p.user().getBytes(StandardCharsets.UTF_8);
            byte[] pass = (p.password() == null ? "" : p.password()).getBytes(StandardCharsets.UTF_8);
            if (user.length > 255 || pass.length > 255) {
                throw new ProxyException(ProxyException.Kind.AUTH, "логин или пароль прокси длиннее 255 байт");
            }
            ByteArrayOutputStream auth = new ByteArrayOutputStream();
            auth.write(1);
            auth.write(user.length);
            auth.writeBytes(user);
            auth.write(pass.length);
            auth.writeBytes(pass);
            out.write(auth.toByteArray());
            out.flush();
            readByte(in);
            if (readByte(in) != 0) {
                throw new ProxyException(ProxyException.Kind.AUTH, "прокси отклонил логин или пароль");
            }
        } else if (method != 0) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL,
                    "прокси: неизвестный способ входа " + method);
        }

        ByteArrayOutputStream req = new ByteArrayOutputStream();
        req.writeBytes(new byte[] {5, 1, 0});
        byte[] literal = literalAddress(host);
        if (literal != null) {
            req.write(literal.length == 4 ? 1 : 4);
            req.writeBytes(literal);
        } else {
            byte[] name = host.getBytes(StandardCharsets.US_ASCII);
            if (name.length > 255) {
                throw new ProxyException(ProxyException.Kind.TARGET, "имя хоста длиннее 255 байт: " + host);
            }
            req.write(3);
            req.write(name.length);
            req.writeBytes(name);
        }
        req.write(port >> 8);
        req.write(port & 0xFF);
        out.write(req.toByteArray());
        out.flush();

        readByte(in);
        int rep = readByte(in);
        readByte(in);
        int atyp = readByte(in);
        int skip = switch (atyp) {
            case 1 -> 4;
            case 4 -> 16;
            case 3 -> readByte(in);
            default -> throw new ProxyException(ProxyException.Kind.PROTOCOL,
                    "прокси: неизвестный тип адреса " + atyp);
        };
        in.readNBytes(skip + 2);
        if (rep != 0) {
            throw new ProxyException(ProxyException.Kind.TARGET,
                    "прокси не подключился к " + host + ":" + port + ": " + socksReply(rep));
        }
    }

    static String socksReply(int rep) {
        return switch (rep) {
            case 1 -> "внутренняя ошибка прокси";
            case 2 -> "запрещено правилами прокси";
            case 3 -> "сеть недоступна";
            case 4 -> "хост недоступен";
            case 5 -> "соединение отклонено";
            case 6 -> "истекло время (TTL)";
            case 7 -> "команда не поддерживается";
            case 8 -> "тип адреса не поддерживается";
            default -> "код " + rep;
        };
    }

    /** The bytes of an IPv4/IPv6 literal, {@code null} for a name (resolved by the proxy). */
    static byte[] literalAddress(String host) {
        try {
            if (IPV4.matcher(host).matches() || host.indexOf(':') >= 0) {
                return InetAddress.getByName(host).getAddress(); // a literal: no DNS lookup
            }
        } catch (IOException e) {
            // not a literal after all
        }
        return null;
    }

    // ---------------------------------------------------------------- HTTP CONNECT

    private static void httpConnect(Socket s, ProxySpec p, String host, int port) throws IOException {
        String target = (host.indexOf(':') >= 0 ? "[" + host + "]" : host) + ":" + port;
        StringBuilder req = new StringBuilder()
                .append("CONNECT ").append(target).append(" HTTP/1.1\r\n")
                .append("Host: ").append(target).append("\r\n");
        if (p.hasCredentials()) {
            String pair = p.user() + ":" + (p.password() == null ? "" : p.password());
            req.append("Proxy-Authorization: Basic ")
                    .append(Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8))).append("\r\n");
        }
        req.append("\r\n");
        OutputStream out = s.getOutputStream();
        out.write(req.toString().getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        String head;
        try {
            head = readHead(s.getInputStream());
        } catch (EOFException e) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL, "прокси не HTTP — укажите socks5://", e);
        }
        String status = head.lines().findFirst().orElse("");
        String[] parts = status.split(" ", 3);
        if (parts.length < 2 || !parts[0].startsWith("HTTP/")) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL, "это не HTTP-прокси"
                    + (status.isEmpty() || status.charAt(0) == 5 ? " — укажите socks5://" : ""));
        }
        int code;
        try {
            code = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new ProxyException(ProxyException.Kind.PROTOCOL, "прокси ответил непонятно: " + status);
        }
        if (code / 100 == 2) {
            return;
        }
        String phrase = parts.length > 2 ? " (" + code + " " + parts[2] + ")" : " (" + code + ")";
        throw switch (code) {
            case 407 -> new ProxyException(ProxyException.Kind.AUTH, p.hasCredentials()
                    ? "прокси отклонил логин или пароль"
                    : "прокси требует логин и пароль");
            case 403, 405 -> new ProxyException(ProxyException.Kind.TARGET,
                    "прокси запретил соединение с " + target + phrase);
            default -> new ProxyException(ProxyException.Kind.TARGET,
                    "прокси не подключился к " + target + phrase);
        };
    }

    /** Status line and headers, byte by byte so nothing of the tunnel is consumed. */
    static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        int matched = 0;
        while (matched < 4) {
            int b = in.read();
            if (b < 0) {
                if (head.size() == 0) {
                    throw new EOFException();
                }
                break;
            }
            head.write(b);
            if (head.size() > MAX_HEAD) {
                throw new IOException("слишком длинный ответ прокси");
            }
            matched = (b == '\r' && (matched == 0 || matched == 2)) || (b == '\n' && (matched == 1 || matched == 3))
                    ? matched + 1 : b == '\r' ? 1 : 0;
        }
        return head.toString(StandardCharsets.ISO_8859_1);
    }

    private static int readByte(InputStream in) throws IOException {
        int b = in.read();
        if (b < 0) {
            throw new EOFException();
        }
        return b;
    }

    static String reason(IOException e) {
        String m = e.getMessage();
        if (e instanceof SocketTimeoutException) {
            return "нет ответа";
        }
        if (e instanceof java.net.UnknownHostException) {
            return "адрес не найден (" + m + ")";
        }
        if (e instanceof java.net.ConnectException) {
            return "соединение отклонено";
        }
        return m == null ? e.getClass().getSimpleName() : m;
    }
}
