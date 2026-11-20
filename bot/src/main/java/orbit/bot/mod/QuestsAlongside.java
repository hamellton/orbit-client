package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import orbit.bot.run.BotContext;
import orbit.config.BotConfig;
import orbit.world.Quests;
import orbit.world.WorldSnapshot;

/**
 * Quests alongside another main module (quests on top of the main module, as in DmPlugin, so the season goes on by the
 * way; DmPlugin's {@code useAsBehavior}): {@code modules.quests.alongside} on, the module stays what it is —
 * no flights to the quest giver or to other maps. What goes on beside it:
 * <ul>
 *   <li>the passes' free buttons ({@link PassCare}: Season Pass levels, Battle Pass, Galaxy Trek) — no rerolls and no
 *       diminishing quests: which missions the module moves is not known;</li>
 *   <li>«collect built recipes» — the assembly builds cheap batches ({@link QuestCrafts}, through {@code CraftWatch});</li>
 *   <li>«pick up cargo boxes» ({@link Quests#CARGO}) — a killing module (and the Mimesis farm between the runs, never in
 *       the escort) picks its NPCs' cargo too, the events' cargo as well ({@link #adjust});</li>
 * </ul>
 * The kills, damage and boxes of the module count by themselves. Not with the Quests module (it does all this itself);
 * with the Mimesis one the passes are its own.
 */
public final class QuestsAlongside {

    private final BotContext ctx;
    private final PassCare passes;
    private BotConfig lastIn;
    private Quests lastQuests;
    private int lastMap;
    private BotConfig lastOut;

    public QuestsAlongside(BotContext ctx) {
        this.ctx = ctx;
        this.passes = new PassCare(ctx);
    }

    /** Alongside is on for this config (the Mimesis module too: its farm between the runs, its crafts). */
    public static boolean on(BotConfig c) {
        if (c.module() == BotConfig.Module.QUESTS) {
            return false;
        }
        JsonNode s = c.modules().get(BotConfig.Module.QUESTS.key());
        return s != null && s.path("alongside").asBoolean(false);
    }

    /** The passes' free buttons, not in the middle of a fight. */
    public void tick(boolean busy) {
        BotConfig c = ctx.config().get();
        if (!on(c) || busy || c.module() == BotConfig.Module.MIMESIS) {
            return; // the Mimesis module keeps the passes itself
        }
        WorldSnapshot w = ctx.world().get();
        if (w.hero().id() == 0 || w.death() != null) {
            return;
        }
        passes.tick(w, c.modules().get(BotConfig.Module.QUESTS.key()), q -> false, false);
    }

    /** The module's config with the quests' extras: cargo boxes picked while a cargo condition is open. */
    public BotConfig adjust(BotConfig c) {
        WorldSnapshot w = ctx.world().get();
        Quests q = w.quests();
        int map = w.hero().mapId();
        if (c == lastIn && q == lastQuests && map == lastMap) {
            return lastOut;
        }
        lastIn = c;
        lastQuests = q;
        lastMap = map;
        // never in a gate or the Mimesis escort: the run there comes first
        lastOut = orbit.config.GateCatalog.instanced(map) ? c : withCargo(c, q);
        return lastOut;
    }

    static BotConfig withCargo(BotConfig c, Quests q) {
        if (!on(c) || !(c.module() == BotConfig.Module.KILL_COLLECT || c.module() == BotConfig.Module.KILL
                || c.module() == BotConfig.Module.MIMESIS) || !cargoOpen(q)) {
            return c;
        }
        BotConfig.Collect k = c.collect();
        Map<String, BotConfig.BoxRule> rules = new LinkedHashMap<>(k.boxes());
        boolean added = false;
        for (String box : QuestModule.CARGO_BOXES) {
            BotConfig.BoxRule own = k.boxes().get(box);
            if (own == null || !own.collect()) {
                rules.put(box, new BotConfig.BoxRule(true, own != null ? own.waitMs() : 0, own != null ? own.priority() : 0));
                added = true;
            }
        }
        if (!added) {
            return c;
        }
        return c.withCollect(new BotConfig.Collect(rules, k.radius(), k.stayAwayFromEnemies(), k.ignoreContested(),
                k.cargo(), k.cargoFullAt(), k.sellOres()));
    }

    /** An open «pick up cargo boxes» condition among the taken quests. */
    static boolean cargoOpen(Quests q) {
        return q.active().values().stream().flatMap(x -> x.all().stream())
                .anyMatch(c -> c.type() == Quests.CARGO && !c.completed());
    }
}
