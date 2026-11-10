package orbit.secrets;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Credentials of one game account as stored in the {@link Vault}.
 *
 * @param username website login
 * @param password website password (secret), may be {@code null} when only a dosid is used
 * @param server   account server code, e.g. {@code int1} for GE1
 * @param dosid    last known website session (secret), reused to avoid password logins; may be {@code null}
 * @param proxy    proxy URI for this account, e.g. {@code socks5://user:pass@host:1080}; may be {@code null}
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public record Account(String username, String password, String server, String dosid, String proxy) {

    public Account withDosid(String newDosid) {
        return new Account(username, password, server, newDosid, proxy);
    }

    /** Another server of the same login; a dosid belongs to one server, so it is dropped. */
    public Account withServer(String newServer) {
        return new Account(username, password, newServer, null, proxy);
    }

    /** Puts every secret of this account into {@link SecretMask}. */
    public void registerSecrets() {
        SecretMask.register(password);
        SecretMask.register(dosid);
        if (proxy != null) {
            int at = proxy.lastIndexOf('@');
            int scheme = proxy.indexOf("://");
            if (at > 0 && scheme > 0) {
                String userInfo = proxy.substring(scheme + 3, at);
                int colon = userInfo.indexOf(':');
                SecretMask.register(colon >= 0 ? userInfo.substring(colon + 1) : userInfo);
            }
        }
    }

    /** Never print secrets. */
    @Override
    public String toString() {
        return "Account[username=" + username + ", server=" + server + ", password=" + (password == null ? "-" : "***")
                + ", dosid=" + (dosid == null ? "-" : "***") + ", proxy=" + (proxy == null ? "-" : "***") + "]";
    }
}
