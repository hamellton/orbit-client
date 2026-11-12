package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HourlyReportTest {

    private static final ZoneId Z = ZoneOffset.UTC;

    private static long at(int h, int m) {
        return LocalDateTime.of(2026, 11, 12, h, m).atZone(Z).toInstant().toEpochMilli();
    }

    private static HourlyReport.Totals totals(double uri, int kills, int pall, int repairs, int deaths) {
        return new HourlyReport.Totals(uri, 0, 0, 0, kills, 0, repairs, deaths, Map.of("палладий", pall), Map.of());
    }

    @Test
    void oneLineAnHourOnTheClock() {
        AtomicReference<HourlyReport.Totals> now = new AtomicReference<>(totals(100, 0, 0, 0, 0));
        HourlyReport r = new HourlyReport(now::get, at(13, 20), Z);
        long t = at(13, 20);
        String line = null;
        while (line == null && t < at(15, 0)) {
            t += 1_000;
            if (t == at(13, 50)) {
                now.set(totals(1_300, 45, 540, 22, 1));
            }
            line = r.tick(t, true);
        }
        assertEquals(at(14, 0), t);
        assertEquals("Сводка 13:20–14:00 · в игре весь час · уридий +1 200 · NPC 45 · подобрано: палладий 540"
                + " · ремонтов 22 · смертей 1 ⚠", line);
        // an hour mostly out of the game
        for (t += 1_000; t <= at(15, 0); t += 1_000) {
            line = r.tick(t, t < at(14, 10));
            if (t < at(15, 0)) {
                assertNull(line);
            }
        }
        assertEquals("Сводка 14:00–15:00 · в игре 10 из 60 мин · ремонтов 0 · смертей 0", line);
    }

    @Test
    void aShortFirstHourJoinsTheNext() {
        HourlyReport r = new HourlyReport(() -> HourlyReport.Totals.NONE, at(13, 55), Z);
        assertNull(r.tick(at(14, 0), false));
        assertEquals("Сводка 13:55–15:00 · не в игре", r.tick(at(15, 0), false));
    }

    @Test
    void aCounterStartedAnewCountsWhole() {
        AtomicReference<HourlyReport.Totals> now = new AtomicReference<>(totals(5_000, 0, 0, 0, 0));
        HourlyReport r = new HourlyReport(now::get, at(13, 0), Z);
        now.set(totals(300, 0, 0, 0, 0)); // a new session: the rewards start from 0
        String line = null;
        for (long t = at(13, 0) + 1_000; t <= at(14, 0); t += 1_000) {
            line = r.tick(t, true);
        }
        assertEquals("Сводка 13:00–14:00 · в игре весь час · уридий +300 · ремонтов 0 · смертей 0", line);
    }
}
