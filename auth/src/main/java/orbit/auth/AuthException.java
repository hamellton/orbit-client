package orbit.auth;

/** Login failure with a message meant for the user (Russian). */
public class AuthException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Why the login failed; lets callers decide whether retrying makes sense. */
    public enum Reason {
        /** Wrong username or password — retrying will not help. */
        INVALID_CREDENTIALS,
        /** The site wants a captcha solved — log in with a dosid from the browser instead. */
        CAPTCHA_REQUIRED,
        /** The saved dosid is no longer valid — log in again. */
        SESSION_EXPIRED,
        /** Account banned or locked. */
        ACCOUNT_BLOCKED,
        /** Maintenance, network trouble or an unexpected page — retry later. */
        UNAVAILABLE,
        /** The site layout changed and could not be understood. */
        UNEXPECTED_PAGE
    }

    private final Reason reason;

    public AuthException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public AuthException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }

    /** Whether trying again later can succeed without user action. */
    public boolean retryable() {
        return reason == Reason.UNAVAILABLE;
    }
}
