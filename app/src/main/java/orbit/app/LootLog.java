package orbit.app;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.StringJoiner;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Every gain and every spending in the log: the game's reward lines ({@code 0|LM|ST|…}) of one event
 * (a kill, a box) joined into one «Добыча: …» line, and each drop of credits or uridium as «Потрачено: …».
 */
final class LootLog {

    private static final Logger log = LoggerFactory.getLogger(LootLog.class);
    /** Reward lines closer than this belong to one event. */
    static final long BATCH_NANOS = 700_000_000L;

    private final Map<String, Double> pending = new LinkedHashMap<>();
    private long firstAt;

    /** A legacy text command; reward lines are collected. */
    void legacy(String message, long nanos) {
        String[] a = message.split("\\|", -1);
        if (a.length < 5 || !"LM".equals(a[1]) || !"ST".equals(a[2])) {
            return;
        }
        double v;
        String what;
        switch (a[3]) {
            case "CRE" -> {
                what = "кредитов";
                v = num(a[4]);
            }
            case "URI" -> {
                what = "уридия";
                v = num(a[4]);
            }
            case "HON" -> {
                what = "чести";
                v = num(a[4]);
            }
            case "EP" -> {
                what = "опыта";
                v = num(a[4]);
            }
            case "LOT", "BAT", "ROK", "AMI" -> {
                if (a.length < 6) {
                    return;
                }
                what = SessionSummary.itemName(a[4]);
                v = num(a[5]);
            }
            case "XEN" -> {
                what = "ксеномита";
                v = num(a[4]);
            }
            case "LOG" -> {
                what = "лог-дисков";
                v = num(a[4]);
            }
            default -> {
                return;
            }
        }
        if (v == 0) {
            return;
        }
        if (pending.isEmpty()) {
            firstAt = nanos;
        }
        pending.merge(what, v, Double::sum);
    }

    /** Writes the collected rewards once the event's lines are all in (or at once with {@code force}). */
    void flush(long nanos, boolean force) {
        if (pending.isEmpty() || (!force && nanos - firstAt < BATCH_NANOS)) {
            return;
        }
        StringJoiner j = new StringJoiner("  ·  ");
        pending.forEach((what, v) -> j.add((v > 0 ? "+" : "−") + SessionSummary.num(Math.abs(v)) + " " + what));
        pending.clear();
        log.info("Добыча: {}", j);
    }

    /** Credits or uridium went down: something was paid for (a repair, a purchase, an ability…). */
    void compare(WorldSnapshot.Hero before, WorldSnapshot.Hero after) {
        if (before.id() == 0 || after.id() != before.id()) {
            return;
        }
        spent("кредитов", before.credits(), after.credits());
        spent("уридия", before.uridium(), after.uridium());
    }

    private static void spent(String what, double before, double after) {
        if (before > 0 && after >= 0 && after < before) {
            log.info("Потрачено: {} {} (осталось {})", SessionSummary.num(before - after), what, SessionSummary.num(after));
        }
    }

    private static double num(String s) {
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
