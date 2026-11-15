package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.config.BotConfig;
import orbit.config.GateCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Galaxy gates (docs/GALAXY-GATES.md; the user, 2026-11-14: «модель гелекси гейта по примеру даркбота и астработа —
 * сначала абг, зеты, коппы, трініті»). Out of a gate, on the company's x-1: the builder's state (as the client's window,
 * {@code ggBuilder}), the first gate of the list with lives enough — placed when its parts are all there, spun for when
 * {@code build} — and its portal ({@link GateCatalog.Gate#portal}) jumped into. In a gate: the NPCs (the config's rules,
 * else the catalog's), the boxes between waves, else the map's centre; a portal of the gate (the next stage of ABG, ζ1 → ζ2
 * → ζ3) once nothing is left to fight; the gate completed — out by its home portal, else a new login (as AstraBot).
 * Trinity Trials is entered by its window's request, no portal.
 *
 * <p>Settings ({@code modules.gate}): {@code gates} — the order, each {@code "alpha"} or {@code {gate, on, minLives}};
 * {@code build}, {@code spinAmount}, {@code multiplier}, {@code energyOnly}, {@code keepUridium}, {@code place};
 * {@code boxRadius}; {@code trinityAttribute} (1 Hellfire, 2 Genome), {@code trinityDifficulty} (1–5).
 */
public final class GateModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(GateModule.class);

    /** The builder's state is asked again after this out of a gate. */
    static final long INFO_MS = 120_000;
    /** The builder window closes this long after its last call. */
    static final long WINDOW_MS = 4_000;
    /** A placed gate whose portal is not seen on x-1 this long: a new login (DarkBot's builder does so). */
    static final long PORTAL_WAIT_MS = 30_000;
    /** Nothing to fight or pick up this long before a gate's portal is taken. */
    static final long QUIET_MS = 3_000;
    /** A completed gate with no portal home this long: a new login. */
    static final long OUT_WAIT_MS = 20_000;
    /** Trinity: its window asked this often until it answers (AstraBot: every 5 s). */
    static final long TRINITY_ASK_MS = 5_000;
    static final long TRINITY_ENTER_MS = 15_000;
    /** The jump is pressed this close to a portal (the server offers it up to ~500). */
    static final double JUMP_RANGE = 450;
    /** The escort's portal of the same type as ζ's stands at the centre of the base maps. */
    static final double CENTER_PORTAL = 1_500;
    /** A gate that could not be placed or entered is left this long. */
    static final long SKIP_MS = 5 * 60_000L;

    private final BotContext ctx;
    /** The module's own view of the context: the config with the catalog's rules for gate NPCs it does not list. */
    private final BotContext gctx;
    private final Attacker attacker;
    private final BoxPicker boxes;
    private final GateKamikaze kamikaze;
    private volatile Runnable refresh;
    private String status = "";
    private String told = "";

    private long infoAskedAt = Long.MIN_VALUE / 2;
    private long windowUntil;
    private boolean windowOpen;
    private long spinAt;
    private long spinAskedAt = Long.MIN_VALUE / 2;
    private long placeAskedAt = Long.MIN_VALUE / 2;
    private final Map<String, Long> skipUntil = new HashMap<>();
    private long pressAt;
    private long pressedAt = Long.MIN_VALUE / 2;
    private int placedPortalFor;
    private long placedAt;
    private long trinityAskedAt = Long.MIN_VALUE / 2;
    private long trinityEnterAt = Long.MIN_VALUE / 2;

    /** The gate the ship is in (its key), when it came in, and since when nothing is left to do there. */
    private String inGate;
    private long enteredAt;
    private long quietSince;
    private long completedSeen;
    private int lastWave = -1;

    private BotConfig cachedBase;
    private java.util.Set<String> cachedNames = java.util.Set.of();
    private int cachedMap;
    private BotConfig cached;

    public GateModule(BotContext ctx) {
        this.ctx = ctx;
        this.gctx = new BotContext(ctx.world(), this::config, ctx.actions(), ctx.nav(), ctx.traveler(), ctx.maps(),
                ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(), ctx.ships());
        this.attacker = new Attacker(gctx);
        this.boxes = new BoxPicker(gctx);
        this.kamikaze = new GateKamikaze(gctx);
    }

    @Override
    public void useRefresh(Runnable refresh) {
        this.refresh = refresh;
    }

    @Override
    public String status() {
        return status;
    }

    /** In a gate — its map (no travel); else the company's x-1, where the gates' portals stand. */
    @Override
    public int workingMap() {
        WorldSnapshot.Hero h = ctx.world().get().hero();
        if (GateCatalog.byMap(h.mapId()) != null) {
            return h.mapId();
        }
        orbit.world.maps.MapCatalog.MapInfo home = h.factionId() > 0 ? ctx.maps().byName(h.factionId() + "-1") : null;
        return home != null ? home.id() : 0;
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0;
    }

    @Override
    public int fightTarget() {
        return attacker.targetId();
    }

    @Override
    public void pause() {
        attacker.clear();
        boxes.clear();
    }

    /**
     * The pet in HP Link (AstraBot «useHpLink» with «hpLinkMinPercent» 75 and the NPC option «HP Link» on β/γ
     * Kristallon): while an NPC flagged so is fought, or the hull is below {@code hpLinkBelow} % in a gate.
     */
    @Override
    public int petGear() {
        int link = orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK;
        if (kamikaze.gear() >= 0) {
            return kamikaze.gear(); // gathering: passive; the blast: kamikaze
        }
        if (attacker.fighting(BotConfig.NpcFlag.HP_LINK)) {
            return link;
        }
        WorldSnapshot.Hero h = ctx.world().get().hero();
        double below = num(settings(), "hpLinkBelow", 0) / 100.0;
        double hp = h.health().hpPercent();
        return below > 0 && hp >= 0 && hp < below && GateCatalog.byMap(h.mapId()) != null ? link : -1;
    }

    /**
     * The config as the module fights by: on a gate's map the gate NPCs in sight the config does not list get the
     * catalog's rule (the radius of the VPS profiles, to be killed). The config's own rules come first.
     */
    public BotConfig config() {
        BotConfig base = ctx.config().get();
        WorldSnapshot w = ctx.world().get();
        int map = w.hero().mapId();
        GateCatalog.Gate gate = GateCatalog.byMap(map);
        if (gate == null) {
            return base;
        }
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc() && s.name() != null && base.npc(s.name()) == null && GateCatalog.gateNpc(s.name()) != null) {
                names.add(s.name());
            }
        }
        boolean end = endGate(gate, w);
        synchronized (this) {
            if (base == cachedBase && map == cachedMap && names.equals(cachedNames) && end == cachedEnd) {
                return cached;
            }
            JsonNode s = base.modules().getOrDefault(BotConfig.Module.GALAXY_GATE.key(),
                    com.fasterxml.jackson.databind.node.MissingNode.getInstance());
            BotConfig.ShipMode endMode = end ? endMode(s, base) : null;
            Map<String, BotConfig.NpcRule> npcs = new java.util.LinkedHashMap<>(base.npcs());
            for (String n : names) {
                BotConfig.NpcRule r = GateCatalog.defaultRule(gate, n);
                // the NPC's formation (AstraBot «Galaxy - Gate»: Wheel on Streuner, Lordakia, Saimon, Drill on
                // Kristallin) — not on the last stage, the end mode's formation is the one then
                String f = s.path("npcFormations").path(GateCatalog.gateNpc(n)).asText("");
                if (!f.isBlank() && endMode == null) {
                    r = new BotConfig.NpcRule(r.radius(), r.priority(), r.kill(), r.flags(), r.maps(), r.ammo(), r.sab(),
                            r.rocket(), r.launcher(), r.move(), f);
                }
                // the NPC's ammunition (DarkBot ABG: key 3 on Kristallin, Kristallon, Protegit; AstraBot RSB on them) —
                // only while the item bar has enough of it, else the config's
                String a = s.path("npcAmmo").path(GateCatalog.gateNpc(n)).asText("");
                WorldSnapshot.Item stock = a.isBlank() ? null : w.items().get(a);
                if (stock != null && stock.counter() >= s.path("npcAmmoMin").asInt(1_000)) {
                    r = new BotConfig.NpcRule(r.radius(), r.priority(), r.kill(), r.flags(), r.maps(), a, r.sab(),
                            r.rocket(), r.launcher(), r.move(), r.formation());
                }
                npcs.put(n, r);
            }
            BotConfig c = base.withNpcs(npcs);
            BotConfig.General g = c.general();
            // in a gate the NPCs are flown to in the fight's mode, not the run's (live 2026-11-15 on gb2: config 2 and
            // Ring at each stage's start, 16 switches a gate, by the config's «travel in the run mode»)
            BotConfig.ShipMode fight = endMode != null ? endMode : g.offensive();
            boolean inFightMode = s.path("fightModeInGate").asBoolean(true);
            c = c.withGeneral(new BotConfig.General(g.workingMap(), fight, inFightMode ? fight : g.roam(), g.run(),
                    g.runFromEnemies(), g.runFromEnemiesInSight(), g.rememberEnemiesSec(), g.roamSequential(),
                    g.onlyKillPreferred(), !inFightMode && g.runModeTravel(), !inFightMode && g.runModeChase(),
                    g.avoidMines(), g.baseRadius()));
            if (end && !cachedEnd) {
                log.info("Врата {}: последний этап — {}", gate.name(), ItemCatalogLabel.of(fight));
            }
            cached = c;
            cachedBase = base;
            cachedMap = map;
            cachedNames = names;
            cachedEnd = end;
            return cached;
        }
    }

    private boolean cachedEnd;

    /**
     * The last stage of a gate (its last {@code endGateWaves}, 4: ABG's last four of 40) — CARNAGE's END_GATE_MODE,
     * AstraBot's {@code useEndGateConfig}: the configuration and formation for it, when set.
     */
    private boolean endGate(GateCatalog.Gate gate, WorldSnapshot w) {
        JsonNode s = settings();
        if (!s.path("useEndGateConfig").asBoolean(false) || gate.builderId() == 0 || gate.waves() <= 0) {
            return false;
        }
        WorldSnapshot.Gate st = w.gates().gate(gate.builderId());
        int last = Math.max(1, s.path("endGateWaves").asInt(4));
        int from = gate.waves() - last + 1;
        // the server tells the wave once a stage, the last one done (live 2026-11-15 on gb2: «волна 36» while α37–α40
        // were fought): that one done means the last stage; the NPCs' own numbers tell it at once
        if (st != null && st.wave() >= from - 1) {
            return true;
        }
        return w.ships().values().stream().anyMatch(sh -> sh.npc() && GateCatalog.gateWave(sh.name()) >= from);
    }

    /** The end of a gate's mode: {@code endGateConfig} (2) and {@code endGateFormation} (Veteran; the ship lacks it — the formation stays). */
    private static BotConfig.ShipMode endMode(JsonNode s, BotConfig base) {
        int config = s.path("endGateConfig").asInt(2);
        String f = s.path("endGateFormation").asText("drone_formation_f-3d-vt");
        return new BotConfig.ShipMode(config, f.isBlank() ? base.general().offensive().formation() : f);
    }

    /** A mode said for the log. */
    private static final class ItemCatalogLabel {
        static String of(BotConfig.ShipMode m) {
            return "конфигурация " + m.config() + (m.formation() != null ? ", " + orbit.config.ItemCatalog.label(m.formation()) : "");
        }
    }

    private JsonNode settings() {
        JsonNode s = ctx.config().get().modules().get(BotConfig.Module.GALAXY_GATE.key());
        return s != null ? s : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    /** One gate of the list: which, whether on, the lives it needs to be entered. */
    record Entry(GateCatalog.Gate gate, int minLives) {
    }

    /**
     * The list's gates in order: {@code gates} (names or {@code {gate, on, minLives}}); none — DarkBot's {@code gateId}
     * (a map id, as the import keeps it); else α, β, γ.
     */
    List<Entry> entries(JsonNode s) {
        List<Entry> out = new ArrayList<>();
        JsonNode list = s.path("gates");
        if (list.isArray()) {
            for (JsonNode e : list) {
                String key = e.isTextual() ? e.asText() : e.path("gate").asText("");
                GateCatalog.Gate g = GateCatalog.byKey(key);
                if (g != null && (e.isTextual() || e.path("on").asBoolean(true))) {
                    out.add(new Entry(g, e.isTextual() ? 1 : Math.max(1, e.path("minLives").asInt(1))));
                }
            }
            return out;
        }
        int id = s.path("gateId").asInt(0);
        GateCatalog.Gate g = id != 0 ? (GateCatalog.byMap(id) != null ? GateCatalog.byMap(id) : GateCatalog.byBuilderId(id)) : null;
        if (g != null) {
            return List.of(new Entry(g, 1));
        }
        return List.of(new Entry(GateCatalog.byKey("alpha"), 1), new Entry(GateCatalog.byKey("beta"), 1),
                new Entry(GateCatalog.byKey("gamma"), 1));
    }

    @Override
    public void tick() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (w.jumpingTo() != 0) {
            status = "Прыжок";
            return;
        }
        GateCatalog.Gate here = GateCatalog.byMap(h.mapId());
        if (here != null) {
            closeWindow(true);
            inGate(here, w);
            return;
        }
        if (inGate != null) {
            gateLeft(w);
        }
        outside(settings(), w);
        closeWindow(false);
    }

    // ---- out of a gate ----

    private void outside(JsonNode s, WorldSnapshot w) {
        long now = ctx.now();
        WorldSnapshot.Gates gates = w.gates();
        if (gates.infoNanos() == 0 || ms(now - infoAskedAt) > INFO_MS && ms(now - gates.infoNanos()) > INFO_MS) {
            if (ms(now - infoAskedAt) > 10_000) {
                window();
                ctx.actions().gateInfo();
                infoAskedAt = now;
            }
            if (gates.infoNanos() == 0) {
                show("Врата: жду ответ окна постройки");
                return;
            }
        }
        for (Entry e : entries(s)) {
            GateCatalog.Gate g = e.gate();
            if (skipped(g, now)) {
                continue;
            }
            if (g.builderId() == 0) {
                if (trinity(s, w, now)) {
                    return;
                }
                continue;
            }
            WorldSnapshot.Gate st = gates.gate(g.builderId());
            if (st == null) {
                continue;
            }
            if (st.lives() > 0) {
                if (buyLives(s, g, gates, now)) {
                    show("Врата " + g.name() + ": покупаю жизнь");
                    return;
                }
                if (st.lives() < e.minLives()) {
                    tell("Врата " + g.name() + ": жизней " + st.lives() + ", нужно не меньше " + e.minLives() + " — пропускаю");
                    continue;
                }
                enter(g, w, now);
                return;
            }
            if (st.parts() >= g.parts() && g.parts() > 0) {
                if (on(s, "place", true)) {
                    place(g, gates, now);
                    return;
                }
                continue;
            }
        }
        // nothing placed or built: spins for the gate of the list nearest its parts (the user's list first on a tie) —
        // not the first one from naught while another lacks one part (live 2026-11-15 on gb2: α done, α spun from 0/34
        // with β at 47/48)
        if (on(s, "build", false)) {
            Entry best = null;
            double bestShare = -1;
            for (Entry e : entries(s)) {
                GateCatalog.Gate g = e.gate();
                WorldSnapshot.Gate st = g.builderId() == 0 ? null : gates.gate(g.builderId());
                if (st == null || skipped(g, now) || st.lives() > 0 || g.parts() <= 0 || st.parts() >= g.parts()) {
                    continue;
                }
                double share = Math.max(0, st.parts()) / (double) g.parts();
                if (share > bestShare) {
                    bestShare = share;
                    best = e;
                }
            }
            if (best != null && spin(s, best.gate(), gates.gate(best.gate().builderId()), gates, now)) {
                return;
            }
        }
        show("Врата: проходить нечего (не поставлены, частей не хватает" + (on(settings(), "build", false) ? ", спинов нет"
                : ", постройка выключена") + ")");
        idleAtBase(w);
    }

    private boolean skipped(GateCatalog.Gate g, long now) {
        Long until = skipUntil.get(g.key());
        return until != null && now < until;
    }

    private void skip(GateCatalog.Gate g, String why) {
        log.warn("Врата {}: {} — пропускаю на {} мин", g.name(), why, SKIP_MS / 60_000);
        skipUntil.put(g.key(), ctx.now() + SKIP_MS * 1_000_000);
    }

    private void window() {
        if (!windowOpen) {
            ctx.actions().gateWindow(true);
            windowOpen = true;
        }
        windowUntil = ctx.now() + WINDOW_MS * 1_000_000;
    }

    private void closeWindow(boolean now) {
        if (windowOpen && (now || ctx.now() > windowUntil)) {
            ctx.actions().gateWindow(false);
            windowOpen = false;
        }
    }

    private void place(GateCatalog.Gate g, WorldSnapshot.Gates gates, long now) {
        if (gates.setupNanos() > placeAskedAt && gates.setupGate() == g.builderId()) {
            if (gates.setupError() != 0) {
                skip(g, "не ставятся (ошибка " + gates.setupError() + ")");
            }
            return; // the answer is in the gate's lives
        }
        if (ms(now - placeAskedAt) > 10_000) {
            log.info("Врата {}: все части есть ({}) — ставлю", g.name(), g.parts());
            window();
            ctx.actions().gatePlace(g.builderId());
            placeAskedAt = now;
        }
        show("Врата " + g.name() + ": ставлю");
    }

    /**
     * May the builder spin now — DarkBot's {@code canBuildGG} (SharedPlugin): free energy, or (not {@code extraEnergyOnly})
     * a spin's uridium price ({@code energy_cost}) within {@code maxSpinCost} and the uridium at least {@code minUridium}
     * (DarkBot {@code minUriBalance}, AstraBot {@code keepMinUriSpin}).
     */
    public static boolean canSpin(JsonNode s, WorldSnapshot.Gates gates, int amount) {
        if (gates.samples() >= amount) {
            return true;
        }
        if (on(s, "extraEnergyOnly", on(s, "energyOnly", true))) {
            return false;
        }
        if (gates.energyCost() > num(s, "maxSpinCost", 100)) {
            return false;
        }
        double min = num(s, "minUridium", num(s, "keepUridium", 1_000_000));
        return gates.money() - (double) gates.energyCost() * amount >= min;
    }

    /**
     * Spins at a time: DarkBot's by the gate's progress (parts / all — for ABG the least of α, β, γ): below {@code spins100}
     * 100 spins, below {@code spins10} 10, below {@code spins5} 5; none of them set — AstraBot's fixed {@code spinAmount}
     * (1, 5, 10, 100). The wait after a spin is the option's (DarkBot: 500 / 250 / 200 ms), else {@code spinDelay}
     * (AstraBot, 500 ms).
     */
    public static int[] spinOption(JsonNode s, double progress) {
        double s100 = num(s, "spins100", 0);
        double s10 = num(s, "spins10", 0);
        double s5 = num(s, "spins5", 0);
        int delay = (int) num(s, "spinDelay", 500);
        if (s100 > 0 && progress < s100) {
            return new int[] {100, Math.max(delay, 500)};
        }
        if (s10 > 0 && progress < s10) {
            return new int[] {10, Math.max(delay, 250)};
        }
        if (s5 > 0 && progress < s5) {
            return new int[] {5, Math.max(delay, 200)};
        }
        int amount = (int) num(s, "spinAmount", 1);
        amount = amount >= 100 ? 100 : amount >= 10 ? 10 : amount >= 5 ? 5 : 1;
        return new int[] {amount, delay};
    }

    /**
     * The multiplier on: DarkBot's {@code useMultiAt} (the gate's multiplier as the window shows it — the server's 1–5
     * plus one — at least this; 0 never), AstraBot's {@code spinMultiplier} the same.
     */
    public static boolean useMultiplier(JsonNode s, int serverMultiplier) {
        int at = (int) num(s, "useMultiAt", num(s, "spinMultiplier", 0));
        int shown = serverMultiplier >= 1 && serverMultiplier <= 5 ? serverMultiplier + 1 : serverMultiplier;
        return at > 0 && shown >= at;
    }

    /** One spin (or a batch) of the builder for {@code g} when it may. */
    private boolean spin(JsonNode s, GateCatalog.Gate g, WorldSnapshot.Gate st, WorldSnapshot.Gates gates, long now) {
        int[] option = spinOption(s, progress(g, gates));
        int amount = option[0];
        if (!canSpin(s, gates, amount)) {
            boolean energyOnly = on(s, "extraEnergyOnly", on(s, "energyOnly", true));
            tell("Врата " + g.name() + ": спинить нечем (энергия " + (long) gates.samples() + ", уридий " + (long) gates.money()
                    + (energyOnly ? ", только энергия" : ", цена спина " + gates.energyCost() + ", оставить "
                    + (long) num(s, "minUridium", num(s, "keepUridium", 1_000_000))) + ")");
            return false;
        }
        if (gates.spinNanos() > spinAskedAt && gates.spinError() != 0) {
            skip(g, gates.spinError() == 1 ? "спину не хватает денег" : "спин не прошёл (ошибка " + gates.spinError() + ")");
            spinAskedAt = now;
            return false;
        }
        boolean answered = gates.spinNanos() > spinAskedAt || ms(now - spinAskedAt) > 5_000;
        if (answered && now >= spinAt) {
            window();
            if (gates.spinSelected() != amount) {
                ctx.actions().gateSpinAmount(amount);
            }
            ctx.actions().gateSpin(g.builderId(), amount, useMultiplier(s, st.multiplier()));
            spinAskedAt = now;
            spinAt = now + ctx.pause(option[1], option[1] + 300);
            spins += amount;
        }
        show("Врата " + g.name() + ": спины по " + amount + ", частей " + Math.max(0, st.parts()) + " из " + g.parts());
        return true;
    }

    private long spins;

    /** Parts / all of the gate; for ABG the least of the three (DarkBot builds them together). */
    private static double progress(GateCatalog.Gate g, WorldSnapshot.Gates gates) {
        java.util.function.ToDoubleFunction<GateCatalog.Gate> of = x -> {
            WorldSnapshot.Gate st = gates.gate(x.builderId());
            return st == null || x.parts() == 0 ? 0 : Math.max(0, st.parts()) / (double) x.parts();
        };
        if (g.builderId() >= 1 && g.builderId() <= 3) {
            return java.util.stream.Stream.of("alpha", "beta", "gamma").map(GateCatalog::byKey).mapToDouble(of).min().orElse(0);
        }
        return of.applyAsDouble(g);
    }

    /**
     * Lives kept in stock (the user, 2026-11-14: «если осталось 2 жизни, то докупаем за уридиум ещё одну, если урика
     * хватает, и так дальше — чтобы всегда был запас жизней»; AstraBot {@code buyLife}/{@code maxLifeToBuy}): a placed
     * gate with fewer than {@code keepLives} (3) gets one more while the uridium after it stays at
     * {@code lifeMinUridium} (0) or above; {@code maxLivesToBuy} a gate (0 — no limit). True when one was asked for.
     */
    private boolean buyLives(JsonNode s, GateCatalog.Gate g, WorldSnapshot.Gates gates, long now) {
        if (g == null || g.builderId() == 0 || !on(s, "buyLives", false)) {
            return false;
        }
        WorldSnapshot.Gate st = gates.gate(g.builderId());
        int keep = (int) num(s, "keepLives", 3);
        if (st == null || st.lives() <= 0 || st.lives() >= keep || st.lifePrice() <= 0) {
            return false;
        }
        int max = (int) num(s, "maxLivesToBuy", 0);
        if (max > 0 && livesBought.getOrDefault(g.key(), 0) >= max) {
            tell("Врата " + g.name() + ": жизней " + st.lives() + ", куплено уже " + max + " — больше не покупаю");
            return false;
        }
        if (gates.money() - st.lifePrice() < num(s, "lifeMinUridium", 0)) {
            tell("Врата " + g.name() + ": жизней " + st.lives() + ", жизнь стоит " + st.lifePrice() + " уридия, а есть "
                    + (long) gates.money() + " — не покупаю");
            return false;
        }
        if (ms(now - lifeAskedAt) < 5_000) {
            return true;
        }
        log.info("Врата {}: жизней {} (держу {}) — покупаю жизнь за {} уридия", g.name(), st.lives(), keep, st.lifePrice());
        window();
        ctx.actions().gateBuyLife(g.builderId());
        lifeAskedAt = now;
        livesBought.merge(g.key(), 1, Integer::sum);
        return true;
    }

    private long lifeAskedAt = Long.MIN_VALUE / 2;
    private final Map<String, Integer> livesBought = new HashMap<>();

    /** To the gate's portal on x-1 and into it. */
    private void enter(GateCatalog.Gate g, WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Portal p = entryPortal(g, w);
        if (p == null) {
            if (placedPortalFor != g.builderId()) {
                placedPortalFor = g.builderId();
                placedAt = now;
            }
            if (ms(now - placedAt) > PORTAL_WAIT_MS) {
                placedAt = now;
                Runnable r = refresh;
                if (r != null) {
                    log.warn("Врата {} поставлены, а портала (тип {}) на {} нет — перевхожу", g.name(), g.portal(),
                            ctx.maps().info(h.mapId()).name());
                    Thread.ofVirtual().name("refresh").start(r);
                } else {
                    skip(g, "портала не видно");
                }
            }
            show("Врата " + g.name() + ": жду портал на " + ctx.maps().info(h.mapId()).name());
            idleAtBase(w);
            return;
        }
        placedPortalFor = 0;
        if (jumpInto(w, p, now)) {
            show("Лечу во врата " + g.name());
        }
    }

    /** The gate's entry on this map: its type; for ζ not the escort's of the same type at the centre. */
    WorldSnapshot.Portal entryPortal(GateCatalog.Gate g, WorldSnapshot w) {
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(w.hero().mapId());
        double cx = m.width() / 2.0;
        double cy = m.height() / 2.0;
        return w.portals().values().stream().filter(p -> p.designId() == g.portal())
                .filter(p -> !g.noCenter() || Math.hypot(p.x() - cx, p.y() - cy) > CENTER_PORTAL)
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - w.hero().x(), p.y() - w.hero().y())))
                .orElse(null);
    }

    /** Flies onto a portal and presses jump once the server offers it; true while doing so. */
    private boolean jumpInto(WorldSnapshot w, WorldSnapshot.Portal p, long now) {
        WorldSnapshot.Hero h = w.hero();
        double dist = Math.hypot(p.x() - h.x(), p.y() - h.y());
        if (w.activatable().containsKey(p.id()) && dist <= JUMP_RANGE && ms(now - pressedAt) > 3_000) {
            if (pressAt == 0) {
                pressAt = now + ctx.pause(200, 1_000);
            }
            if (now >= pressAt) {
                ctx.actions().jump();
                pressedAt = now;
                pressAt = 0;
                log.info("Прыжок в портал (тип {}) на {}/{}", p.designId(), p.x() / 100, p.y() / 100);
            }
        }
        if (dist > 100 && (ctx.nav().idle() || ctx.nav().destination().x() != p.x() || ctx.nav().destination().y() != p.y())) {
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.nav().moveTo(p.x(), p.y(), true);
        }
        ctx.nav().tick();
        return true;
    }

    private void idleAtBase(WorldSnapshot w) {
        ctx.nav().tick();
    }

    // ---- Trinity Trials ----

    /** Its window asked, then entered while there is a key or the entry is free; true while busy with it. */
    private boolean trinity(JsonNode s, WorldSnapshot w, long now) {
        WorldSnapshot.Trinity t = w.gates().trinity();
        boolean fresh = t.nanos() != 0 && ms(now - t.nanos()) < 60_000;
        if (!fresh) {
            if (trinityFirstAsk == 0) {
                trinityFirstAsk = now;
            }
            if (ms(now - trinityFirstAsk) > 30_000) {
                trinityFirstAsk = 0;
                skip(GateCatalog.byKey("trinity"), "окно Trinity не ответило");
                return false;
            }
            if (ms(now - trinityAskedAt) > TRINITY_ASK_MS) {
                ctx.actions().trinityWindow(0);
                trinityAskedAt = now;
            }
            show("Trinity: жду окно");
            return true;
        }
        trinityFirstAsk = 0;
        if (t.keys() <= 0 && !t.free()) {
            tell("Trinity: ключей нет — пропускаю");
            return false;
        }
        int attr = Math.max(1, (int) num(s, "trinityAttribute", 1));
        int diff = Math.max(1, Math.min(5, (int) num(s, "trinityDifficulty", 1)));
        if (t.openDifficulty() > 0 && diff > t.openDifficulty()) {
            diff = t.openDifficulty();
        }
        if (ms(now - trinityEnterAt) > TRINITY_ENTER_MS) {
            log.info("Trinity: вхожу — атрибут {}, сложность {} (ключей {}{})", attr, diff, t.keys(), t.free() ? ", бесплатно" : "");
            ctx.actions().trinityEnter(attr, diff);
            trinityEnterAt = now;
        }
        show("Trinity: вхожу");
        return true;
    }

    private long trinityFirstAsk;

    // ---- in a gate ----

    private void inGate(GateCatalog.Gate g, WorldSnapshot w) {
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        if (!g.key().equals(inGate)) {
            inGate = g.key();
            enteredAt = now;
            quietSince = 0;
            completedSeen = 0;
            WorldSnapshot.Gate st = w.gates().gate(g.builderId());
            log.info("Во вратах {} ({}){}", g.name(), ctx.maps().info(h.mapId()).name(),
                    st != null && st.lives() > 0 ? ", жизней " + st.lives() : "");
        }
        WorldSnapshot.Gate st = g.builderId() != 0 ? w.gates().gate(g.builderId()) : null;
        if (st != null && st.wave() != lastWave) {
            lastWave = st.wave();
        }
        boolean completed = st != null && st.completedNanos() > enteredAt;
        if (completed && completedSeen == 0) {
            completedSeen = now;
            log.info("Врата {} пройдены за {} мин", g.name(), ms(now - enteredAt) / 60_000);
        }
        buyLives(settings(), g, w.gates(), now); // a life lost in the gate: the stock again (the request goes on aside)
        BotConfig c = config();
        // DarkBot's SharedPlugin: the kamikaze first — gathering the flagged ones, the blast; the fight waits meanwhile
        if (kamikaze.tick(settings(), c, w, attacker)) {
            quietSince = 0;
            status = "Врата " + g.name() + ": камикадзе — " + (kamikaze.stage() == GateKamikaze.Stage.ACTIVE
                    ? "взрыв" : "собираю NPC") + wave(st);
            return;
        }
        // the fight
        if (attacker.targetId() != 0 && attacker.tick(true)) {
            quietSince = 0;
            status = "Врата " + g.name() + ": бой" + wave(st);
            return;
        }
        WorldSnapshot.Ship t = Targets.best(c, w, Double.MAX_VALUE, s -> attacker.gaveUp(s.id()));
        if (t != null) {
            quietSince = 0;
            attacker.target(t);
            attacker.tick(true);
            status = "Врата " + g.name() + ": бой" + wave(st);
            return;
        }
        // between the waves: the boxes about
        double radius = num(settings(), "boxRadius", 1_500);
        if (boxes.tick(h.x(), h.y(), radius)) {
            quietSince = 0;
            status = "Врата " + g.name() + ": подбираю " + boxes.currentName();
            return;
        }
        if (quietSince == 0) {
            quietSince = now;
        }
        kamikaze.waiting(); // between waves: the next one's start is primed (SharedPlugin)
        boolean quiet = ms(now - quietSince) > QUIET_MS;
        // a portal of the gate: the next stage (ABG), the next map (ζ); home only once the gate is done (or Trinity)
        WorldSnapshot.Portal next = w.portals().values().stream().filter(p -> p.designId() != GateCatalog.HOME_PORTAL)
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - h.x(), p.y() - h.y()))).orElse(null);
        WorldSnapshot.Portal home = w.portals().values().stream().filter(p -> p.designId() == GateCatalog.HOME_PORTAL)
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - h.x(), p.y() - h.y()))).orElse(null);
        boolean done = completed || g.builderId() == 0 && home != null && ms(now - enteredAt) > 60_000;
        if (quiet && next != null && !done) {
            tellOnce("Врата " + g.name() + ": портал (тип " + next.designId() + ") — дальше");
            jumpInto(w, next, now);
            status = "Врата " + g.name() + ": в следующий портал";
            return;
        }
        if (quiet && done) {
            if (home != null) {
                tellOnce("Врата " + g.name() + ": пройдены — домой порталом");
                jumpInto(w, home, now);
                status = "Врата " + g.name() + ": домой";
                return;
            }
            if (ms(now - Math.max(completedSeen, quietSince)) > OUT_WAIT_MS) {
                Runnable r = refresh;
                if (r != null) {
                    log.info("Врата {} пройдены, портала домой нет — перевхожу (как AstraBot)", g.name());
                    completedSeen = now + 120_000L * 1_000_000; // not again while the login goes
                    Thread.ofVirtual().name("refresh").start(r);
                }
            }
            status = "Врата " + g.name() + ": пройдены";
            return;
        }
        // nothing yet: the next wave comes — wait in the centre (DarkBot: moveToCenter)
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(h.mapId());
        double cx = m.width() / 2.0;
        double cy = m.height() / 2.0;
        if (Math.hypot(cx - h.x(), cy - h.y()) > 600 && (ctx.nav().idle() || ctx.nav().destination().x() != cx)) {
            ctx.ship().want(c.general().roam());
            ctx.nav().moveTo(cx, cy, false);
        }
        ctx.nav().tick();
        status = "Врата " + g.name() + ": жду волну" + wave(st);
    }

    private static String wave(WorldSnapshot.Gate st) {
        return st != null && st.wave() > 0 ? ", волна " + st.wave() + (st.lives() > 0 ? ", жизней " + st.lives() : "") : "";
    }

    /** Back out of a gate: done, or a life lost (the revive is the runner's). */
    private void gateLeft(WorldSnapshot w) {
        GateCatalog.Gate g = GateCatalog.byKey(inGate);
        WorldSnapshot.Gate st = g != null && g.builderId() != 0 ? w.gates().gate(g.builderId()) : null;
        if (g != null) {
            if (st != null && st.completedNanos() > enteredAt) {
                log.info("Из врат {} вышел — пройдены", g.name());
            } else if (st != null && st.lives() > 0) {
                log.info("Из врат {} вышел, жизней {} — вернусь", g.name(), st.lives());
            } else {
                log.info("Из врат {} вышел", g.name());
            }
        }
        inGate = null;
        lastWave = -1;
        infoAskedAt = Long.MIN_VALUE / 2; // the builder's state again: lives, parts after it
        attacker.clear();
        boxes.clear();
    }

    // ---- helpers ----

    private void show(String s) {
        status = s;
        tell(s);
    }

    private void tell(String s) {
        if (!s.equals(told)) {
            told = s;
            log.info(s);
        }
    }

    private String onceTold = "";

    private void tellOnce(String s) {
        if (!s.equals(onceTold)) {
            onceTold = s;
            log.info(s);
        }
    }

    private static boolean on(JsonNode s, String key, boolean def) {
        JsonNode n = s.path(key);
        return n.isBoolean() ? n.asBoolean() : def;
    }

    private static double num(JsonNode s, String key, double def) {
        JsonNode n = s.path(key);
        return n.isNumber() ? n.asDouble() : def;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
