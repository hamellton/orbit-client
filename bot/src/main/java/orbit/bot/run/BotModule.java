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
    /**
     * The module's work is over and the account should log out (the galaxy gate's EBG: the runs done or no CPU left,
     * «улететь на базу и выйти из игры»): the reason, {@code null} while it works on.
     */
    default String stopRequest() {
        return null;
    }

    default void observe() {
    }

    /** Safety took over or the module is being replaced: drop targets and plans. */
    /** The HP share of the module's target ({@code -1} none or unknown). */
    default double targetHp() {
        return -1;
    }

    /** The repair is the module's own this tick (a galaxy gate: round the target); false — {@link Safety}'s way. */
    default boolean repairTick() {
        return false;
    }

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
     * True while the module rushes a target flagged {@code RUSH}: the runner leaves no repair, flight or keeping off
     * offenders to it till the target is dead (the user, 2026-11-17: «совсем не уходить, пока инвок жив»).
     */
    default boolean rushing() {
        return false;
    }

    /**
     * A quiet moment for a planned new login (DarkBot's refresh): nothing under way and nothing due within a few
     * minutes. By default when not {@link #busy()}.
     */
    default boolean calm() {
        return !busy();
    }

    /**
     * Nothing under way and nothing due for {@code ms}: a window for a trip away (a new cloaking CPU at the base). By
     * default {@link #calm()}.
     */
    default boolean freeFor(long ms) {
        return calm();
    }

    /** Each tick while the runner flies the ship to the module's map (between maps). */
    default void travelling() {
    }

    /** A new cloaking CPU when the fitted one is empty ({@link CloakCare}), {@code null} = not wanted. */
    default CloakPlan cloakPlan() {
        return null;
    }

    /** A new login of the game connection, for modules that need one (the gates: AstraBot logs in again after one). */
    default void useRefresh(Runnable refresh) {
    }

    /** The pet without lasers: taken off on the site at the base in a free window ({@link CloakCare}). */
    default boolean petLasersOff() {
        return false;
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
