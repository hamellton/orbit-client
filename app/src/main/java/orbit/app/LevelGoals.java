package orbit.app;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** The leveling advisor's current goal per account, short, for the account list's «Цель» column. */
public final class LevelGoals {

    private static final Map<String, String> GOALS = new ConcurrentHashMap<>();

    private LevelGoals() {
    }

    static void set(String account, String text) {
        if (account != null && text != null) {
            int cut = text.indexOf(" | ");
            String s = cut >= 0 ? text.substring(0, cut) : text;
            GOALS.put(account, s.length() > 140 ? s.substring(0, 140) + "…" : s);
        }
    }

    /** The goal the advisor named last ({@code null} for an account not on the leveling mode). */
    public static String of(String account) {
        return account == null ? null : GOALS.get(account);
    }
}
