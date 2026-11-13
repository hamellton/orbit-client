package orbit.bot.mod;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A temporary record of each fight to tune the movement by (the user, 2026-11-13): how long, which way of moving,
 * the target's and the ship's hull at the start and the end, the distance kept, the share of the time in range,
 * who shot the ship and for how long, how often the circle turned. One line in the log and one in
 * {@code local/fight-log.csv} when the fight ends (killed, given up, the ship went to repair, another target).
 */
public final class FightLog {

    private static final Logger log = LoggerFactory.getLogger(FightLog.class);
    /** Off (tests): no lines, no file; the app turns it on. */
    private static volatile boolean enabled;

    public static void enable() {
        enabled = true;
    }
    static final Path FILE = Path.of("local", "fight-log.csv");

    private String target;
    private int map;
    private long start;
    private long lastSample;
    private double heroHp0 = -1;
    private double heroHp1 = -1;
    private double targetHp0 = -1;
    private double targetHp1 = -1;
    private String mode = "";
    private int turns;
    private double distSum;
    private double distMin = Double.MAX_VALUE;
    private int samples;
    private int inRange;
    private final Map<String, Long> hitBy = new LinkedHashMap<>();

    boolean active() {
        return target != null;
    }

    void begin(WorldSnapshot.Ship t, WorldSnapshot.Hero h, long now) {
        target = t.name();
        map = h.mapId();
        start = now;
        lastSample = now;
        heroHp0 = h.health().hpPercent();
        heroHp1 = heroHp0;
        targetHp0 = percent(t);
        targetHp1 = targetHp0;
        mode = "";
        turns = 0;
        distSum = 0;
        distMin = Double.MAX_VALUE;
        samples = 0;
        inRange = 0;
        hitBy.clear();
        inRangeNs = 0;
        targetHitNs = 0;
        shootingNs = 0;
        tune = false;
    }

    private static double percent(WorldSnapshot.Ship t) {
        if (!t.health().known()) {
            return -1;
        }
        double max = t.health().hpMax() + t.health().shieldMax();
        return max <= 0 ? -1 : (t.health().hp() + t.health().shield()) / max;
    }

    /** For the radius learning: the distance fought at, its base, and the time in range and under the target's fire. */
    private double used;
    private double base;
    private boolean tune;
    private long inRangeNs;
    private long targetHitNs;
    /** In range with the ship's lasers on the target (the attack may not have run: then the range is not to blame). */
    private long shootingNs;
    /** Below this share of the time in range with the lasers on, a target that lost nothing says nothing of the range. */
    static final double SHOOTING_SHARE = 0.5;

    /** The radius the fight was fought at and the one with nothing learned; {@code tune} — it may be learned. */
    void radius(double usedRadius, double baseRadius, boolean mayTune) {
        used = usedRadius;
        base = baseRadius;
        tune = mayTune;
    }

    void sample(WorldSnapshot w, WorldSnapshot.Ship t, double radius, String how, long now) {
        if (target == null) {
            return;
        }
        WorldSnapshot.Hero h = w.hero();
        long dt = now - lastSample;
        if (t.distanceTo(h.x(), h.y()) <= radius + 50) {
            inRangeNs += dt;
            if (t.attackTargetId() == h.id()) {
                targetHitNs += dt;
            }
            if (h.attackTargetId() == t.id()) {
                shootingNs += dt;
            }
        }
        lastSample = now;
        heroHp1 = h.health().hpPercent();
        double tp = percent(t);
        if (tp >= 0) {
            targetHp1 = tp;
            if (targetHp0 < 0) {
                targetHp0 = tp;
            }
        }
        mode = how;
        double d = t.distanceTo(h.x(), h.y());
        distSum += d;
        distMin = Math.min(distMin, d);
        samples++;
        if (d <= radius + 50) {
            inRange++;
        }
        for (int id : h.attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            String who = s == null ? "#" + id : s.id() == t.id() ? "цель" : s.name();
            hitBy.merge(who, dt / 1_000_000, Long::sum);
        }
    }

    void turned() {
        turns++;
    }

    void end(String outcome, long now) {
        if (target == null) {
            return;
        }
        String who = target;
        target = null;
        if (tune && inRangeNs > 0) {
            boolean reached = targetHp0 < 0 || targetHp1 < 0 || targetHp1 < targetHp0 - 0.01 || "убит".equals(outcome);
            boolean lostNothing = !reached || "не теряет HP".equals(outcome);
            // live 2026-11-13 on 2-6: Kristallon at 632, in range all 31 s, lost nothing — the attack had not run,
            // the radius was cut for it
            if (!lostNothing || (double) shootingNs / inRangeNs >= SHOOTING_SHARE) {
                RadiusTuner.learn(who, base, used, inRangeNs / 1e9, (double) targetHitNs / inRangeNs, !lostNothing);
            }
        }
        if (!enabled || samples == 0) {
            return;
        }
        double secs = (now - start) / 1e9;
        double lost = heroHp0 - heroHp1;
        StringBuilder hits = new StringBuilder();
        hitBy.forEach((name, ms) -> hits.append(hits.isEmpty() ? "" : ", ").append(name).append(' ')
                .append(Math.round(ms / 1000.0)).append(" с"));
        log.info("Бой с {}: {} с, {}, {}; цель {}→{}, мы {}→{} ({}%/с); дистанция ср. {} мин. {}, в радиусе {}%;"
                        + " били нас: {}; смен стороны {}",
                who, Math.round(secs), mode, outcome, pct(targetHp0), pct(targetHp1), pct(heroHp0), pct(heroHp1),
                secs > 0 ? String.format("%.1f", 100 * lost / secs) : "0", Math.round(distSum / samples),
                Math.round(distMin), Math.round(100.0 * inRange / samples), hits.isEmpty() ? "никто" : hits, turns);
        try {
            boolean header = !Files.exists(FILE);
            Files.createDirectories(FILE.toAbsolutePath().getParent());
            String line = String.join(";", java.time.LocalDateTime.now().withNano(0).toString(), String.valueOf(map),
                    who, mode, outcome, String.valueOf(Math.round(secs)), pct(heroHp0), pct(heroHp1), pct(targetHp0),
                    pct(targetHp1), String.valueOf(Math.round(distSum / samples)), String.valueOf(Math.round(distMin)),
                    String.valueOf(Math.round(100.0 * inRange / samples)), String.valueOf(turns), hits.toString()) + "\n";
            Files.writeString(FILE, (header ? "time;map;npc;mode;outcome;sec;ourHp0;ourHp1;npcHp0;npcHp1;distAvg;distMin;"
                    + "inRangePct;turns;hitBy\n" : "") + line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException e) {
            // a temporary help only
        }
    }

    private static String pct(double v) {
        return v < 0 ? "?" : Math.round(v * 100) + "%";
    }
}
