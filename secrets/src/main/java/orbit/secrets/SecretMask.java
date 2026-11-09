package orbit.secrets;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Process-wide registry of secret values (passwords, dosid, sessionID, proxy passwords) that
 * must never reach a log. Code that obtains a secret registers it; the logging layer
 * ({@link MaskingConverter}) replaces every registered value with {@code ***}.
 *
 * <p>Masking is a safety net: code still must not log secrets on purpose.
 */
public final class SecretMask {

    /** Shorter values would mask ordinary words and numbers. */
    static final int MIN_LENGTH = 4;
    static final String MASK = "***";

    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    /** Longest first, so a secret containing another one is masked whole. */
    private static volatile List<String> ordered = List.of();

    private SecretMask() {
    }

    /** Registers a secret; {@code null}, blank and very short values are ignored. */
    public static void register(String secret) {
        if (secret == null || secret.strip().length() < MIN_LENGTH) {
            return;
        }
        if (SECRETS.add(secret)) {
            ordered = SECRETS.stream().sorted(Comparator.comparingInt(String::length).reversed()).toList();
        }
    }

    /** Returns {@code text} with every registered secret replaced by {@code ***}. */
    public static String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;
        for (String s : ordered) {
            if (out.contains(s)) {
                out = out.replace(s, MASK);
            }
        }
        return out;
    }

    /** For tests. */
    static void clear() {
        SECRETS.clear();
        ordered = List.of();
    }
}
