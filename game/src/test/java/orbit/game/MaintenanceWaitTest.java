package orbit.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.Test;

class MaintenanceWaitTest {

    @Test
    void growsToTenMinutesWithScatter() {
        Random random = new Random(7);
        long[] minutes = {1, 2, 4, 8, 10, 10, 10};
        for (int attempt = 0; attempt < minutes.length; attempt++) {
            Duration w = ConnectionSettings.maintenanceWait(attempt, random);
            long base = Duration.ofMinutes(minutes[attempt]).toMillis();
            assertTrue(w.toMillis() >= base * 0.8 && w.toMillis() <= base * 1.2, attempt + ": " + w);
        }
        // two accounts do not get the same wait
        assertTrue(ConnectionSettings.maintenanceWait(4, new Random(1)) != ConnectionSettings.maintenanceWait(4, new Random(2)));
    }

    @Test
    void waitsAreSpokenForPeople() {
        assertEquals("15 с", GameConnection.spoken(Duration.ofSeconds(15)));
        assertEquals("1 мин", GameConnection.spoken(Duration.ofSeconds(60)));
        assertEquals("4 мин 12 с", GameConnection.spoken(Duration.ofSeconds(252)));
    }
}
