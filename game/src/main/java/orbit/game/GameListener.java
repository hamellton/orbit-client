package orbit.game;

import orbit.protocol.Packet;

/** Receives everything a {@link GameConnection} sees. Called from the connection's reader thread. */
public interface GameListener {

    /** Connection lifecycle as shown to the user. */
    enum State {
        /** Logging in on the website. */
        AUTHENTICATING,
        /** TCP connect + version check + login. */
        CONNECTING,
        /** Logged in on a map. */
        ONLINE,
        /** The ship is destroyed: the kill screen is shown until a repair is chosen. */
        DEAD,
        /** Changing server after the game asked for it. */
        CHANGING_SERVER,
        /** Lost connection, waiting before the next attempt. */
        RECONNECTING,
        /** Stopped for good (outdated protocol, wrong password, closed by user). */
        STOPPED
    }

    /** Every decoded server packet. */
    void onPacket(Packet packet);

    /** Every packet written to the game server (called from the sending thread). */
    default void onSent(Packet packet) {
    }

    /**
     * @param reason human-readable explanation in Russian (never contains secrets)
     */
    default void onState(State state, String reason) {
    }
}
