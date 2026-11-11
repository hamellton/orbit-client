package orbit.config;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Random;

/**
 * When the bot plays and when it rests, from three sources: the account's timetable (or a fixed
 * config), the config's work/break sessions, and a test limit ({@code --minutes}). Each call plans the
 * next stretch: online with a config until a moment, or offline until a moment.
 */
public final class SessionPlanner {

    /**
     * @param config the config to play, {@code null} = offline
     * @param until  end of this stretch ({@code null} = open-ended)
     * @param breakAfter how long to rest when the stretch ends by the work limit (zero = none)
     * @param reason why, for the window and the log
     */
    public record Plan(String config, LocalDateTime until, Duration breakAfter, String reason) {

        public boolean online() {
            return config != null;
        }
    }

    private final AccountSettings.Entry account;
    private final String fixedConfig;
    private final long seed;
    private final Random random;

    /**
     * @param fixedConfig {@code --config}: always this config, the timetable is ignored
     * @param seed        per account, for the timetable's daily shifts
     */
    public SessionPlanner(AccountSettings.Entry account, String fixedConfig, long seed, Random random) {
        this.account = account;
        this.fixedConfig = fixedConfig;
        this.seed = seed;
        this.random = random;
    }

    /**
     * @param session the work/break settings of the config the timetable picks (looked up by the caller)
     */
    public Plan next(LocalDateTime now, java.util.function.Function<String, BotConfig.Session> session) {
        String name;
        LocalDateTime until;
        if (fixedConfig != null) {
            name = fixedConfig;
            until = null;
        } else {
            Schedule.Decision d = account.decide(now, seed);
            if (!d.online()) {
                return new Plan(null, d.until(), Duration.ZERO,
                        account.schedule().enabled() ? "перерыв" : "нет конфига");
            }
            name = d.config();
            until = d.until();
        }
        BotConfig.Session s = session.apply(name);
        if (s == null || s.workMinutes() <= 0) {
            return new Plan(name, until, Duration.ZERO, until == null ? "без ограничения" : "по расписанию");
        }
        LocalDateTime workEnd = now.plus(jittered(s.workMinutes(), s.jitterMinutes()));
        if (until != null && !workEnd.isBefore(until)) {
            return new Plan(name, until, Duration.ZERO, "по расписанию");
        }
        Duration rest = s.breakMinutes() > 0 ? jittered(s.breakMinutes(), s.jitterMinutes()) : Duration.ZERO;
        return new Plan(name, workEnd, rest, "сессия " + s.workMinutes() + " мин");
    }

    /** {@code minutes} ± up to {@code jitter} minutes, at least one minute. */
    Duration jittered(int minutes, int jitter) {
        long seconds = minutes * 60L;
        if (jitter > 0) {
            seconds += (long) ((random.nextDouble() * 2 - 1) * jitter * 60);
        }
        return Duration.ofSeconds(Math.max(60, seconds));
    }
}
