package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import orbit.bot.run.BotContext;
import orbit.config.GateCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Eternal Blacklight gate (EBG) as DarkBot's SharedPlugin {@code EternalBlacklightGate} plays it (docs/EBG.md), with the
 * user's additions (2026-11-16): a number of runs, crafting the CPU, logging out when they are over, the lasers enriched
 * before a run, the abilities on the hardest NPCs. Settings {@code modules.gate.eternal}:
 * <ul>
 *   <li>{@code runs} (0 = no end), {@code map} («1-BL»: where the CPU is used and the portal entered);</li>
 *   <li>{@code craft}: craft a CPU when none is left (the assembly's recipe, resources permitting), {@code whenOut}
 *       {@code logout} | {@code wait};</li>
 *   <li>{@code lasersMin}: not in with fewer laser shots enriched (0 = no check), {@code lasersWaitMinutes}, then
 *       {@code lasersGoAnyway};</li>
 *   <li>{@code brakeOnWave} (0 = off), {@code brakeAction} {@code SUICIDE} | {@code EXIT};</li>
 *   <li>{@code splitUberKristallon}; {@code boosters} {@code {auto, priority: {DAMAGE: 0…}}};</li>
 *   <li>{@code ish}, {@code emp}, {@code ability} {@code {on, npcs: [...]}}, {@code leech} {@code {on, fromWave}}.</li>
 * </ul>
 */
public final class EternalGate {

    private static final Logger log = LoggerFactory.getLogger(EternalGate.class);

    /** Booster types as the server numbers them (EternalBlacklightBoosterCommand) and their names in the settings. */
    static final List<String> BOOSTERS = List.of("ABILITY_COOLDOWN_TIME", "DAMAGE", "DAMAGE_LASER", "DAMAGE_ROCKETS",
            "HITCHANCE_LASER", "HITPOINTS", "SHIELD", "SPEED");
    /** SharedPlugin's priorities by default (lower first). */
    static final int[] DEFAULT_PRIORITY = {1, 0, 0, 1, 0, 1, 2, 2};
    static final String[] BOOSTER_RU = {"перезарядка", "урон", "урон лазеров", "урон ракет", "точность лазеров", "корпус",
        "щит", "скорость"};
    /** The CPU is pressed again no sooner than this (SharedPlugin 250 ms between presses; the portal takes a moment). */
    static final long CPU_EVERY_MS = 3_000;
    /** A booster chosen: the next no sooner (SharedPlugin: 500 ms and a 1 000 ms pause after the click). */
    static final long BOOSTER_EVERY_MS = 1_500;
    /** SUICIDE: within this of the target the ship stays still till it is destroyed (SharedPlugin 1 000). */
    static final double SUICIDE_NEAR = 1_000;

    private final BotContext ctx;
    private int runs;
    private boolean inRun;
    private long cpuAt = Long.MIN_VALUE / 2;
    private long boosterAt = Long.MIN_VALUE / 2;
    private long lasersSince;
    private long labAskedAt = Long.MIN_VALUE / 2;
    private boolean exited;
    private boolean suiciding;
    private String stop;
    private String told = "";
    /** The last centre and tolerance (SharedPlugin keeps the Uber Kristallon centre till the next fight's tick). */
    private double[] centre = {10_000, 6_000};
    private double tolerance = 4_000;

    EternalGate(BotContext ctx) {
        this.ctx = ctx;
    }

    JsonNode settings(JsonNode gate) {
        return gate.path("eternal");
    }

    static boolean on(JsonNode e, String key, boolean def) {
        JsonNode n = e.path(key);
        return n.isBoolean() ? n.asBoolean() : def;
    }

    /**
     * The BL map the run starts from: {@code own} (the default) the company's — a VRU account has no way to 1-BL (live
     * 2026-11-16 on gb2: «нет пути с карты 3-6 на 1-BL»), else the one named.
     */
    int blMap(JsonNode e) {
        String name = e.path("map").asText("own");
        if ("own".equalsIgnoreCase(name)) {
            int f = ctx.world().get().hero().factionId();
            name = (f >= 1 && f <= 3 ? f : 1) + "-BL";
        }
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().byName(name);
        return m != null ? m.id() : 306;
    }

    static boolean bl(int mapId) {
        return mapId == 306 || mapId == 307 || mapId == 308;
    }

    /** The module's stop: the runs done, or no CPU and none to craft with «выйти из игры». */
    String stopRequest() {
        return stop;
    }

    boolean suiciding() {
        return suiciding;
    }

    // ---- out of the gate ----

    /**
     * Out of EBG: the runs, the lasers, the CPU and the portal. Returns the map to be on ({@code 0} = stay where the ship
     * is) and sets the status through {@code show}.
     */
    int outside(JsonNode e, WorldSnapshot w, java.util.function.Consumer<String> show) {
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Eternal ebg = w.gates().eternal();
        if (inRun) {
            inRun = false; // back from a run: dead, out by the brake, or the server put the ship out
            suiciding = false;
            runs++;
            log.info("Вечные врата: забег {} окончен{}", runs, exited ? " (выход по тормозу)" : "");
            exited = false;
        }
        int wanted = e.path("runs").asInt(0);
        if (wanted > 0 && runs >= wanted) {
            return over(e, "забегов сделано " + runs + " из " + wanted, show);
        }
        // the lasers enriched before a run (the user: «чтобы порт всегда не с пустыми лазерами проходить»)
        long min = e.path("lasersMin").asLong(0);
        WorldSnapshot.Enrichment laser = w.inventory().lab().items().get(0);
        long left = laser == null ? 0 : laser.left();
        if (min > 0 && w.inventory().lab().nanos() == 0) {
            if ((now - labAskedAt) / 1_000_000 > 10_000) {
                labAskedAt = now;
                ctx.actions().labInfo(); // the lasers are checked before a run: the lab's answer first
            }
            show.accept("Вечные врата: жду данные лаборатории (лазеры)");
            return 0;
        }
        if (min > 0 && left < min) {
            if (lasersSince == 0) {
                lasersSince = now;
            }
            long waited = (now - lasersSince) / 60_000_000_000L;
            if (waited < e.path("lasersWaitMinutes").asLong(20) || !on(e, "lasersGoAnyway", true)) {
                show.accept("Вечные врата: жду обогащения лазеров — " + left + " из " + min + " выстрелов");
                return 0;
            }
            tellOnce("Вечные врата: лазеры не обогатились за " + waited + " мин (" + left + " из " + min
                    + ") — иду так");
        } else {
            lasersSince = 0;
        }
        if (!ebg.known()) {
            show.accept("Вечные врата: жду от игры число CPU");
            return 0;
        }
        int bl = blMap(e);
        if (bl(h.mapId())) {
            WorldSnapshot.Portal p = w.portals().values().stream().filter(x -> x.designId() == 54)
                    .min(java.util.Comparator.comparingDouble(x -> Math.hypot(x.x() - h.x(), x.y() - h.y()))).orElse(null);
            if (p != null) {
                portal = p;
                show.accept("Вечные врата: лечу в портал");
                return h.mapId();
            }
            portal = null;
            if (ebg.cpus() > 0) {
                if ((now - cpuAt) / 1_000_000 > CPU_EVERY_MS) {
                    cpuAt = now;
                    log.info("Вечные врата: использую CPU (осталось {})", ebg.cpus());
                    ctx.actions().useItem(GateCatalog.EBG_CPU);
                }
                show.accept("Вечные врата: открываю портал CPU (" + ebg.cpus() + " шт.)");
                return h.mapId();
            }
        } else {
            portal = null;
        }
        if (ebg.cpus() <= 0) {
            if (crafting(e, w)) {
                show.accept("Вечные врата: CPU нет — крафчу");
                return 0;
            }
            return over(e, "CPU для Вечных врат нет" + (on(e, "craft", false) ? " и скрафтить не из чего" : ""), show);
        }
        show.accept("Вечные врата: лечу на " + ctx.maps().info(bl).name() + " (CPU: " + ebg.cpus() + ")");
        return bl;
    }

    private WorldSnapshot.Portal portal;
    private long recipesSince;

    /** EBG's portal on the BL map, when the CPU opened it. */
    WorldSnapshot.Portal portal() {
        return portal;
    }

    /** A CPU being crafted, or to be: the recipe free and on in the settings. */
    private boolean crafting(JsonNode e, WorldSnapshot w) {
        if (!on(e, "craft", false)) {
            return false;
        }
        if (w.inventory().recipes().isEmpty()) {
            // the game lists the recipes a moment after the login: «nothing to craft» is not judged before (2 min)
            if (recipesSince == 0) {
                recipesSince = ctx.now();
            }
            return (ctx.now() - recipesSince) / 1_000_000 < 120_000;
        }
        recipesSince = 0;
        WorldSnapshot.Recipe r = w.inventory().recipes().get(GateCatalog.EBG_CPU_RECIPE);
        return r != null && (r.state() == WorldSnapshot.RecipeState.AVAILABLE || r.state() == WorldSnapshot.RecipeState.BUILDING
                || r.state() == WorldSnapshot.RecipeState.READY);
    }

    private int over(JsonNode e, String why, java.util.function.Consumer<String> show) {
        if ("logout".equalsIgnoreCase(e.path("whenOut").asText("logout"))) {
            if (stop == null) {
                stop = "Вечные врата: " + why + " — на базу и выход из игры";
                log.info(stop);
            }
            show.accept(stop);
        } else {
            show.accept("Вечные врата: " + why + " — жду");
        }
        return 0;
    }

    /** The craft list's EBG rule: a CPU while none is left (CraftWatch reads it). */
    public static orbit.config.BotConfig.CraftRule craftRule(JsonNode gate) {
        JsonNode e = gate.path("eternal");
        return on(e, "craft", false) ? new orbit.config.BotConfig.CraftRule(GateCatalog.EBG_CPU_RECIPE, GateCatalog.EBG_CPU,
                Math.max(1, e.path("craftKeep").asInt(1)), 1) : null;
    }

    // ---- in the gate ----

    /** In EBG: a run counts once the ship is in. */
    void entered(WorldSnapshot w) {
        if (!inRun) {
            inRun = true;
            log.info("Вечные врата: забег {} начат (CPU осталось {})", runs + 1, w.gates().eternal().cpus());
        }
    }

    int wave(WorldSnapshot w) {
        int wv = w.gates().eternal().wave();
        if (wv > 0) {
            return wv;
        }
        return w.ships().values().stream().filter(WorldSnapshot.Ship::npc).mapToInt(s -> GateCatalog.gateWave(s.name()))
                .max().orElse(0);
    }

    /**
     * The centre and the tolerance (SharedPlugin updateUberKristallonCenter, each fight's tick): on the Uber Kristallon wave
     * (47 of each 51) with exactly two of them — (6 000, 4 000) while they are within 300 of each other, else
     * (14 000, 8 000), tolerance 1 500; otherwise (10 000, 6 000) and 4 000.
     */
    void fightCentre(JsonNode e, WorldSnapshot w) {
        List<WorldSnapshot.Ship> uk = w.ships().values().stream()
                .filter(s -> s.npc() && "uberkristallon".equals(GateCatalog.gateNpc(s.name()))).toList();
        boolean ukWave = wave(w) % GateCatalog.EBG_CYCLE == 47
                || uk.stream().anyMatch(s -> GateCatalog.gateWave(s.name()) == 47);
        if (on(e, "splitUberKristallon", true) && ukWave && uk.size() == 2) {
            boolean together = Math.hypot(uk.get(0).x() - uk.get(1).x(), uk.get(0).y() - uk.get(1).y()) < 300;
            centre = together ? new double[] {6_000, 4_000} : new double[] {14_000, 8_000};
            tolerance = 1_500;
        } else {
            centre = new double[] {10_000, 6_000};
            tolerance = 4_000;
        }
    }

    double[] centre() {
        return centre;
    }

    double tolerance() {
        return tolerance;
    }

    /** The brake reached (SharedPlugin brakeOnWave, 0 = off). */
    boolean braking(JsonNode e, WorldSnapshot w) {
        int b = e.path("brakeOnWave").asInt(0);
        return b > 0 && wave(w) >= b;
    }

    boolean exitBrake(JsonNode e) {
        return "EXIT".equalsIgnoreCase(e.path("brakeAction").asText("SUICIDE"));
    }

    /**
     * SUICIDE (SharedPlugin pauseForSuicideWave): the fire stopped, the pet passive, to the target; within 1 000 the ship
     * stays still till it is destroyed. True while it does.
     */
    boolean suicide(JsonNode e, WorldSnapshot w, WorldSnapshot.Ship target, Attacker attacker) {
        if (!braking(e, w) || exitBrake(e) || target == null && !suiciding) {
            return false;
        }
        if (!suiciding) {
            suiciding = true;
            log.info("Вечные врата: волна {} — тормоз «самоубийство»: стоп огонь, жду гибели у NPC", wave(w));
            attacker.clear();
            ctx.actions().stopAttack();
        }
        WorldSnapshot.Hero h = w.hero();
        if (target != null && Math.hypot(target.x() - h.x(), target.y() - h.y()) > SUICIDE_NEAR) {
            ctx.nav().moveTo(target.x(), target.y(), false);
            ctx.nav().tick();
        } else {
            ctx.nav().stop();
        }
        return true;
    }

    /** EXIT (SharedPlugin tryExit): between the waves, nothing to pick — the home portal. */
    WorldSnapshot.Portal exit(JsonNode e, WorldSnapshot w) {
        if (!braking(e, w) || !exitBrake(e)) {
            return null;
        }
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Portal home = w.portals().values().stream().filter(p -> p.designId() == GateCatalog.HOME_PORTAL)
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - h.x(), p.y() - h.y()))).orElse(null);
        if (home != null && !exited) {
            exited = true;
            log.info("Вечные врата: волна {} — тормоз «выход»: домой порталом", wave(w));
        }
        return home;
    }

    /**
     * Between the waves (SharedPlugin selectBestBooster): the offered booster of the lowest priority, the higher per cent
     * on a tie; the window opened and the choice sent while there are points; none — the window closed.
     */
    void boosters(JsonNode e, WorldSnapshot w) {
        WorldSnapshot.Eternal ebg = w.gates().eternal();
        JsonNode b = e.path("boosters");
        if (!on(b, "auto", true) || ebg.points() <= 0 || ebg.options().isEmpty()) {
            return;
        }
        long now = ctx.now();
        if ((now - boosterAt) / 1_000_000 < BOOSTER_EVERY_MS) {
            return;
        }
        int best = -1;
        int bestPrio = Integer.MAX_VALUE;
        int bestValue = -1;
        for (int i = 0; i < ebg.options().size(); i++) {
            int[] o = ebg.options().get(i);
            int type = o[0];
            String key = type >= 0 && type < BOOSTERS.size() ? BOOSTERS.get(type) : "";
            int prio = b.path("priority").path(key).asInt(type >= 0 && type < DEFAULT_PRIORITY.length
                    ? DEFAULT_PRIORITY[type] : 0);
            if (prio < bestPrio || prio == bestPrio && o[1] > bestValue) {
                best = i;
                bestPrio = prio;
                bestValue = o[1];
            }
        }
        if (best < 0) {
            return;
        }
        boosterAt = now;
        int type = ebg.options().get(best)[0];
        log.info("Вечные врата: бустер «{}» +{}% (очков {})", type >= 0 && type < BOOSTER_RU.length ? BOOSTER_RU[type] : type,
                bestValue, ebg.points());
        ctx.actions().ebgWindow(true);
        ctx.actions().ebgChooseBooster(best + 1);
        ctx.actions().ebgWindow(false);
    }

    /** The NPC's extra flags of the settings: ISH, EMP, the ship's ability on the named NPCs, the leech from a wave on. */
    java.util.Set<String> abilityFlags(JsonNode e, String bare, int wave) {
        java.util.Set<String> out = new java.util.HashSet<>();
        add(out, e.path("ish"), bare, orbit.config.BotConfig.NpcFlag.USE_ISH);
        add(out, e.path("emp"), bare, orbit.config.BotConfig.NpcFlag.USE_EMP);
        add(out, e.path("ability"), bare, orbit.config.BotConfig.NpcFlag.USE_ABILITY);
        JsonNode leech = e.path("leech");
        if (on(leech, "on", false) && wave >= leech.path("fromWave").asInt(123)) {
            out.add(orbit.config.BotConfig.NpcFlag.USE_LEECH.name());
        }
        return out;
    }

    private static void add(java.util.Set<String> out, JsonNode n, String bare, orbit.config.BotConfig.NpcFlag flag) {
        if (!on(n, "on", false)) {
            return;
        }
        for (JsonNode x : n.path("npcs")) {
            if (bare.equals(x.asText("").toLowerCase(java.util.Locale.ROOT))) {
                out.add(flag.name());
                return;
            }
        }
    }

    private void tellOnce(String s) {
        if (!s.equals(told)) {
            told = s;
            log.info(s);
        }
    }
}
