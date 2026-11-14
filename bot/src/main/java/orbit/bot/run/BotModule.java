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
     * The user's pause is over: the plan from before it goes on at once; only the movement is asked again (the ship
     * may have been flown elsewhere by clicks meanwhile).
     */
    default void resumed() {
    }

    /**
     * Uridium the jump CPU may spend today on the way to {@link #workingMap} when no voucher is left;
     * {@code -1} = no CPU jumps, portals only.
     */
    default int jumpUridiumPerDay() {
        return -1;
    }

    /** Circles {x, y, r} the module wants flown round now, besides the offenders' (e.g. a camped spawn while waiting). */
    default java.util.List<double[]> avoidCircles() {
        return java.util.List.of();
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

    /** A pet mode ({@code PetGearTypeModule} number) the module wants now over the config's, -1 for none. */
    default int petGear() {
        return -1;
    }

    /** Whether the pet should be out now: {@code null} = as the config says, false = keep it in. */
    default Boolean petOut() {
        return null;
    }

    /** The NPC the module fights now, 0 for none. */
    default int fightTarget() {
        return 0;
    }

    /**
     * True while the module's fight must not be left for players' circles (the behemoth among a train, CARNAGE's
     * BLCycle: their attacks are ignored); the runner's keeping off offenders waits.
     */
    default boolean holdsGround() {
        return false;
    }

    /**
     * A quiet moment for a planned new login (DarkBot's refresh): nothing under way and nothing due within a few
     * minutes. By default when not {@link #busy()}.
     */
    default boolean calm() {
        return !busy();
    }

    /** True when it is a bad moment to leave (e.g. a boss almost dead). */
    default boolean busy() {
        return false;
    }

    /**
     * An NPC number the pet's enemy locator should look for now instead of the config's mode (a quest's NPC), 0 for
     * none: the config's pet mode goes on.
     */
    default int petLocator() {
        return 0;
    }
}
