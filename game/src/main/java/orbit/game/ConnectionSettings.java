package orbit.game;

import java.net.Proxy;
import java.time.Duration;
import java.util.List;

/**
 * Tunables of a {@link GameConnection}.
 *
 * @param proxy           proxy for the game socket ({@link Proxy#NO_PROXY}, SOCKS or HTTP CONNECT)
 * @param keepAlive       interval between {@code KeepAlive} packets (the client uses 15 s)
 * @param keepAliveJitter random extra delay added to each interval
 * @param policyRequest   ask for the Flash socket policy before each game connection, like the client
 * @param connectTimeout  TCP connect timeout
 * @param loginTimeout    time allowed from connect until the ship is initialised
 * @param backoff         waits between reconnect attempts; the last value repeats
 * @param refreshAfter    reconnect failures after which a fresh website login is done
 */
public record ConnectionSettings(Proxy proxy, Duration keepAlive, Duration keepAliveJitter, boolean policyRequest,
                                 Duration connectTimeout, Duration loginTimeout, List<Duration> backoff,
                                 int refreshAfter) {

    public static ConnectionSettings defaults(Proxy proxy) {
        return new ConnectionSettings(proxy, Duration.ofSeconds(15), Duration.ofMillis(150), true,
                Duration.ofSeconds(15), Duration.ofSeconds(30),
                List.of(Duration.ofSeconds(5), Duration.ofSeconds(15), Duration.ofSeconds(30),
                        Duration.ofMinutes(1), Duration.ofMinutes(2), Duration.ofMinutes(5)),
                3);
    }

    Duration backoff(int attempt) {
        return backoff.get(Math.min(attempt, backoff.size() - 1));
    }
}
