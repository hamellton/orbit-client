package orbit.bot.mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The NPCs the accounts of this program are fighting (docs/PLAN.md «Чужие цели», the user's choice 2026-11-26): one account's
 * target is not taken by another of them — before the server greys it for the second one (its {@code LSH} comes after the
 * first shots) both shot at it, the second for nothing (the five starters: 8–21 such targets dropped a day each). A claim
 * is renewed every fight tick and lapses {@link #HOLD_NS} after the last one (an account stopped or gone off). The account
 * is the bot thread's log context ({@code account}).
 */
public final class TargetClaims {

    /** A claim not renewed this long is over. */
    static final long HOLD_NS = 15_000_000_000L;

    private record Claim(String account, long nanos) {
    }

    private static final Map<Long, Claim> CLAIMS = new ConcurrentHashMap<>();

    private TargetClaims() {
    }

    private static long key(int mapId, int npcId) {
        return ((long) mapId << 32) | (npcId & 0xffffffffL);
    }

    private static String account() {
        return org.slf4j.MDC.get("account");
    }

    /** This account fights the NPC (renewed every tick of the fight). */
    public static void claim(int mapId, int npcId) {
        String a = account();
        if (a != null && npcId != 0) {
            CLAIMS.put(key(mapId, npcId), new Claim(a, System.nanoTime()));
        }
    }

    /** This account's fight with the NPC is over. */
    public static void release(int mapId, int npcId) {
        String a = account();
        if (a != null && npcId != 0) {
            CLAIMS.computeIfPresent(key(mapId, npcId), (k, c) -> c.account().equals(a) ? null : c);
        }
    }

    /** Another account of this program fights the NPC now. */
    public static boolean claimedByOther(int mapId, int npcId) {
        Claim c = CLAIMS.get(key(mapId, npcId));
        if (c == null) {
            return false;
        }
        if (System.nanoTime() - c.nanos() > HOLD_NS) {
            CLAIMS.remove(key(mapId, npcId), c);
            return false;
        }
        return !c.account().equals(account());
    }

    /** For tests: no claims. */
    static void clearAll() {
        CLAIMS.clear();
    }
}
