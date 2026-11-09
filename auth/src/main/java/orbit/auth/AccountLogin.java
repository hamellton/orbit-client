package orbit.auth;

import orbit.secrets.Account;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Logs in an account from the {@link Vault}, sparing the password: the saved {@code dosid} is
 * tried first, and every successful login stores the current {@code dosid} back. Frequent
 * password logins make the site demand a captcha.
 *
 * <p>Repeated calls alternate: after a dosid attempt the next call uses the password (if any),
 * so a session the game keeps rejecting is replaced by a really fresh one.
 */
public final class AccountLogin {

    private static final Logger log = LoggerFactory.getLogger(AccountLogin.class);

    private final AuthClient client;
    private final Vault vault;
    private final String name;
    private boolean dosidTurn = true;

    public AccountLogin(AuthClient client, Vault vault, String name) throws AuthException {
        this.client = client;
        this.vault = vault;
        this.name = name;
        Account a = vault.get(name);
        if (a == null) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS,
                    "Аккаунт «" + name + "» не найден в хранилище " + vault.file() + ".");
        }
        if (a.password() == null && a.dosid() == null) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS,
                    "У аккаунта «" + name + "» нет ни пароля, ни dosid.");
        }
    }

    public Account account() {
        return vault.get(name);
    }

    /** Logs in and returns a game session; the dosid in the vault is updated on success. */
    public GameSession login() throws AuthException {
        Account a = account();
        boolean useDosid = a.dosid() != null && (dosidTurn || a.password() == null);
        dosidTurn = !useDosid;
        if (useDosid) {
            try {
                return remember(client.loginWithDosid(server(a), a.dosid()));
            } catch (AuthException e) {
                if (e.reason() != AuthException.Reason.SESSION_EXPIRED || a.password() == null) {
                    throw e;
                }
                log.info("Сохранённая сессия сайта устарела, вхожу по паролю.");
                forgetDosid();
            }
        }
        if (a.password() == null) {
            throw new AuthException(AuthException.Reason.SESSION_EXPIRED,
                    "Сессия сайта устарела, а пароля в хранилище нет. Обновите dosid: vault set-dosid " + name);
        }
        return remember(client.loginWithPassword(a.username(), a.password()));
    }

    private GameSession remember(GameSession s) {
        Account a = account();
        if (!s.dosid().equals(a.dosid())) {
            vault.put(name, a.withDosid(s.dosid()));
            save();
        }
        return s;
    }

    private void forgetDosid() {
        vault.put(name, account().withDosid(null));
        save();
    }

    private void save() {
        try {
            vault.save();
        } catch (VaultException e) {
            log.warn("Не удалось обновить хранилище: {}", e.getMessage());
        }
    }

    private static String server(Account a) {
        return a.server() == null || a.server().isBlank() ? "int1" : a.server();
    }
}
