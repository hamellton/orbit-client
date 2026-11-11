package orbit.game;

import orbit.auth.proxy.ProxyConnector;
import java.time.Duration;
import java.util.List;

/**
 * Tunables of a {@link GameConnection}.
 *
 * @param proxy           how the game sockets connect: {@link ProxyConnector#DIRECT} or the account's proxy
 * @param keepAlive       interval between {@code KeepAlive} packets (the client uses 15 s)
 * @param keepAliveJitter random extra delay added to each interval
 * @param policyRequest   ask for the Flash socket policy before each game connection, like the client
 * @param connectTimeout  TCP connect timeout
 * @param loginTimeout    time allowed from connect until the ship is initialised
 * @param backoff         waits between reconnect attempts; the last value repeats
 * @param refreshAfter    reconnect failures after which a fresh website login is done
 * @param silence         in game, the server saying nothing this long means the link is gone (a Wi-Fi drop
 *                        or a network switch leaves the socket open); the server talks every few seconds
 * @param stall           see {@link #STALL}
 */
public record ConnectionSettings(ProxyConnector proxy, Duration keepAlive, Duration keepAliveJitter, boolean policyRequest,
                                 Duration connectTimeout, Duration loginTimeout, List<Duration> backoff,
                                 int refreshAfter, Duration silence, Duration stall) {

    /**
     * The world stood still this long after the bot asked to fly — no ship moved, appeared, left or shot:
     * the server hangs while still answering (live 2026-11-11: five minutes at a portal, then it closed).
     */
    public static final Duration STALL = Duration.ofSeconds(90);

    public ConnectionSettings(ProxyConnector proxy, Duration keepAlive, Duration keepAliveJitter, boolean policyRequest,
                              Duration connectTimeout, Duration loginTimeout, List<Duration> backoff, int refreshAfter,
                              Duration silence) {
        this(proxy, keepAlive, keepAliveJitter, policyRequest, connectTimeout, loginTimeout, backoff, refreshAfter, silence,
                STALL);
    }

    public static ConnectionSettings defaults(ProxyConnector proxy) {
        return new ConnectionSettings(proxy, Duration.ofSeconds(15), Duration.ofMillis(150), true,
                Duration.ofSeconds(15), Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                        Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5)),
                3, Duration.ofSeconds(30));
    }

    Duration backoff(int attempt) {
        return backoff.get(Math.min(attempt, backoff.size() - 1));
    }

    /** Waits while the site shows its maintenance page: 1, 2, 4, 8, then every 10 minutes. */
    static final List<Duration> MAINTENANCE_WAITS = List.of(Duration.ofMinutes(1), Duration.ofMinutes(2),
            Duration.ofMinutes(4), Duration.ofMinutes(8), Duration.ofMinutes(10));

    /**
     * The wait before the next login during a maintenance, ±20% at random so that several accounts do not
     * knock at the same second.
     */
    static Duration maintenanceWait(int attempt, java.util.random.RandomGenerator random) {
        Duration base = MAINTENANCE_WAITS.get(Math.min(attempt, MAINTENANCE_WAITS.size() - 1));
        return Duration.ofMillis(Math.round(base.toMillis() * (0.8 + 0.4 * random.nextDouble())));
    }
}
