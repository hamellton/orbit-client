package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DailyTotalsTest {

    private static HourlyReport.Totals t(double uri, int kills, double traces) {
        return new HourlyReport.Totals(uri, 0, 0, 0, kills, 0, 0, 0, Map.of(),
                traces > 0 ? Map.of("resource_blacklight_trace", traces) : Map.of());
    }

    private static long at(int day, int h) {
        return LocalDate.of(2026, 11, day).atTime(h, 0).toInstant(ZoneOffset.UTC).toEpochMilli();
    }

    @Test
    void runsOfADayAddUpAndADayStartsAnew() throws Exception {
        // the session summary started from nothing at each start of the bot
        Path file = Files.createTempDirectory("daily").resolve("today-3.json");
        DailyTotals first = new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 13));
        first.update(t(100, 3, 7), at(13, 10));
        first.update(t(300, 5, 16), at(13, 11));
        first.flush();
        // the program again: the earlier run read from the file, a new one from nothing
        DailyTotals second = new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 13));
        second.update(t(50, 2, 6), at(13, 12));
        assertEquals(350, second.today().uridium());
        assertEquals(7, second.today().kills());
        assertEquals(22, second.today().items().get("resource_blacklight_trace"));
        // a new login in the same program: counters from nothing again
        second.update(t(10, 1, 0), at(13, 13));
        assertEquals(360, second.today().uridium());
        // midnight: only what came since the last count before it
        second.update(t(40, 4, 0), at(14, 1));
        second.update(t(70, 6, 0), at(14, 2));
        assertEquals(60, second.today().uridium());
        assertEquals(5, second.today().kills());
    }

    private static HourlyReport.Totals gates(int kills, int done) {
        return new HourlyReport.Totals(0, 0, 0, 0, kills, 0, 0, 0, Map.of(), Map.of(), 0, 0, Map.of(), Map.of(),
                Map.of("α|done", done));
    }

    @Test
    void theGatesDoneAreSummedOverRunsAndKeptOnDisk() throws Exception {
        Path file = Files.createTempDirectory("daily").resolve("today-3.json");
        DailyTotals first = new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 21));
        first.update(gates(10, 2), at(21, 10));
        first.flush();
        Thread.sleep(5); // another program: its runs named by another start time
        DailyTotals second = new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 21));
        second.update(gates(3, 1), at(21, 11));
        assertEquals(Map.of("α|done", 3), second.today().gates());
    }
}
