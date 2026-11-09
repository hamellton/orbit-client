package orbit.net;

/** Thrown when bytes on the wire do not match the expected protocol format. */
public class ProtocolException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolException(String message) {
        super(message);
    }
}
