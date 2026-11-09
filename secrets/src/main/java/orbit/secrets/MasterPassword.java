package orbit.secrets;

import java.io.Console;
import java.util.Arrays;

/** Where the vault's master password comes from: the environment (headless runs) or the console. */
public final class MasterPassword {

    public static final String ENV = "ORBIT_MASTER_PASSWORD";

    private MasterPassword() {
    }

    /**
     * Returns the master password from {@value #ENV}, or asks for it in the console.
     *
     * @param confirm ask twice (when creating a new vault)
     */
    public static char[] obtain(boolean confirm) throws VaultException {
        String env = System.getenv(ENV);
        if (env != null && !env.isEmpty()) {
            SecretMask.register(env);
            return env.toCharArray();
        }
        Console console = System.console();
        if (console == null) {
            throw new VaultException("Нет консоли для ввода мастер-пароля. Запустите из терминала "
                    + "или задайте переменную " + ENV + ".");
        }
        char[] first = console.readPassword("Мастер-пароль хранилища: ");
        if (first == null || first.length == 0) {
            throw new VaultException("Мастер-пароль не введён.");
        }
        if (confirm) {
            char[] second = console.readPassword("Повторите мастер-пароль: ");
            boolean same = Arrays.equals(first, second);
            if (second != null) {
                Arrays.fill(second, '\0');
            }
            if (!same) {
                Arrays.fill(first, '\0');
                throw new VaultException("Пароли не совпадают.");
            }
        }
        SecretMask.register(new String(first));
        return first;
    }
}
