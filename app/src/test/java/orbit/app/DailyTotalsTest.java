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

    /** The world's uridium of the stay and the session's own kills. */
    private static HourlyReport.Totals stay(double uri, int kills) {
        return new HourlyReport.Totals(uri, 0, 0, 0, kills, 0, 0, 0, Map.of(), Map.of());
    }

    @Test
    void aNewLoginInTheSameProgramDoesNotCountTheStaysRewardsAgain() throws Exception {
        // live 2026-11-24: the stay's rewards run on while the bot's counters start anew; today showed 399k uridium
        // for 118k earned
        Path file = Files.createTempDirectory("daily").resolve("today-3.json");
        DailyTotals d = new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 24));
        d.update(stay(250_000, 1_000), at(24, 1)); // the program ran since yesterday: all of it counted at first
        d.update(stay(280_000, 1_700), at(24, 13));
        d.update(stay(280_000, 0), at(24, 14)); // a new login: the bot's counters from nothing
        d.update(stay(369_000, 54), at(24, 18));
        assertEquals(369_000, d.today().uridium());
        assertEquals(1_754, d.today().kills());
    }

    @Test
    void theEarlierFileOfRunsIsReadWithoutTheDoubleCount() throws Exception {
        Path file = Files.createTempDirectory("daily").resolve("today-3.json");
        Files.writeString(file, """
                {"day":"2026-11-24","runs":{
                 "abc-2":{"from":{"uridium":250000,"kills":1000,"picked":{}},
                          "to":{"uridium":280000,"kills":1700,"picked":{}}},
                 "abc-3":{"from":{"uridium":0,"kills":0},
                          "to":{"uridium":369000,"kills":54,"picked":{"mimesis ai medic box":110}}}}}
                """);
        DailyTotals d = new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 24));
        assertEquals(119_000, d.today().uridium());
        assertEquals(754, d.today().kills());
        assertEquals(Map.of("ящик Mimesis: Medic", 110), d.today().picked());
        d.flush(); // written in the new form and read back the same
        assertEquals(119_000, new DailyTotals(file, ZoneOffset.UTC, LocalDate.of(2026, 11, 24)).today().uridium());
    }
}
