package orbit.app;

import java.util.function.Consumer;
import orbit.protocol.Packet;
import orbit.protocol.gen.MonthlyDeluxeClaimRequest;
import orbit.protocol.gen.MonthlyDeluxeDayInfoCommand;
import orbit.protocol.gen.MonthlyDeluxeInfoCommand;
import orbit.protocol.gen.MonthlyDeluxeTimeInfoCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's own calendar window, Monthly Deluxe: the server sends {@code MonthlyDeluxeInfoCommand} on every login and the
 * client opens the window by itself; its «Claim» button sends {@code MonthlyDeluxeClaimRequest} (no fields; the client
 * blocks a second click for 5 s). The button is there only with the pass bought ({@code timeToPassEnd > 0}), and the
 * day's reward only while {@code isClaimable} (client 2026-11-04, {@code MonthlyDeluxeUIData}, {@code MonthlyDeluxeMediator};
 * DmPlugin's CalendarBonus clicks the same button). The bot claims like that button, at most once a {@link #RETRY_MS}.
 */
public final class DeluxeCalendar {

    private static final Logger log = LoggerFactory.getLogger(DeluxeCalendar.class);

    /** A claim not confirmed by the server is sent again this much later. */
    static final long RETRY_MS = 60_000;
    /** No more claims than this in one day of the calendar (the server keeps saying claimable: something is off). */
    static final int MAX_TRIES = 3;

    private volatile Double timeToPassEnd;
    private volatile Boolean claimable;
    private volatile int today;
    private volatile int claimedDays;
    private long sentAt = Long.MIN_VALUE / 2;
    private int sentForDay = -1;
    private int tries;
    private boolean toldNoPass;
    private boolean waiting;

    /** Every packet from the server; only the Monthly Deluxe ones are read. */
    public void onPacket(Packet p) {
        switch (p) {
            case MonthlyDeluxeInfoCommand c -> {
                time(c.timeInfoCommand);
                day(c.dayInfoCommand);
            }
            case MonthlyDeluxeTimeInfoCommand c -> time(c);
            case MonthlyDeluxeDayInfoCommand c -> day(c);
            default -> {
            }
        }
    }

    private void time(MonthlyDeluxeTimeInfoCommand c) {
        if (c != null) {
            timeToPassEnd = c.timeToPassEnd;
        }
    }

    private synchronized void day(MonthlyDeluxeDayInfoCommand c) {
        if (c == null) {
            return;
        }
        today = c.today;
        claimedDays = c.daysClaimed == null ? 0 : c.daysClaimed.size();
        claimable = c.isClaimable;
        if (waiting && !c.isClaimable) {
            waiting = false;
            log.info("Календарь Monthly Deluxe: награда дня {} забрана (забрано дней: {})", today, claimedDays);
        }
    }

    /**
     * Claims the day's reward when it can be had.
     *
     * @param on   the account's settings let the bot claim calendars
     * @param send the game connection
     */
    public synchronized void tick(long nowMs, boolean on, Consumer<Packet> send) {
        Double pass = timeToPassEnd;
        Boolean can = claimable;
        if (!on || pass == null || can == null) {
            return; // nothing from the server yet
        }
        if (pass <= 0) {
            if (!toldNoPass) {
                toldNoPass = true;
                log.info("Календарь Monthly Deluxe: пропуска нет — забирать нечего");
            }
            return;
        }
        if (!can) {
            return;
        }
        if (sentForDay != today) {
            sentForDay = today;
            tries = 0;
        }
        if (tries >= MAX_TRIES || nowMs - sentAt < RETRY_MS) {
            return;
        }
        if (++tries == MAX_TRIES) {
            log.warn("Календарь Monthly Deluxe: сервер не подтвердил награду дня {} — последняя попытка за день", today);
        }
        sentAt = nowMs;
        waiting = true;
        send.accept(new MonthlyDeluxeClaimRequest());
        log.info("Календарь Monthly Deluxe: забираю награду дня {}", today);
    }
}
