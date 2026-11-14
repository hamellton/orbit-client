package orbit.bot.run;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crafts what the config's craft list asks for (the user, 2026-11-14: «крафт буквально всего, чтобы можно было
 * конфигурировать это в конфигах»): a recipe free in the assembly and its item below the rule's count — a batch is
 * started; a batch ready — collected. Packets as the official client's (docs/recon/ASSEMBLY.md): the window opened,
 * {@code AssemblyBuildRequest}, the window closed; later {@code AssemblyCollectRequest} the same way. One action every
 * {@link #CHECK_MS}, nothing before the game has listed the recipes.
 */
final class CraftWatch {

    private static final Logger log = LoggerFactory.getLogger(CraftWatch.class);

    static final long CHECK_MS = 5_000;
    /** A start that changed nothing (the recipe still free) is tried again no sooner than this. */
    static final long RETRY_MS = 120_000;
    /** No crafting before the game has listed the recipes after a login. */
    static final long SETTLE_MS = 20_000;

    private final BotContext ctx;
    private long nextCheck;
    private final Map<String, Long> startedAt = new HashMap<>();
    private final Map<String, Long> collectedAt = new HashMap<>();
    /** A collect not answered (the recipe still ready) is sent again no sooner than this. */
    static final long COLLECT_RETRY_MS = 30_000;
    private final Set<String> told = new HashSet<>();

    CraftWatch(BotContext ctx) {
        this.ctx = ctx;
        this.nextCheck = ctx.now() + SETTLE_MS * 1_000_000;
    }

    void tick() {
        long now = ctx.now();
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_MS * 1_000_000;
        BotConfig.Buy b = ctx.config().get().buy();
        if (b.crafts().isEmpty()) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        if (w.hero().id() == 0 || w.death() != null) {
            return;
        }
        Map<String, WorldSnapshot.Recipe> recipes = w.inventory().recipes();
        if (recipes.isEmpty()) {
            if (told.add("?list")) {
                log.info("Крафт: игра ещё не прислала список рецептов — жду");
            }
            return;
        }
        for (BotConfig.CraftRule r : b.crafts()) {
            WorldSnapshot.Recipe rec = recipes.get(r.recipe());
            if (rec == null) {
                if (told.add("?" + r.recipe())) {
                    log.warn("Крафт: рецепта {} нет в сборке игры — пропускаю", r.recipe());
                }
                continue;
            }
            switch (rec.state()) {
                case READY -> {
                    Long asked = collectedAt.get(r.recipe());
                    if (asked != null && now - asked < COLLECT_RETRY_MS * 1_000_000) {
                        continue;
                    }
                    collectedAt.put(r.recipe(), now);
                    log.info("Крафт: забираю {} ×{}", name(r), (long) rec.amount());
                    ctx.actions().assemblyWindow();
                    ctx.actions().assemblyCollect(r.recipe());
                    ctx.actions().assemblyWindow();
                    startedAt.remove(r.recipe());
                    return; // one action a check
                }
                case BUILDING -> {
                    continue;
                }
                case UNAVAILABLE -> {
                    if (told.add("x" + r.recipe())) {
                        log.info("Крафт: {} сейчас недоступен ({})", name(r), rec.unavailable() == 0
                                ? "предмета уже максимум" : "рецепт исчерпан");
                    }
                    continue;
                }
                default -> {
                }
            }
            if (rec.state() != WorldSnapshot.RecipeState.AVAILABLE) {
                continue;
            }
            told.remove("x" + r.recipe());
            double have = count(w, r.item());
            if (r.below() > 0) {
                if (have < 0) {
                    if (told.add("?" + r.item())) {
                        log.warn("Крафт: игра не прислала количество {} — не крафчу", ItemCatalog.label(r.item()));
                    }
                    continue;
                }
                if (have >= r.below()) {
                    continue;
                }
            }
            Long last = startedAt.get(r.recipe());
            if (last != null && now - last < RETRY_MS * 1_000_000) {
                continue;
            }
            startedAt.put(r.recipe(), now);
            log.info("Крафт: запускаю {} ×{} (есть {}, нужно от {})", name(r), r.amount(),
                    have < 0 ? "?" : String.valueOf((long) have), r.below());
            ctx.actions().assemblyWindow();
            ctx.actions().assemblyBuild(r.recipe(), r.amount());
            ctx.actions().assemblyWindow();
            return; // one action a check
        }
    }

    /** The item's count: the item bar's, else the inventory's; -1 when the game has told neither. */
    static double count(WorldSnapshot w, String item) {
        if (item == null || item.isBlank()) {
            return -1;
        }
        WorldSnapshot.Item i = w.items().get(item);
        if (i != null) {
            return i.counter();
        }
        Double inv = w.inventory().resources().get(item);
        return inv == null ? -1 : inv;
    }

    private static String name(BotConfig.CraftRule r) {
        return r.item() != null && !r.item().isBlank() ? ItemCatalog.label(r.item()) : r.recipe();
    }
}
