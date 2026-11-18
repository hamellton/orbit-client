package orbit.bot.mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the accounts of one LoW group tell each other inside the program (docs/LOW.md, the user, 2026-11-18: «у нас все
 * аки можно сделать в одной проге»): the leader's phase — gathering on x-3, jumping in, the gate started, done — and when
 * it began; the helpers read it to come to the portal, to leave after the start and to log in again for the next run.
 * One board per leader (the name as in the game, any case); the helpers that left the group see nothing of it in the
 * game's packets any more, so the board is their only word from the leader.
 */
public final class LowBoard {

    public enum Phase {
        /** no leader at work (not started, or logged out) */
        IDLE,
        /** the leader is on x-3 gathering the group at the portal */
        GATHER,
        /** the leader has jumped into the portal (the queue, the instance): the others follow */
        ENTERING,
        /** the gate is on («brought online», or everybody inside): the helpers may leave */
        STARTED,
        /** the gate completed: the leader is on his way back to x-3 */
        DONE
    }

    private static final Map<String, LowBoard> BOARDS = new ConcurrentHashMap<>();

    /** A gate started this long ago is still played (the leader's death and return keep it). */
    public static final long ACTIVE_MS = 40 * 60_000L;

    private final String leader;
    private volatile Phase phase = Phase.IDLE;
    private volatile long phaseAt;
    private volatile long startedAt;
    private volatile int runs;
    private final Map<String, String> helpers = new ConcurrentHashMap<>();

    private LowBoard(String leader) {
        this.leader = leader;
    }

    public static LowBoard of(String leaderName) {
        String key = leaderName == null ? "" : leaderName.strip().toLowerCase(java.util.Locale.ROOT);
        return BOARDS.computeIfAbsent(key, LowBoard::new);
    }

    /** Tests: every board forgotten. */
    public static void reset() {
        BOARDS.clear();
    }

    public String leader() {
        return leader;
    }

    public Phase phase() {
        return phase;
    }

    /** When the phase was set (wall clock, ms). */
    public long phaseAt() {
        return phaseAt;
    }

    public long startedAt() {
        return startedAt;
    }

    public int runs() {
        return runs;
    }

    void set(Phase p, long now) {
        if (phase != p) {
            phase = p;
            phaseAt = now;
            if (p == Phase.STARTED) {
                startedAt = now;
            }
        }
    }

    void runDone(long now) {
        runs++;
        set(Phase.DONE, now);
    }

    /** The gate is on and not stale: a dead member may come back in while in the group. */
    public boolean gateActive(long now) {
        return (phase == Phase.STARTED || phase == Phase.ENTERING) && now - phaseAt < ACTIVE_MS;
    }

    /** The helpers are wanted at the portal: the leader gathers, or has just jumped. */
    public boolean gathering() {
        return phase == Phase.GATHER || phase == Phase.ENTERING;
    }

    /** A helper's word for the window («у портала», «вышел»…). */
    public void helper(String name, String status) {
        if (name != null && !name.isBlank()) {
            helpers.put(name, status);
        }
    }

    public Map<String, String> helpers() {
        return Map.copyOf(helpers);
    }
}
