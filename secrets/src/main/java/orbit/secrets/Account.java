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
public record Account(String username, String password, String server, String dosid, String proxy,
                      java.util.Map<String, String> dosids) {

    /**
     * @param dosids website sessions of the other servers of this login ({@code ru5} → dosid; a dosid
     *               belongs to one server); {@code dosid} stays the one of {@code server}
     */
    public Account {
        dosids = dosids == null ? java.util.Map.of() : java.util.Map.copyOf(dosids);
    }

    public Account(String username, String password, String server, String dosid, String proxy) {
        this(username, password, server, dosid, proxy, null);
    }

    public Account withDosid(String newDosid) {
        return new Account(username, password, server, newDosid, proxy, dosids);
    }

    /** The default server of the login (the dosids of all servers are kept). */
    public Account withServer(String newServer) {
        java.util.Map<String, String> all = new java.util.TreeMap<>(dosids);
        if (dosid != null && server != null) {
            all.put(server, dosid);
        }
        String own = all.remove(newServer);
        return new Account(username, password, newServer, own, proxy, all);
    }

    /** The default server, {@code int1} when none is set. */
    public String home() {
        return server == null || server.isBlank() ? "int1" : server;
    }

    /** The saved website session of a server, or {@code null}. */
    public String dosidFor(String s) {
        return s.equals(home()) ? dosid : dosids.get(s);
    }

    /** The account with this server's session replaced ({@code null} = forget it). */
    public Account withDosidFor(String s, String d) {
        if (s.equals(home())) {
            return withDosid(d);
        }
        java.util.Map<String, String> all = new java.util.TreeMap<>(dosids);
        if (d == null) {
            all.remove(s);
        } else {
            all.put(s, d);
        }
        return new Account(username, password, server, dosid, proxy, all);
    }

    /** Every server with a saved session, the default one first. */
    public java.util.List<String> serversWithDosid() {
        java.util.List<String> out = new java.util.ArrayList<>();
        if (dosid != null) {
            out.add(home());
        }
        dosids.keySet().stream().filter(k -> !out.contains(k)).forEach(out::add);
        return out;
    }

    /** Puts every secret of this account into {@link SecretMask}. */
    public void registerSecrets() {
        SecretMask.register(password);
        SecretMask.register(dosid);
        dosids.values().forEach(SecretMask::register);
        if (proxy != null) {
            SecretMask.register(proxy); // the whole text, in case it does not parse
            try {
                proxySpec().registerSecrets();
            } catch (IllegalArgumentException ignored) {
                // reported where the proxy is used
            }
        }
    }

    /** The account's proxy; {@link ProxySpec#DIRECT} when none is set. */
    public ProxySpec proxySpec() {
        return ProxySpec.parse(proxy);
    }

    /** The account with another proxy ({@code null} = none); the text must parse. */
    public Account withProxy(String newProxy) {
        String p = newProxy == null || ProxySpec.parse(newProxy).direct() ? null : newProxy.strip();
        return new Account(username, password, server, dosid, p, dosids);
    }

    /** Never print secrets. */
    @Override
    public String toString() {
        return "Account[username=" + username + ", server=" + server + ", password=" + (password == null ? "-" : "***")
                + ", dosid=" + (dosid == null ? "-" : "***") + (dosids.isEmpty() ? "" : ", other servers=" + dosids.keySet())
                + ", proxy=" + proxyText() + "]";
    }

    private String proxyText() {
        if (proxy == null) {
            return "-";
        }
        try {
            return proxySpec().describe();
        } catch (IllegalArgumentException e) {
            return "*** (ошибка: " + e.getMessage() + ")";
        }
    }
}
