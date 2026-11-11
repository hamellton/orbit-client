package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicLong;
import orbit.protocol.gen.ShipInitializationCommand;
import org.junit.jupiter.api.Test;

class WorldFeedTest {

    private static final long SECOND = 1_000_000_000L;

    @Test
    void sessionClockStopsWithTheAccount() {
        AtomicLong clock = new AtomicLong(100 * SECOND);
        WorldFeed feed = new WorldFeed("бот, test", "test");
        feed.setClock(clock::get);
        ShipInitializationCommand ship = new ShipInitializationCommand();
        ship.userName = "pilot";
        ship.mapId = 1;
        feed.onPacket(ship, clock.get());
        assertEquals("нет (бот пока только держит связь)", feed.action());

        clock.addAndGet(90 * SECOND);
        assertEquals(90 * SECOND, feed.sessionNanos());

        feed.stopped();
        clock.addAndGet(600 * SECOND);
        assertEquals(90 * SECOND, feed.sessionNanos(), "frozen after the stop");
        assertEquals("Не в игре", feed.action());

        feed.newSession();
        assertEquals(0, feed.sessionNanos(), "a new start begins from zero");
    }
}
