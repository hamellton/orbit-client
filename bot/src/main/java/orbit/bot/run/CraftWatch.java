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
public final class CraftWatch {

    private static final Logger log = LoggerFactory.getLogger(CraftWatch.class);

    static final long CHECK_MS = 5_000;
    /** A start the game ignored (no resources): tried again no sooner than this. */
    static final long IGNORED_RETRY_MS = 30 * 60_000;
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
        java.util.List<BotConfig.CraftRule> rules = new java.util.ArrayList<>(b.crafts());
        // the galaxy gates' EBG: a CPU crafted when none is left (modules.gate.eternal.craft)
        BotConfig cfg = ctx.config().get();
        if (cfg.module() == BotConfig.Module.GALAXY_GATE) {
            com.fasterxml.jackson.databind.JsonNode gate = cfg.modules().get(BotConfig.Module.GALAXY_GATE.key());
            BotConfig.CraftRule cpu = gate == null ? null : orbit.bot.mod.EternalGate.craftRule(gate);
            if (cpu != null) {
                rules.add(cpu);
            }
        }
        // the Mimesis escort: EMAA-20 crafted from the Byte Remnants (modules.mimesis.craftEmaa, AstraBot «Craft EMAA-20»)
        if (cfg.module() == BotConfig.Module.MIMESIS) {
            BotConfig.CraftRule emaa = orbit.bot.mod.MimesisModule.craftRule(cfg);
            if (emaa != null) {
                rules.add(emaa);
            }
        }
        WorldSnapshot w = ctx.world().get();
        // a quest's «collect built recipes» (Season Pass 700030/700031): cheap batches one after another
        BotConfig.CraftRule quest = orbit.bot.mod.QuestCrafts.craftRule(cfg, w);
        if (quest != null && rules.stream().noneMatch(r -> r.recipe().equals(quest.recipe()))) {
            rules.add(quest);
        }
        if (rules.isEmpty()) {
            return;
        }
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
        for (BotConfig.CraftRule r : rules) {
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
            if (last != null && told.add("!" + r.recipe())) {
                // a start the game ignored: the resources are short (the game tells their counts only as they change)
                log.warn("Крафт: игра не запустила {} — видимо, не хватает ресурсов (нужно: {}); снова через {} мин",
                        name(r), price(r.recipe(), r.amount()), IGNORED_RETRY_MS / 60_000);
                startedAt.put(r.recipe(), now + (IGNORED_RETRY_MS - RETRY_MS) * 1_000_000);
                continue;
            }
            String lacking = lacking(w, r.recipe(), r.amount());
            if (lacking != null) {
                if (told.add("$" + r.recipe() + lacking)) {
                    log.info("Крафт: {} — не хватает {}", name(r), lacking);
                }
                continue; // the game would ignore the start in silence
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

    /**
     * What the account lacks for {@code amount} batches of the recipe (the client's prices, {@link RecipeCosts}), as
     * «Eternal Fragment 120 из 500, …»; {@code null} when enough or when not known.
     */
    public static String lacking(WorldSnapshot w, String recipe, int amount) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (orbit.config.RecipeCosts.Part p : orbit.config.RecipeCosts.of(recipe)) {
            double have = switch (p.item()) {
                case "currency_credits" -> w.hero().credits();
                case "currency_uridium" -> w.hero().uridium();
                default -> count(w, p.item());
            };
            long need = p.amount() * Math.max(1, amount);
            if (have >= 0 && have < need) {
                out.add(partName(p.item()) + " " + num(have) + " из " + num(need));
            }
        }
        return out.isEmpty() ? null : String.join(", ", out);
    }

    /** «500 Eternal Fragment, 500 Rinusk, 150 000 кредиты» — a recipe's price for {@code amount} batches. */
    public static String price(String recipe, int amount) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (orbit.config.RecipeCosts.Part p : orbit.config.RecipeCosts.of(recipe)) {
            out.add(num(p.amount() * Math.max(1, amount)) + " " + partName(p.item()));
        }
        return out.isEmpty() ? "?" : String.join(", ", out);
    }

    private static String num(double v) {
        return String.format(java.util.Locale.ROOT, "%,.0f", v).replace(',', ' ');
    }

    /** «кредиты», «уридий», «Eternal Fragment» for a price's part. */
    static String partName(String id) {
        return switch (id) {
            case "currency_credits" -> "кредиты";
            case "currency_uridium" -> "уридий";
            default -> {
                String bare = id.replaceFirst("^resource_collectable_", "").replaceFirst("^resource_", "");
                String ru = orbit.config.LootNames.known(bare);
                if (ru != null) {
                    yield ru;
                }
                String l = ItemCatalog.label(id);
                yield l.equals(id) ? java.util.Arrays.stream(bare.split("[-_]")).filter(x -> !x.isEmpty())
                        .map(x -> Character.toUpperCase(x.charAt(0)) + x.substring(1)).reduce((a1, b1) -> a1 + " " + b1)
                        .orElse(bare) : l;
            }
        };
    }

    /** The item's count: the item bar's, else the inventory's; -1 when the game has told neither. */
    static double count(WorldSnapshot w, String item) {
        if (item == null || item.isBlank()) {
            return -1;
        }
        if (orbit.config.GateCatalog.EBG_CPU.equals(item) && w.gates().eternal().known()) {
            return w.gates().eternal().cpus(); // the server tells EBG's CPUs itself
        }
        WorldSnapshot.Item i = w.items().get(ItemCatalog.barItem(item));
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
