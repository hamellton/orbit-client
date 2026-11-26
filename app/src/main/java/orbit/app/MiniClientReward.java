package orbit.app;

import java.util.List;
import java.util.function.Consumer;
import orbit.protocol.Packet;
import orbit.protocol.gen.MiniClientItemRewardModule;
import orbit.protocol.gen.MiniClientRewardClaimRequest;
import orbit.protocol.gen.MiniClientRewardDayInfoCommand;
import orbit.protocol.gen.MiniClientRewardInfoCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The downloadable client's own login reward («Награда клиента», window {@code miniclient_reward}): the server sends
 * {@code MiniClientRewardInfoCommand{isClaimable, daysClaimed, rewardList}} on login; the window's button sends
 * {@code MiniClientRewardClaimRequest} (no fields) and the server answers {@code MiniClientRewardDayInfoCommand{daysClaimed+1,
 * isClaimable=false}} (the user's own claim, st2 2026-11-26). The bot claims like that button, as {@link DeluxeCalendar}.
 */
public final class MiniClientReward {

    private static final Logger log = LoggerFactory.getLogger(MiniClientReward.class);

    /** A claim not confirmed by the server is sent again this much later. */
    static final long RETRY_MS = 60_000;
    /** No more claims than this for one day (the server keeps saying claimable: something is off). */
    static final int MAX_TRIES = 3;

    private volatile Boolean claimable;
    private volatile int daysClaimed;
    private volatile List<MiniClientItemRewardModule> rewards = List.of();
    private long sentAt = Long.MIN_VALUE / 2;
    private int sentForDay = -1;
    private int tries;
    private boolean waiting;

    /** Every packet from the server; only the client reward's are read. */
    public synchronized void onPacket(Packet p) {
        switch (p) {
            case MiniClientRewardInfoCommand c -> {
                daysClaimed = c.daysClaimed;
                rewards = c.rewardList == null ? List.of() : List.copyOf(c.rewardList);
                answer(c.isClaimable);
            }
            case MiniClientRewardDayInfoCommand c -> {
                daysClaimed = c.daysClaimed;
                answer(c.isClaimable);
            }
            default -> {
            }
        }
    }

    private void answer(boolean can) {
        claimable = can;
        if (waiting && !can) {
            waiting = false;
            log.info("Награда клиента: забрана — день {}{}", daysClaimed, reward(daysClaimed - 1));
        }
    }

    /** The reward of day {@code index} (from 0) as « (3 × resource_…)», or empty. */
    private String reward(int index) {
        List<MiniClientItemRewardModule> r = rewards;
        if (index < 0 || index >= r.size()) {
            return "";
        }
        return " (" + r.get(index).amount + " × " + r.get(index).lootId + ")";
    }

    /**
     * Claims the day's reward when it can be had.
     *
     * @param on   the account's settings let the bot claim calendars
     * @param send the game connection
     */
    public synchronized void tick(long nowMs, boolean on, Consumer<Packet> send) {
        Boolean can = claimable;
        if (!on || can == null || !can) {
            return;
        }
        int day = daysClaimed;
        if (sentForDay != day) {
            sentForDay = day;
            tries = 0;
        }
        if (tries >= MAX_TRIES || nowMs - sentAt < RETRY_MS) {
            return;
        }
        if (++tries == MAX_TRIES) {
            log.warn("Награда клиента: сервер не подтвердил — последняя попытка на сегодня");
        }
        sentAt = nowMs;
        waiting = true;
        send.accept(new MiniClientRewardClaimRequest());
        log.info("Награда клиента: забираю награду дня {}{}", day + 1, reward(day));
    }
}
