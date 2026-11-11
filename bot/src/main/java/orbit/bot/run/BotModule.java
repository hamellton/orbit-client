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

    /** The module's own way with a full hold ({@code null}: the config's «Полный трюм»). */
    default CargoPlan cargoPlan(orbit.config.BotConfig c) {
        return null;
    }

    /**
     * Another ship wanted now: the website hangar to switch to ({@code 0} = none) — the bot logs out, the app
     * switches the hangar and logs in again. {@code active} is the hangar in use ({@code 0} not known yet).
     */
    default int hangarWanted(int active, orbit.world.WorldSnapshot w) {
        return 0;
    }

    /** The same right after the ship was destroyed (the kill screen): switch instead of reviving. */
    default int hangarOnDeath(int active, orbit.world.WorldSnapshot w) {
        return 0;
    }

    /** Log out at a portal for a hangar switch (true), or where the ship is (false). */
    default boolean hangarAtPortal() {
        return true;
    }

    /** True when it is a bad moment to leave (e.g. a boss almost dead). */
    default boolean busy() {
        return false;
    }
}
