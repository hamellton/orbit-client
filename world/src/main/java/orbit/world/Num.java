package orbit.world;

/**
 * Numbers as the logs and the window write amounts: groups of three split by a space, «20 226 544/75 000 000» (the
 * user, 2026-11-20: «20226544/75000000 сложно разбираться»). Ids, coordinates and ports stay as they are.
 */
public final class Num {

    private Num() {
    }

    /** 20 226 544; a fraction rounded. */
    public static String group(double v) {
        return String.format(java.util.Locale.ROOT, "%,d", Math.round(v)).replace(',', ' ');
    }

    /** «20 226 544/75 000 000». */
    public static String of(double current, double target) {
        return group(current) + "/" + group(target);
    }
}
