package orbit.bot.mod;

/** The package's Mimesis tables for the module's tests in {@code orbit.bot.run}. */
public final class MimesisModuleTestAccess {

    private MimesisModuleTestAccess() {
    }

    public static String plain(String name) {
        return MimesisModule.plain(name);
    }

    public static int priority(String name, boolean escortMap) {
        return MimesisModule.tableRule(name, escortMap).priority();
    }

    public static boolean kills(String name) {
        return MimesisModule.tableRule(name, true).kill();
    }
}
