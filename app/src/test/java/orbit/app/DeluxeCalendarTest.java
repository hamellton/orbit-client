package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.ArrayList;
import java.util.List;
import orbit.protocol.Packet;
import orbit.protocol.gen.MonthlyDeluxeClaimRequest;
import orbit.protocol.gen.MonthlyDeluxeDayInfoCommand;
import orbit.protocol.gen.MonthlyDeluxeInfoCommand;
import orbit.protocol.gen.MonthlyDeluxeTimeInfoCommand;
import org.junit.jupiter.api.Test;

class DeluxeCalendarTest {

    private static MonthlyDeluxeInfoCommand info(double passEnd, boolean claimable, int today) {
        MonthlyDeluxeInfoCommand c = new MonthlyDeluxeInfoCommand();
        c.timeInfoCommand = new MonthlyDeluxeTimeInfoCommand();
        c.timeInfoCommand.timeToPassEnd = passEnd;
        c.dayInfoCommand = day(claimable, today);
        return c;
    }

    private static MonthlyDeluxeDayInfoCommand day(boolean claimable, int today) {
        MonthlyDeluxeDayInfoCommand d = new MonthlyDeluxeDayInfoCommand();
        d.isClaimable = claimable;
        d.today = (short) today;
        return d;
    }

    @Test
    void claimsOnlyWithThePassAndWhileClaimable() {
        List<Packet> sent = new ArrayList<>();
        DeluxeCalendar noPass = new DeluxeCalendar();
        noPass.onPacket(info(-1.79e9, true, 5));
        noPass.tick(0, true, sent::add);
        assertEquals(0, sent.size());

        DeluxeCalendar cal = new DeluxeCalendar();
        cal.tick(0, true, sent::add); // nothing from the server yet
        cal.onPacket(info(86_400, true, 5));
        cal.tick(0, false, sent::add); // turned off in the account's settings
        assertEquals(0, sent.size());
        cal.tick(1_000, true, sent::add);
        assertEquals(1, sent.size());
        assertInstanceOf(MonthlyDeluxeClaimRequest.class, sent.get(0));
        cal.tick(2_000, true, sent::add); // not again at once
        assertEquals(1, sent.size());

        cal.onPacket(day(false, 5)); // the server took it
        cal.tick(DeluxeCalendar.RETRY_MS * 2, true, sent::add);
        assertEquals(1, sent.size());

        cal.onPacket(day(true, 6)); // the next day
        cal.tick(DeluxeCalendar.RETRY_MS * 3, true, sent::add);
        assertEquals(2, sent.size());
    }

    @Test
    void givesUpAfterAFewUnconfirmedTriesADay() {
        List<Packet> sent = new ArrayList<>();
        DeluxeCalendar cal = new DeluxeCalendar();
        cal.onPacket(info(86_400, true, 7));
        for (int i = 0; i < 10; i++) {
            cal.tick(i * DeluxeCalendar.RETRY_MS, true, sent::add);
        }
        assertEquals(DeluxeCalendar.MAX_TRIES, sent.size());
    }
}
