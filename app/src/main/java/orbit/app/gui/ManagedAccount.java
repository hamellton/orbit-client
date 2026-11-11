package orbit.app.gui;

import orbit.app.WorldFeed;

/** An account the manager window can start and stop; its feed (tab, log) lives as long as the program. */
public interface ManagedAccount {

    enum Phase {
        STOPPED, RUNNING, STOPPING
    }

    String name();

    WorldFeed feed();

    Phase phase();

    /** Why the last start failed or the bot stopped by itself; {@code null} when nothing went wrong. */
    String problem();

    /**
     * Logs in after {@code delayMs} and runs the account's config or timetable; nothing when running.
     *
     * @return {@code null} when started, otherwise why not (for the user)
     */
    String start(long delayMs);

    /** Flies to a portal and logs out, in the background. */
    void stop();

    /** The config it runs now, or the account's config when stopped; {@code null} = none. */
    String config();

    /** Runs another config from now on (when running) and keeps it as the account's config. */
    void switchConfig(String name);
}
