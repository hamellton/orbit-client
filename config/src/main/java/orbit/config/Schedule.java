package orbit.config;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Timetable of an account (like AstraBot's {@code enableTimeTable}): which config runs when, the
 * rest of the time the bot is offline. Entries are checked in order, the first one covering the
 * moment wins; an entry without a config is a break. A window may cross midnight
 * ({@code 22:00–02:00}); its days are the days it starts on.
 *
 * <p>{@code jitterMinutes} moves every start and end by up to that many minutes, differently each
 * day but the same all day long (a player does not log in at 08:00:00 sharp every morning).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record Schedule(boolean enabled, List<Entry> entries, int jitterMinutes) {

    public static final Schedule OFF = new Schedule(false, List.of(), 0);

    public Schedule {
        entries = entries == null ? List.of() : List.copyOf(entries);
        jitterMinutes = Math.max(0, Math.min(jitterMinutes, 60));
    }

    /**
     * @param days   empty = every day
     * @param from   {@code HH:mm}
     * @param to     {@code HH:mm}; equal to {@code from} = the whole day
     * @param config config name; {@code null} or empty = break (offline)
     */
    @JsonInclude(JsonInclude.Include.NON_EMPTY)
    public record Entry(Set<DayOfWeek> days, String from, String to, String config) {

        public Entry {
            days = days == null || days.isEmpty() ? Set.of() : java.util.Collections.unmodifiableSet(EnumSet.copyOf(days));
            LocalTime.parse(from);
            LocalTime.parse(to);
            config = config == null || config.isBlank() ? null : config;
        }

        @JsonIgnore
        public boolean isBreak() {
            return config == null;
        }
    }

    /** What to do at a moment: run {@code config}, or nothing ({@code config == null}) until {@code until}. */
    public record Decision(String config, LocalDateTime until) {

        public boolean online() {
            return config != null;
        }
    }

    /**
     * @param seed per-account value, so two accounts on one timetable do not start in the same minute
     */
    public Decision at(LocalDateTime now, long seed) {
        // a window may have started yesterday (crossing midnight)
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            for (int back = 1; back >= 0; back--) {
                LocalDate start = now.toLocalDate().minusDays(back);
                if (!e.days().isEmpty() && !e.days().contains(start.getDayOfWeek())) {
                    continue;
                }
                LocalDateTime[] w = window(e, i, start, seed);
                if (!now.isBefore(w[0]) && now.isBefore(w[1])) {
                    return new Decision(e.config(), w[1]);
                }
            }
        }
        return new Decision(null, nextStart(now, seed));
    }

    /** The next moment some window begins (within a week), or a week from now. */
    private LocalDateTime nextStart(LocalDateTime now, long seed) {
        LocalDateTime best = now.plusDays(7);
        for (int i = 0; i < entries.size(); i++) {
            Entry e = entries.get(i);
            if (e.isBreak()) {
                continue;
            }
            for (int d = 0; d <= 7; d++) {
                LocalDate start = now.toLocalDate().plusDays(d);
                if (!e.days().isEmpty() && !e.days().contains(start.getDayOfWeek())) {
                    continue;
                }
                LocalDateTime s = window(e, i, start, seed)[0];
                if (s.isAfter(now) && s.isBefore(best)) {
                    best = s;
                }
            }
        }
        return best;
    }

    private LocalDateTime[] window(Entry e, int index, LocalDate day, long seed) {
        LocalTime from = LocalTime.parse(e.from());
        LocalTime to = LocalTime.parse(e.to());
        LocalDateTime start = day.atTime(from);
        LocalDateTime end = to.isAfter(from) ? day.atTime(to) : day.plusDays(1).atTime(to);
        if (jitterMinutes > 0) {
            SplittableRandom r = new SplittableRandom(seed * 31 + day.toEpochDay() * 1_000 + index);
            start = start.plusSeconds(r.nextLong(-jitterMinutes * 60L, jitterMinutes * 60L + 1));
            end = end.plusSeconds(r.nextLong(-jitterMinutes * 60L, jitterMinutes * 60L + 1));
        }
        return new LocalDateTime[] {start, end};
    }
}
