package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SessionPlannerTest {

    static final LocalDateTime NOON = LocalDateTime.of(2026, 11, 10, 12, 0);

    @Test
    void fixedConfigWithoutSessionsRunsOpenEnded() {
        SessionPlanner p = new SessionPlanner(new AccountSettings.Entry(null, null), "CUBIKON", 1, new Random(1));
        SessionPlanner.Plan plan = p.next(NOON, n -> BotConfig.Session.ENDLESS);
        assertEquals("CUBIKON", plan.config());
        assertNull(plan.until());
    }

    @Test
    void workThenBreakWithinTheJitter() {
        SessionPlanner p = new SessionPlanner(new AccountSettings.Entry("CUBIKON", null), null, 1, new Random(3));
        SessionPlanner.Plan plan = p.next(NOON, n -> new BotConfig.Session(120, 20, 10));
        long work = Duration.between(NOON, plan.until()).toMinutes();
        assertTrue(work >= 110 && work <= 130, "work " + work);
        long rest = plan.breakAfter().toMinutes();
        assertTrue(rest >= 10 && rest <= 30, "break " + rest);
    }

    @Test
    void theTimetableEndsASessionEarly() {
        Schedule s = new Schedule(true, List.of(new Schedule.Entry(Set.of(), "08:00", "13:00", "CUBIKON")), 0);
        SessionPlanner p = new SessionPlanner(new AccountSettings.Entry(null, s), null, 1, new Random(1));
        SessionPlanner.Plan plan = p.next(NOON, n -> new BotConfig.Session(120, 20, 0));
        assertEquals(NOON.withHour(13), plan.until());
        assertEquals(Duration.ZERO, plan.breakAfter(), "the timetable decides what comes next");
    }

    @Test
    void offlineOutsideTheTimetable() {
        Schedule s = new Schedule(true, List.of(new Schedule.Entry(Set.of(), "14:00", "18:00", "CUBIKON")), 0);
        SessionPlanner p = new SessionPlanner(new AccountSettings.Entry("CUBIKON", s), null, 1, new Random(1));
        SessionPlanner.Plan plan = p.next(NOON, n -> BotConfig.Session.ENDLESS);
        assertFalse(plan.online());
        assertEquals(NOON.withHour(14), plan.until());
    }
}
