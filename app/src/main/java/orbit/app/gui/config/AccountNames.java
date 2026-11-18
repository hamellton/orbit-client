package orbit.app.gui.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import orbit.config.AccountSettings;

/**
 * The program's accounts ({@code name@server}) for the config window's account pickers (LoW's helpers): the manager
 * window gives its list (the vault); without it the accounts with settings in {@code local/accounts.json}.
 */
public final class AccountNames {

    private static volatile Supplier<List<String>> source;

    private AccountNames() {
    }

    public static void source(Supplier<List<String>> s) {
        source = s;
    }

    public static List<String> all() {
        Supplier<List<String>> s = source;
        if (s != null) {
            return new ArrayList<>(s.get());
        }
        try {
            return new ArrayList<>(AccountSettings.load(AccountSettings.DEFAULT_FILE).accounts().keySet());
        } catch (java.io.IOException | RuntimeException e) {
            return new ArrayList<>();
        }
    }
}
