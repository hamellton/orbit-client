package orbit.secrets;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A proxy of one account, parsed from the text the user typed.
 *
 * <p>Accepted forms (what proxy sellers hand out):
 * <pre>
 *   socks5://user:pass@host:port     http://user:pass@host:port     socks5://host:port
 *   host:port:user:pass              user:pass@host:port            host:port
 * </pre>
 * Without a scheme the type is {@link Type#AUTO}: the first connection finds out whether the proxy
 * speaks SOCKS5 or HTTP. URL forms may percent-encode the user and password ({@code p%40ss}).
 *
 * @param type     {@link Type#DIRECT} for no proxy
 * @param host     proxy host
 * @param port     proxy port
 * @param user     login, or {@code null}
 * @param password password (secret), or {@code null}
 */
public record ProxySpec(Type type, String host, int port, String user, String password) {

    public enum Type {
        /** No proxy. */
        DIRECT,
        /** SOCKS5 (RFC 1928), with login and password (RFC 1929) when given; names are resolved by the proxy. */
        SOCKS5,
        /** HTTP proxy with {@code CONNECT} tunnels, Basic authentication when a login is given. */
        HTTP,
        /** SOCKS5 or HTTP, found out on the first connection. */
        AUTO
    }

    public static final ProxySpec DIRECT = new ProxySpec(Type.DIRECT, null, 0, null, null);

    /** {@code host:port:rest} with digits right after the first colon. */
    private static final java.util.regex.Pattern SELLER =
            java.util.regex.Pattern.compile("(\\[[^\\]]+\\]|[^:@/\\[]+):(\\d{1,5}):(.*)");

    public ProxySpec {
        if (type != Type.DIRECT) {
            if (host == null || host.isBlank()) {
                throw new IllegalArgumentException("у прокси не указан адрес");
            }
            if (port < 1 || port > 65_535) {
                throw new IllegalArgumentException("порт прокси вне 1–65535: " + port);
            }
        }
    }

    /**
     * @param text proxy as typed by the user; {@code null}, empty, {@code -}, {@code none} and {@code direct} mean no proxy
     * @throws IllegalArgumentException with a message for the user when the text cannot be a proxy
     */
    public static ProxySpec parse(String text) {
        if (text == null) {
            return DIRECT;
        }
        String s = text.strip();
        String lower = s.toLowerCase(Locale.ROOT);
        if (s.isEmpty() || s.equals("-") || lower.equals("none") || lower.equals("direct")) {
            return DIRECT;
        }
        Type type = Type.AUTO;
        int sep = s.indexOf("://");
        if (sep >= 0) {
            String scheme = lower.substring(0, sep);
            type = switch (scheme) {
                case "socks5", "socks5h", "socks" -> Type.SOCKS5;
                case "http" -> Type.HTTP;
                case "socks4", "socks4a" -> throw new IllegalArgumentException("SOCKS4 не поддерживается — нужен SOCKS5 или HTTP");
                case "https" -> throw new IllegalArgumentException(
                        "https:// не поддерживается — нужен http:// или socks5://");
                default -> throw new IllegalArgumentException("неизвестный тип прокси «" + scheme + "» — нужен socks5:// или http://");
            };
            s = s.substring(sep + 3);
            if (s.endsWith("/")) {
                s = s.substring(0, s.length() - 1);
            }
            return split(type, s, true);
        }
        return split(type, s, false);
    }

    /** {@code [user[:pass]@]host:port[:user:pass]}; {@code url} = percent-decode the login. */
    private static ProxySpec split(Type type, String s, boolean url) {
        java.util.regex.Matcher seller = SELLER.matcher(s);
        if (seller.matches()) {
            // host:port:user:pass — taken literally, the password may contain '@' and ':'
            String host = seller.group(1).startsWith("[") ? seller.group(1).substring(1, seller.group(1).length() - 1)
                    : seller.group(1);
            String creds = seller.group(3);
            int c = creds.indexOf(':');
            if (c < 0) {
                throw new IllegalArgumentException("ожидается адрес:порт:логин:пароль");
            }
            return build(type, host, seller.group(2), creds.substring(0, c), creds.substring(c + 1));
        }
        String user = null;
        String password = null;
        String hostPort = s;
        int at = s.lastIndexOf('@');
        if (at >= 0) {
            String info = s.substring(0, at);
            hostPort = s.substring(at + 1);
            int colon = info.indexOf(':');
            user = colon >= 0 ? info.substring(0, colon) : info;
            password = colon >= 0 ? info.substring(colon + 1) : null;
            if (url) {
                user = decode(user);
                password = password == null ? null : decode(password);
            }
        }
        String host;
        String rest;
        if (hostPort.startsWith("[")) {
            int close = hostPort.indexOf(']');
            if (close < 0) {
                throw new IllegalArgumentException("адрес прокси: нет закрывающей «]»");
            }
            host = hostPort.substring(1, close);
            rest = hostPort.substring(close + 1);
            if (!rest.startsWith(":")) {
                throw new IllegalArgumentException("не указан порт прокси: " + hostPort);
            }
            rest = rest.substring(1);
        } else {
            int colon = hostPort.indexOf(':');
            if (colon < 0) {
                throw new IllegalArgumentException("не указан порт: нужно адрес:порт");
            }
            host = hostPort.substring(0, colon);
            rest = hostPort.substring(colon + 1);
        }
        return build(type, host, rest, user, password);
    }

    private static ProxySpec build(Type type, String host, String portText, String user, String password) {
        int port;
        try {
            port = Integer.parseInt(portText.strip());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("порт прокси не число: «" + portText + "»");
        }
        if (user != null && user.isEmpty()) {
            user = null;
        }
        if (password != null && password.isEmpty()) {
            password = null;
        }
        return new ProxySpec(type, host.strip(), port, user, password);
    }

    private static String decode(String v) {
        try {
            return URLDecoder.decode(v.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return v; // a stray '%' in a plain password
        }
    }

    public boolean direct() {
        return type == Type.DIRECT;
    }

    public boolean hasCredentials() {
        return user != null;
    }

    /** The same proxy with a known type (after {@link Type#AUTO} was resolved). */
    public ProxySpec withType(Type t) {
        return new ProxySpec(t, host, port, user, password);
    }

    /** {@code host:port}, IPv6 in brackets. */
    public String address() {
        return host.indexOf(':') >= 0 ? "[" + host + "]:" + port : host + ":" + port;
    }

    /** For logs and the window: {@code SOCKS5 1.2.3.4:1080 (логин user)} — never the password. */
    public String describe() {
        if (direct()) {
            return "без прокси";
        }
        String kind = switch (type) {
            case SOCKS5 -> "SOCKS5";
            case HTTP -> "HTTP";
            default -> "прокси";
        };
        return kind + " " + address() + (user != null ? " (логин " + user + ")" : "");
    }

    /** Puts the password into {@link SecretMask}. */
    public void registerSecrets() {
        SecretMask.register(password);
    }

    /** Never print the password. */
    @Override
    public String toString() {
        return direct() ? "ProxySpec[DIRECT]" : "ProxySpec[" + type + " " + address()
                + (user != null ? ", user=" + user : "") + (password != null ? ", password=***" : "") + "]";
    }
}
