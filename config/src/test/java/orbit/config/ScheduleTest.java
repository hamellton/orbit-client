package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ScheduleTest {

    // 2026-11-09 is a Monday
    static LocalDateTime at(int day, int h, int m) {
        return LocalDateTime.of(2026, 11, day, h, m);
    }

    @Test
    void firstMatchingWindowWins() {
        Schedule s = new Schedule(true, List.of(
                new Schedule.Entry(Set.of(), "12:00", "13:00", null),
                new Schedule.Entry(Set.of(), "08:00", "18:00", "CUBIKON"),
                new Schedule.Entry(Set.of(DayOfWeek.SATURDAY), "10:00", "20:00", "ABG")), 0);
        assertEquals(new Schedule.Decision("CUBIKON", at(9, 18, 0)), s.at(at(9, 9, 0), 1));
        Schedule.Decision lunch = s.at(at(9, 12, 30), 1);
        assertFalse(lunch.online());
        assertEquals(at(9, 13, 0), lunch.until());
        // after the work window: offline until tomorrow 08:00
        Schedule.Decision night = s.at(at(9, 20, 0), 1);
        assertNull(night.config());
        assertEquals(at(10, 8, 0), night.until());
        // Saturday 19:00: the every-day window ended at 18:00, the Saturday one still runs
        assertEquals("ABG", s.at(at(14, 19, 0), 1).config());
    }

    @Test
    void windowsCrossMidnight() {
        Schedule s = new Schedule(true, List.of(new Schedule.Entry(Set.of(DayOfWeek.MONDAY), "22:00", "02:00", "BL")), 0);
        assertEquals(new Schedule.Decision("BL", at(10, 2, 0)), s.at(at(10, 1, 0), 1)); // Tuesday 01:00
        assertFalse(s.at(at(11, 1, 0), 1).online()); // Wednesday 01:00: Tuesday is not a start day
        assertEquals(at(16, 22, 0), s.at(at(10, 3, 0), 1).until()); // next Monday
    }

    @Test
    void sameStartAndEndIsTheWholeDay() {
        Schedule s = new Schedule(true, List.of(new Schedule.Entry(Set.of(), "00:00", "00:00", "X")), 0);
        assertTrue(s.at(at(9, 23, 59), 1).online());
    }

    @Test
    void jitterIsStableWithinADayAndBounded() {
        Schedule s = new Schedule(true, List.of(new Schedule.Entry(Set.of(), "08:00", "18:00", "X")), 15);
        LocalDateTime end1 = s.at(at(9, 12, 0), 7).until();
        assertEquals(end1, s.at(at(9, 15, 0), 7).until());
        assertTrue(Duration.between(at(9, 18, 0), end1).abs().toMinutes() <= 15);
        long distinct = java.util.stream.IntStream.rangeClosed(9, 20)
                .mapToObj(d -> s.at(at(d, 12, 0), 7).until().toLocalTime()).distinct().count();
        assertTrue(distinct > 5, "ends move from day to day");
        assertTrue(!s.at(at(9, 12, 0), 7).until().equals(s.at(at(9, 12, 0), 8).until()), "accounts differ");
    }

    @Test
    void badTimesAreRefused() {
        assertThrows(RuntimeException.class, () -> new Schedule.Entry(Set.of(), "25:00", "10:00", "X"));
    }

    @Test
    void accountWithoutScheduleRunsItsConfig() {
        AccountSettings.Entry e = new AccountSettings.Entry("ABG", null);
        assertEquals(new Schedule.Decision("ABG", null), e.decide(at(9, 3, 0), 1));
    }
}
