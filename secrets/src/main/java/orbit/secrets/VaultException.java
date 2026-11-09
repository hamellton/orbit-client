package orbit.secrets;

/** Vault failure with a message meant for the user (Russian). */
public class VaultException extends Exception {

    private static final long serialVersionUID = 1L;

    public VaultException(String message) {
        super(message);
    }

    public VaultException(String message, Throwable cause) {
        super(message, cause);
    }
}
