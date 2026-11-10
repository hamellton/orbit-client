package orbit.auth.proxy;

import java.io.IOException;

/** A proxy did not give a connection; the message is for the user (Russian). */
public class ProxyException extends IOException {

    private static final long serialVersionUID = 1L;

    public enum Kind {
        /** The proxy itself cannot be reached (down, wrong address or port, firewall). */
        UNREACHABLE,
        /** The proxy wants a login, or rejected the one given — retrying will not help. */
        AUTH,
        /** The proxy works but could not or would not connect to the target. */
        TARGET,
        /** The other side does not speak SOCKS5 or HTTP as expected. */
        PROTOCOL
    }

    private final Kind kind;

    public ProxyException(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public ProxyException(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public Kind kind() {
        return kind;
    }
}
