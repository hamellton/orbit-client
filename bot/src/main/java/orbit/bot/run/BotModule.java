package orbit.bot.run;

/**
 * A module the bot runs (kill, Cubikon camp, gates…). {@link BotRunner} calls {@link #tick} about ten
 * times a second while the hero is alive, on the module's map and not busy with safety (repair,
 * escape); the module owns the navigator meanwhile.
 */
public interface BotModule {

    /** Short status for the window, in Russian. */
    String status();

    /** The map the module works on; the runner flies there first. 0 = wherever the hero is. */
    int workingMap();

    void tick();

    /**
     * Called every tick while the hero is alive, also when safety steers or the bot travels: watch the
     * world (e.g. remember a Cubikon dying while the ship repairs at a portal).
     */
    default void observe() {
    }

    /** Safety took over or the module is being replaced: drop targets and plans. */
    default void pause() {
    }

    /**
     * Uridium the jump CPU may spend today on the way to {@link #workingMap} when no voucher is left;
     * {@code -1} = no CPU jumps, portals only.
     */
    default int jumpUridiumPerDay() {
        return -1;
    }

    /** True when it is a bad moment to leave (e.g. a boss almost dead). */
    default boolean busy() {
        return false;
    }
}
