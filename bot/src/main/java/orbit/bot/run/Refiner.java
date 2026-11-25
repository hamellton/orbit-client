package orbit.bot.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Raw ore in the hold refined towards promerium for the lab's drive enrichment (docs/LEVELING.md «Двигатели»: a new account
 * without the Skylab's ore): prometid from 20 prometium + 10 endurium, duranium from 20 terbium + 10 endurium (the
 * endurium shared — the scarcer of the two made first), promerium from 10 prometid + 10 duranium + 1 xenomit (the recipes
 * of {@code QuestModule.RECIPES}, DarkBot's AutoRefine). One refinement at a time ({@code LabRefinementRequest}, the
 * client's window), then a pause.
 *
 * <p>Settings: {@code modules.enrich.refine} ({@code false} by default — the leveling configs turn it on).
 */
final class Refiner {

    private static final Logger log = LoggerFactory.getLogger(Refiner.class);

    static final int PROMETIUM = 0;
    static final int ENDURIUM = 1;
    static final int TERBIUM = 2;
    static final int XENOMIT = 3;
    static final int PROMETID = 4;
    static final int DURANIUM = 5;
    static final int PROMERIUM = 6;
    /** Between two refinements: the hold's counts come back first. */
    static final long GAP_MS = 6_000;

    private final BotContext ctx;
    private long at = Long.MIN_VALUE / 2;

    Refiner(BotContext ctx) {
        this.ctx = ctx;
    }

    private boolean on() {
        JsonNode s = ctx.config().get().modules().get("enrich");
        return s != null && s.path("enabled").asBoolean(false) && s.path("refine").asBoolean(false);
    }

    void tick() {
        if (!on()) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        if (w.hero().id() == 0 || !w.hero().alive()) {
            return;
        }
        long now = ctx.now();
        if ((now - at) / 1_000_000 < GAP_MS) {
            return;
        }
        long[] step = next(w.inventory().ores());
        if (step == null) {
            return;
        }
        at = now;
        log.info("Переработка в трюме: {} {} (для обогащения двигателей)", step[1], NAMES.get((int) step[0]));
        ctx.actions().labRefine((int) step[0], step[1]);
    }

    static final Map<Integer, String> NAMES = Map.of(PROMETID, "прометида", DURANIUM, "дураниума", PROMERIUM, "промерия");

    /** The next refinement as {ore, count}: promerium when it can be made, else the scarcer of prometid and duranium. */
    static long[] next(Map<Integer, Double> ores) {
        long prometium = amount(ores, PROMETIUM);
        long endurium = amount(ores, ENDURIUM);
        long terbium = amount(ores, TERBIUM);
        long xenomit = amount(ores, XENOMIT);
        long prometid = amount(ores, PROMETID);
        long duranium = amount(ores, DURANIUM);
        long promerium = Math.min(Math.min(prometid / 10, duranium / 10), xenomit);
        if (promerium > 0) {
            return new long[] {PROMERIUM, promerium};
        }
        long canPrometid = Math.min(prometium / 20, endurium / 10);
        long canDuranium = Math.min(terbium / 20, endurium / 10);
        boolean duraniumFirst = duranium < prometid && canDuranium > 0 || canPrometid == 0;
        if (duraniumFirst && canDuranium > 0) {
            // half the endurium at most, the other half kept for prometid
            long n = prometium >= 20 ? Math.max(1, Math.min(canDuranium, endurium / 20)) : canDuranium;
            return new long[] {DURANIUM, n};
        }
        if (canPrometid > 0) {
            long n = terbium >= 20 ? Math.max(1, Math.min(canPrometid, endurium / 20)) : canPrometid;
            return new long[] {PROMETID, n};
        }
        return null;
    }

    private static long amount(Map<Integer, Double> ores, int ore) {
        Double v = ores.get(ore);
        return v == null ? 0 : (long) Math.floor(v);
    }
}
