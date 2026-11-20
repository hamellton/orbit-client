package orbit.world;

/**
 * Numbers as the logs and the window write amounts: groups of three split by a space, {@code 20 226 544/75 000 000}, as
 * long numbers are hard to read. Ids, coordinates and ports stay as they are.
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
