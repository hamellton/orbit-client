package orbit.auth;

import orbit.secrets.Account;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs in an account from the {@link Vault} on one of its servers, sparing the password: the saved
 * session ({@code dosid}) of that server first, then the session of any other server of the login
 * followed by the site's server switch, the password last (frequent password logins bring a captcha).
 * Every successful login stores the current sessions back.
 */
public final class AccountLogin {

    private static final Logger log = LoggerFactory.getLogger(AccountLogin.class);

    private final AuthClient client;
    private final Vault vault;
    private final String name;
    private final String server;

    public AccountLogin(AuthClient client, Vault vault, String name) throws AuthException {
        this(client, vault, name, null);
    }

    /** @param server the server to play on ({@code ru5}); {@code null} = the account's default */
    public AccountLogin(AuthClient client, Vault vault, String name, String server) throws AuthException {
        this.client = client;
        this.vault = vault;
        this.name = name;
        Account a = vault.get(name);
        if (a == null) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS,
                    "Аккаунт «" + name + "» не найден в хранилище " + vault.file() + ".");
        }
        this.server = server == null || server.isBlank() ? a.home() : server;
        if (a.password() == null && a.serversWithDosid().isEmpty()) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS,
                    "У аккаунта «" + name + "» нет ни пароля, ни dosid.");
        }
    }

    public Account account() {
        return vault.get(name);
    }

    /** The server this login plays on. */
    public String server() {
        return server;
    }

    /** Logs in and returns a game session on {@link #server()}; the sessions in the vault are updated. */
    public GameSession login() throws AuthException {
        Account a = account();
        String own = a.dosidFor(server);
        if (own != null) {
            try {
                return remember(server, client.loginWithDosid(server, own));
            } catch (AuthException e) {
                if (e.reason() != AuthException.Reason.SESSION_EXPIRED) {
                    throw e;
                }
                log.info("Сохранённая сессия сайта для {} устарела", server);
                remember(server, null);
            }
        }
        for (String other : account().serversWithDosid()) {
            if (other.equals(server)) {
                continue;
            }
            GameSession there;
            try {
                there = remember(other, client.loginWithDosid(other, account().dosidFor(other)));
            } catch (AuthException e) {
                if (e.reason() != AuthException.Reason.SESSION_EXPIRED) {
                    throw e;
                }
                remember(other, null);
                continue;
            }
            log.info("Вошёл на {} — перехожу на {}", other, server);
            return remember(server, client.switchTo(there, server));
        }
        if (a.password() == null) {
            throw new AuthException(AuthException.Reason.SESSION_EXPIRED, "Сессия сайта устарела, а пароля в "
                    + "хранилище нет. Обновите dosid: vault set-dosid " + name + " --server " + server);
        }
        // a password login lands on the server played last
        GameSession s = client.loginWithPassword(a.username(), a.password());
        if (s.host().equals(client.originOf(server))) {
            return remember(server, s);
        }
        String host = java.net.URI.create(s.host()).getHost();
        String landed = host == null || !host.contains(".") ? host : host.substring(0, host.indexOf('.'));
        remember(landed, s);
        log.info("Сайт открыл {}, нужен {} — перехожу", landed, server);
        return remember(server, client.switchTo(s, server));
    }

    /** Stores the session of a server ({@code null} = forget it). */
    private GameSession remember(String srv, GameSession s) {
        Account a = account();
        String d = s == null ? null : s.dosid();
        if (!java.util.Objects.equals(d, a.dosidFor(srv))) {
            vault.put(name, a.withDosidFor(srv, d));
            save();
        }
        return s;
    }

    private void save() {
        try {
            vault.save();
        } catch (VaultException e) {
            log.warn("Не удалось обновить хранилище: {}", e.getMessage());
        }
    }
}
