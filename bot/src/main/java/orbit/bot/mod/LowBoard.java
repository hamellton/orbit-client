package orbit.bot.mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What the accounts of one LoW group tell each other inside the program (docs/LOW.md; all the accounts can run in one
 * program): the leader's phase — gathering on x-3, jumping in, the gate started, done — and when
 * it began; the helpers read it to come to the portal, to leave after the start and to log in again for the next run.
 * One board per leader (the name as in the game, any case); the helpers that left the group see nothing of it in the
 * game's packets any more, so the board is their only word from the leader.
 *
 * <p>The leader's own crew (docs/LOW.md §11): the leader's config lists the accounts that are his
 * keys to the gate; the board is then keyed by the leader's account ({@code name@server}), the leader writes his name in
 * the game and his LoW settings on it, each helper its name, and the program's {@link Crew} starts the helpers.
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

    /** The program's accounts as the leader's crew: the window's runners (none in tests and alone in a terminal). */
    public interface Crew {
        /**
         * Starts the stopped account {@code helper} as {@code leader}'s helper.
         *
         * @return {@code null} when it runs for the leader now, otherwise why not (e.g. already at work)
         */
        String take(String leader, String helper);

        /** {@code helper} runs as {@code leader}'s helper now. */
        boolean holds(String leader, String helper);
    }

    private static volatile Crew crew;

    public static void crew(Crew c) {
        crew = c;
    }

    public static Crew crew() {
        return crew;
    }

    /** A gate started this long ago is still played (the leader's death and return keep it). */
    public static final long ACTIVE_MS = 40 * 60_000L;

    private final String leader;
    private volatile Phase phase = Phase.IDLE;
    private volatile long phaseAt;
    private volatile long startedAt;
    private volatile int runs;
    private final Map<String, String> helpers = new ConcurrentHashMap<>();
    private volatile String leaderName = "";
    private volatile com.fasterxml.jackson.databind.JsonNode settings;
    /** A crew helper's account (lower case) → its name in the game. */
    private final Map<String, String> nicks = new ConcurrentHashMap<>();

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
        crew = null;
    }

    /** The crew leader's word: his name in the game and his LoW settings (the helpers play by them). */
    public void publish(String name, com.fasterxml.jackson.databind.JsonNode lowSettings) {
        if (name != null && !name.isBlank()) {
            leaderName = name;
        }
        settings = lowSettings;
    }

    /** The crew leader's name in the game; empty till he is in. */
    public String leaderName() {
        return leaderName;
    }

    /** The crew leader's LoW settings; {@code null} till he is in. */
    public com.fasterxml.jackson.databind.JsonNode settings() {
        return settings;
    }

    /** A crew helper's name in the game, once it is in. */
    public void nick(String account, String name) {
        if (account != null && name != null && !name.isBlank()) {
            nicks.put(account.strip().toLowerCase(java.util.Locale.ROOT), name);
        }
    }

    /** The crew helpers' names in the game (the accounts in game at least once). */
    public Map<String, String> nicks() {
        return Map.copyOf(nicks);
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

    /** A helper's word for the window (at the portal, left…). */
    public void helper(String name, String status) {
        if (name != null && !name.isBlank()) {
            helpers.put(name, status);
        }
    }

    public Map<String, String> helpers() {
        return Map.copyOf(helpers);
    }
}
