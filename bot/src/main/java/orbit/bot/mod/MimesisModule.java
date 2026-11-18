package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.config.BotConfig;
import orbit.config.GateCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Mimesis Mutiny escort (docs/MIMESIS.md; the user, 2026-11-18: «поведение берём с даркбота, автоматизацию с
 * астработа»). The run as DarkBot's DonorPlugin {@code MimesisGateModule} plays it — round the EM Freighter at 350–900,
 * the leash 600 / 2 000 with enemies / 2 500 with a box by it, the targets within 2 000 of it (those shooting it first),
 * the boxes within 2 500, out by the portal once the freighter is gone and nothing is left (or the hull is below
 * {@code after.stayHp}); SharedPlugin's {@code MimesisMutinyGate} table for the escort's NPCs the config does not list.
 * Around it AstraBot's {@code MimesisModule}: the keys farmed on the working map between the runs, to x-1 before the full
 * hour, into the portal (type 54 at 10 500, 6 500) once the server opens the entry; EMP-01, ISH-01, the Energy Leech,
 * the fight ability (Solaris+ and the like) on a crowd, the Solace's heal, the pet's HP Link; EMAA-20 on the Mimesis and
 * crafted in the assembly. The time is the server's ({@code PayloadEscortTimeInfoCommand}), the computer's clock only
 * while none came.
 *
 * <p>Settings {@code modules.mimesis}: {@code startTravelSeconds} (180), {@code between} {@code farm} | {@code wait} |
 * {@code logout}, {@code minKeys} (1); {@code after} {@code {stayHp, ignoreNpc, ignoreBoxes}}; {@code emaa}
 * {@code {on, min}}; {@code craftEmaa} {@code {on, below, recipe}}; {@code emp}, {@code ish}, {@code leech},
 * {@code solace} {@code {on, below}} (per cent of the hull); {@code ability} {@code {on, minNpc}}; {@code hpLink}
 * {@code {on, heroBelow, petMin}}.
 */
public final class MimesisModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(MimesisModule.class);

    public static final String KEY = "mimesis";

    // ---- DonorPlugin MimesisGateModule ----
    /** The leash to the freighter: a box within 2 500 of it, enemies about, nothing. */
    static final double LEASH_BOX = 2_500;
    static final double LEASH_ENEMIES = 2_000;
    static final double LEASH_IDLE = 600;
    /** Gone beyond the leash: only flying back till this near. */
    static final double BACK_WITHIN = 1_700;
    /** The orbit round the freighter: 350 + up to 550. */
    static final double ORBIT_MIN = 350;
    static final double ORBIT_SPREAD = 550;
    /** Idle with the way's end this close to the freighter: a new point of the orbit. */
    static final double IDLE_NEAR = 100;
    /** Targets within this of the living freighter (MimesisGateLootModule.shouldKill). */
    static final double KILL_RANGE = 2_000;
    /** Boxes within this of the living freighter (MimesisCollectorModule). */
    static final double BOX_RANGE = 2_500;
    /** The fight's circle kept toward the freighter beyond this (score: −(distance − 1 600)·10). */
    static final double KEEP_NEAR = 1_600;
    /** The current target's bonus: within this, or locked. */
    static final double CURRENT_NEAR = 600;
    /** NPCs not shooting the ship are fought at three quarters of their radius. */
    static final double CALM_RADIUS = 0.75;
    /** The jump is pressed this close to a portal. */
    static final double JUMP_RANGE = 450;
    /** The escort's entry is the type-54 portal this near (10 500, 6 500) — ζ's portal of the same type stands elsewhere. */
    static final double ENTRY_NEAR = 1_500;
    /** The fight ability: NPCs this near the ship are counted (AstraBot «Min npc to use ability»). */
    static final double CROWD_NEAR = 800;
    /** An item asked for: not again this soon. */
    static final long ITEM_ASK_NS = 3_000_000_000L;
    /** A jump that left the ship on x-1 this long: pressed again; this many times — the entry is given up this hour. */
    static final long ENTRY_RETRY_NS = 15_000_000_000L;
    static final int ENTRY_TRIES = 4;

    /** EMAA-20 and its repeatable recipe (10 000 for 1 500 Byte Remnant and 15 000 uridium). */
    public static final String EMAA = "ammunition_laser_emaa-20";
    public static final String EMAA_RECIPE = "recipe_blueprint-emaa-20-10k-repeat";
    static final String LEECH = Attacker.LEECH;
    static final List<String> SOLACE = List.of("ability_solace-plus_nano-cluster-repairer-plus", "ability_solace");
    /** Solaris first (AstraBot «Use Solaris+ ability»), then the ship's other fight abilities. */
    static final List<String> CROWD_ABILITIES = crowdAbilities();

    private static List<String> crowdAbilities() {
        List<String> out = new java.util.ArrayList<>(List.of("ability_solaris-plus_incinerate-plus",
                "ability_solaris_incinerate"));
        for (String a : Attacker.FIGHT_ABILITIES) {
            if (!out.contains(a) && !SOLACE.contains(a)) {
                out.add(a);
            }
        }
        return List.copyOf(out);
    }

    private final BotContext ctx;
    /** The module's view: the config with the Mimesis rules it does not list, EMAA-20 on the Mimesis. */
    private final BotContext mctx;
    private final Attacker attacker;
    private final BoxPicker boxes;
    /** AstraBot: Kill &amp; Collect on the working map between the runs (the keys drop there). */
    private final KillModule farm;
    private String status = "";
    private String told = "";

    /** In an escort map: since when, the run's number, the freighter last seen. */
    private boolean inRun;
    private long runSince;
    private int runs;
    private int keysAtStart = -1;
    private double[] freighterAt;
    private long freighterSeen;
    private boolean wasFar;
    private boolean guarding;
    /** The entry: the window this run was made in (or given up), the jump pressed, the tries. */
    private boolean windowDone;
    private long pressedAt = Long.MIN_VALUE / 2;
    private long pressAt;
    private int tries;
    private final Map<String, Long> askedItem = new HashMap<>();
    private long restWakeMs;

    public MimesisModule(BotContext ctx) {
        this.ctx = ctx;
        this.mctx = new BotContext(ctx.world(), this::config, ctx.actions(), ctx.nav(), ctx.traveler(), ctx.maps(),
                ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(), ctx.ships());
        this.attacker = new Attacker(mctx);
        this.boxes = new BoxPicker(mctx);
        this.farm = new KillModule(mctx, true, true);
    }

    // ---- the NPCs ----

    /**
     * A Mimesis name as letters: the game writes them with digits ({@code -=[ Obscured M1mes1s ]=-},
     * {@code Mim3si5 Turret}) — {@code obscured mimesis}.
     */
    static String plain(String name) {
        if (name == null) {
            return "";
        }
        return BotConfig.bare(name).replace('1', 'i').replace('3', 'e').replace('5', 's').replace('0', 'o');
    }

    static boolean mimesis(String name) {
        return plain(name).contains("mimesis");
    }

    static boolean freighter(WorldSnapshot.Ship s) {
        return s.npc() && plain(s.name()).contains("freighter");
    }

    /** One NPC of the tables: its radius (0 = DarkBot's recommended), priority, flags, kill. */
    record Def(int radius, int priority, boolean kill, Set<String> flags) {
    }

    private static Def def(int radius, int priority, BotConfig.NpcFlag... flags) {
        Set<String> f = new java.util.TreeSet<>();
        for (BotConfig.NpcFlag x : flags) {
            f.add(x.name());
        }
        return new Def(radius, priority, true, Set.copyOf(f));
    }

    /**
     * The escort maps' NPCs: SharedPlugin's {@code MimesisMutinyGate} table ({@code NpcParam(radius, priority, flags)};
     * «stick to target» = {@code HOLD_TARGET}); the freighter never shot.
     */
    static final Map<String, Def> ESCORT = escortTable();
    /**
     * The Mimesis of the ordinary maps (the keys): the user's DarkBot profile {@code MIMESIS.json} — Terror and Reflector
     * first and whoever hit them (their boxes go to the three best by damage), Cloning, then the small ones.
     */
    static final Map<String, Def> FARM = farmTable();

    private static Map<String, Def> escortTable() {
        Map<String, Def> m = new LinkedHashMap<>();
        BotConfig.NpcFlag hold = BotConfig.NpcFlag.HOLD_TARGET;
        m.put("medic mimesis", def(600, -90, hold));
        m.put("obscured mimesis", def(650, -80, hold));
        m.put("mirror mimesis", def(650, -80, hold));
        m.put("marker mimesis", def(650, -80, hold));
        m.put("sniper mimesis", def(600, -70, hold));
        m.put("piercing mimesis", def(600, -60, hold));
        m.put("hounding mimesis", def(600, -50, hold));
        m.put("inspirit mimesis", def(600, -30, hold));
        m.put("hardy mimesis", def(600, -10, hold));
        m.put("raider mimesis", def(600, 0));
        m.put("assailant mimesis", def(600, 0));
        m.put("warhead", def(560, 10));
        m.put("seeker rocket", def(560, 20));
        m.put("mimesis turret", def(560, 50, BotConfig.NpcFlag.NO_CIRCLE));
        m.put("em freighter", new Def(560, 100, false, Set.of(BotConfig.NpcFlag.NO_CIRCLE.name(),
                BotConfig.NpcFlag.PASSIVE.name())));
        return Map.copyOf(m);
    }

    private static Map<String, Def> farmTable() {
        Map<String, Def> m = new LinkedHashMap<>();
        m.put("terror mimesis", def(0, -50, BotConfig.NpcFlag.IGNORE_OWNERSHIP, BotConfig.NpcFlag.IGNORE_ATTACKED));
        m.put("reflector mimesis", def(0, -50, BotConfig.NpcFlag.IGNORE_OWNERSHIP, BotConfig.NpcFlag.IGNORE_ATTACKED));
        m.put("cloning mimesis", def(0, -4));
        m.put("hexor mimesis", def(0, 0));
        m.put("raging mimesis", def(0, 1));
        m.put("kamikaze mimesis", def(0, 2));
        m.put("cloned mimesis", def(0, 3));
        return Map.copyOf(m);
    }

    /** The table's entry of a name: the exact one, else one the name holds (SharedPlugin matches by {@code contains}). */
    static Def tableRule(String name, boolean escortMap) {
        String p = plain(name);
        Map<String, Def> t = escortMap ? ESCORT : FARM;
        Def d = t.get(p);
        if (d != null) {
            return d;
        }
        for (Map.Entry<String, Def> e : t.entrySet()) {
            if (p.contains(e.getKey())) {
                return e.getValue();
            }
        }
        return null;
    }

    private BotConfig cachedBase;
    private String cachedKey = "";
    private BotConfig cached;

    /**
     * The config the module fights by: the Mimesis in sight the config does not list get the tables' rule; EMAA-20 on
     * every Mimesis while the item bar has {@code emaa.min} of it; in an escort map the NPCs not shooting the ship at
     * three quarters of their radius (DonorPlugin). The config's own rules first.
     */
    public BotConfig config() {
        BotConfig base = ctx.config().get();
        WorldSnapshot w = ctx.world().get();
        int map = w.hero().mapId();
        boolean escortMap = GateCatalog.escort(map);
        JsonNode s = settings(base);
        JsonNode em = s.path("emaa");
        WorldSnapshot.Item stock = w.items().get(EMAA);
        boolean emaa = on(em, "on", true) && stock != null && stock.counter() >= em.path("min").asInt(1_000);
        java.util.TreeMap<String, Boolean> names = new java.util.TreeMap<>();
        int hero = w.hero().id();
        for (WorldSnapshot.Ship sh : w.ships().values()) {
            if (sh.npc() && sh.name() != null && (mimesis(sh.name()) || tableRule(sh.name(), escortMap) != null)) {
                boolean shoots = sh.attackTargetId() == hero || w.hero().attackers().contains(sh.id());
                names.merge(sh.name(), shoots, Boolean::logicalOr);
            }
        }
        // SharedPlugin's isStickToTarget: a Mirror under 3 000 000 of max HP is a clone — not held as the target
        WorldSnapshot.Ship cur = w.ships().get(attacker.targetId());
        String clone = cur != null && clone(cur) ? cur.name() : "";
        String key = map + "|" + emaa + "|" + clone + "|" + names;
        synchronized (this) {
            if (base == cachedBase && key.equals(cachedKey)) {
                return cached;
            }
            Map<String, BotConfig.NpcRule> npcs = new LinkedHashMap<>(base.npcs());
            for (Map.Entry<String, Boolean> e : names.entrySet()) {
                String n = e.getKey();
                BotConfig.NpcRule own = base.npc(n);
                BotConfig.NpcRule r = own;
                if (r == null) {
                    Def d = tableRule(n, escortMap);
                    if (d == null) {
                        continue;
                    }
                    r = new BotConfig.NpcRule(d.radius(), d.priority(), d.kill(), d.flags(),
                            escortMap ? Set.copyOf(GateCatalog.ESCORT_MAPS) : Set.of(), null);
                }
                if (n.equals(clone) && r.has(BotConfig.NpcFlag.HOLD_TARGET)) {
                    Set<String> f = new java.util.TreeSet<>(r.flags());
                    f.remove(BotConfig.NpcFlag.HOLD_TARGET.name());
                    r = new BotConfig.NpcRule(r.radius(), r.priority(), r.kill(), Set.copyOf(f), r.maps(), r.ammo(), r.sab(),
                            r.rocket(), r.launcher(), r.move(), r.formation());
                }
                if (emaa && mimesis(n) && r.kill()) {
                    r = new BotConfig.NpcRule(r.radius(), r.priority(), r.kill(), r.flags(), r.maps(), EMAA, r.sab(),
                            r.rocket(), r.launcher(), r.move(), r.formation());
                }
                if (escortMap && !e.getValue() && r.kill()) {
                    double radius = r.radius() > 0 ? r.radius() : Targets.shownRadius(n, 0);
                    r = new BotConfig.NpcRule(radius * CALM_RADIUS, r.priority(), r.kill(), r.flags(), r.maps(), r.ammo(),
                            r.sab(), r.rocket(), r.launcher(), r.move(), r.formation());
                }
                if (r != own) {
                    npcs.put(own != null ? ownKey(base, n) : n, r);
                }
            }
            cached = base.withNpcs(npcs);
            cachedBase = base;
            cachedKey = key;
            return cached;
        }
    }

    /** SharedPlugin: the Mirror's clones look the same, their max HP is under 3 000 000 (the real one 3 000 000). */
    static final double MIRROR_REAL_HP = 3_000_000;

    static boolean clone(WorldSnapshot.Ship s) {
        return plain(s.name()).contains("mirror") && s.health().hpMax() > 0 && s.health().hpMax() < MIRROR_REAL_HP;
    }

    /** The key the config lists a name under (its own spelling, matched by the bare name). */
    private static String ownKey(BotConfig c, String name) {
        if (c.npcs().containsKey(name)) {
            return name;
        }
        String b = BotConfig.bare(name);
        return c.npcs().keySet().stream().filter(k -> BotConfig.bare(k).equals(b)).findFirst().orElse(name);
    }

    static JsonNode settings(BotConfig c) {
        JsonNode s = c.modules().get(KEY);
        return s != null ? s : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    private JsonNode settings() {
        return settings(ctx.config().get());
    }

    /** The craft list's EMAA-20 rule (CraftWatch reads it): a batch while fewer than {@code craftEmaa.below} are left. */
    public static BotConfig.CraftRule craftRule(BotConfig c) {
        JsonNode cr = settings(c).path("craftEmaa");
        if (!on(cr, "on", false)) {
            return null;
        }
        return new BotConfig.CraftRule(cr.path("recipe").asText(EMAA_RECIPE), EMAA, Math.max(1,
                cr.path("below").asInt(10_000)), 1);
    }

    // ---- BotModule ----

    @Override
    public String status() {
        return status;
    }

    @Override
    public void useRefresh(Runnable refresh) {
        farm.useRefresh(refresh);
    }

    private boolean inEscort() {
        return GateCatalog.escort(ctx.world().get().hero().mapId());
    }

    /** In an escort map — that map; the entry due — the company's x-1; else the working map (farm) or x-1 (wait). */
    @Override
    public int workingMap() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (GateCatalog.escort(h.mapId())) {
            return h.mapId();
        }
        if (active(w) || !"farm".equals(between())) {
            return homeMap(h);
        }
        return ctx.config().get().general().workingMap();
    }

    private int homeMap(WorldSnapshot.Hero h) {
        orbit.world.maps.MapCatalog.MapInfo home = h.factionId() > 0 ? ctx.maps().byName(h.factionId() + "-1") : null;
        return home != null ? home.id() : h.mapId();
    }

    private String between() {
        String b = settings().path("between").asText("farm").toLowerCase(java.util.Locale.ROOT);
        return b.equals("wait") || b.equals("logout") ? b : "farm";
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0 || !inEscort() && farm.busy();
    }

    @Override
    public int fightTarget() {
        return inEscort() ? attacker.targetId() : farm.fightTarget();
    }

    @Override
    public double targetHp() {
        if (!inEscort()) {
            return farm.targetHp();
        }
        WorldSnapshot.Ship t = ctx.world().get().ships().get(attacker.targetId());
        return t != null && t.health().known() ? t.health().hpPercent() : -1;
    }

    /** In the escort the players about are the company's own: nothing to keep off. */
    @Override
    public boolean holdsGround() {
        return inEscort() || farm.holdsGround();
    }

    @Override
    public boolean rushing() {
        return !inEscort() && farm.rushing();
    }

    @Override
    public void travelling() {
        farm.travelling();
    }

    @Override
    public int petLocator() {
        return inEscort() || active(ctx.world().get()) ? 0 : farm.petLocator();
    }

    /** No trip away amid a run, nor shortly before one. */
    @Override
    public boolean calm() {
        WorldSnapshot w = ctx.world().get();
        return !busy() && !inEscort() && !active(w);
    }

    @Override
    public boolean freeFor(long ms) {
        if (!calm()) {
            return false;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Escort e = w.gates().escort();
        if (!e.timesKnown()) {
            return false;
        }
        double next = e.left(e.nextSpawn(), ctx.now());
        return next - startTravel() > ms / 1000.0;
    }

    @Override
    public void pause() {
        if (repairedHere) {
            repairedHere = false;
            return;
        }
        attacker.clear();
        boxes.clear();
        farm.pause();
    }

    @Override
    public void resumed() {
        farm.resumed();
    }

    /**
     * PikaPika's PET: HP Link while the ship is in a fight, its hull below {@code hpLink.heroBelow} and the pet's at
     * least {@code hpLink.petMin}; else the farm's (its NPC rules' HP Link).
     */
    @Override
    public int petGear() {
        WorldSnapshot w = ctx.world().get();
        JsonNode l = settings().path("hpLink");
        if (on(l, "on", true)) {
            double hero = w.hero().health().hpPercent();
            double pet = w.pet().health() == null ? -1 : w.pet().health().hpPercent();
            boolean fighting = inEscort() ? attacker.targetId() != 0 : farm.fightTarget() != 0;
            if (fighting && hero >= 0 && hero < pct(l, "heroBelow", 70) && pet >= pct(l, "petMin", 70)) {
                return orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK;
            }
        }
        return inEscort() ? -1 : farm.petGear();
    }

    /**
     * Out of the game till shortly before the next freighter ({@code between = logout}, DonorPlugin's «pause and
     * wait»): woken {@code startTravelSeconds} and two minutes for the login before it.
     */
    @Override
    public Rest restRequest() {
        if (!"logout".equals(between()) || inEscort()) {
            return null;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Escort e = w.gates().escort();
        if (!e.timesKnown() || active(w) || e.left(e.gateClose(), ctx.now()) > 0 && !windowDone) {
            return null;
        }
        double away = e.left(e.nextSpawn(), ctx.now()) - startTravel() - 120;
        if (away < 300) {
            return null;
        }
        restWakeMs = System.currentTimeMillis() + (long) (away * 1000);
        log.info("Мимесис: до следующего фрейтера {} мин — выхожу из игры, вернусь за {} с до него",
                Math.round(away / 60), startTravel() + 120);
        long wake = restWakeMs;
        return new Rest("Мимесис: жду следующего фрейтера", () -> System.currentTimeMillis() >= wake,
                (long) (away * 1000) + 60_000);
    }

    // ---- the time ----

    private long startTravel() {
        return Math.max(0, settings().path("startTravelSeconds").asLong(180));
    }

    /** The entry open now: the server's {@code timeToGateClose}, else the computer's clock (DonorPlugin: HH:00–HH:05). */
    boolean open(WorldSnapshot w) {
        WorldSnapshot.Escort e = w.gates().escort();
        if (e.timesKnown()) {
            return e.left(e.gateClose(), ctx.now()) > 2;
        }
        java.time.LocalTime t = java.time.LocalTime.now();
        return t.getMinute() * 60 + t.getSecond() < 300;
    }

    /** Seconds to the next freighter ({@code -1} unknown: the clock's hour then). */
    double toSpawn(WorldSnapshot w) {
        WorldSnapshot.Escort e = w.gates().escort();
        if (e.timesKnown()) {
            return e.left(e.nextSpawn(), ctx.now());
        }
        java.time.LocalTime t = java.time.LocalTime.now();
        return 3600 - (t.getMinute() * 60 + t.getSecond());
    }

    /** The keys enough for a run (none told yet: tried — the server says). */
    boolean keysEnough(WorldSnapshot w) {
        int keys = w.gates().escort().keys();
        return keys < 0 || keys >= Math.max(1, settings().path("minKeys").asInt(1));
    }

    /** DonorPlugin's «not inactive»: keys, and the entry open (not made this hour) or the freighter within the travel. */
    boolean active(WorldSnapshot w) {
        if (!keysEnough(w)) {
            return false;
        }
        boolean open = open(w);
        if (!open) {
            windowDone = false;
        }
        return open && !windowDone || !open && toSpawn(w) <= startTravel();
    }

    // ---- the tick ----

    @Override
    public void tick() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (w.jumpingTo() != 0) {
            status = "Прыжок";
            return;
        }
        if (GateCatalog.escort(h.mapId())) {
            escort(w);
            return;
        }
        if (inRun) {
            runOver(w);
        }
        attacker.setAbilityPlan(null);
        if (active(w)) {
            entry(w);
            return;
        }
        tries = 0;
        String b = between();
        String keys = keysText(w);
        if ("farm".equals(b)) {
            farm.tick();
            status = "Мимесис: фарм ключей" + keys + nextText(w) + " · " + farm.status();
            return;
        }
        ctx.nav().tick();
        status = "Мимесис: жду фрейтера" + keys + nextText(w);
        tellOnce("Мимесис: жду фрейтера" + keys + ("logout".equals(b) ? " (выход из игры, когда до него далеко)" : ""));
    }

    private String keysText(WorldSnapshot w) {
        int k = w.gates().escort().keys();
        return k >= 0 ? " (ключей " + k + ")" : "";
    }

    private String nextText(WorldSnapshot w) {
        double s = toSpawn(w);
        return s > 0 ? ", фрейтер через " + (long) (s / 60) + " мин" : "";
    }

    // ---- the entry ----

    /** On x-1 to the escort's portal; in once the server opens the entry. */
    private void entry(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (h.mapId() != homeMap(h)) {
            status = "Мимесис: лечу на " + ctx.maps().info(homeMap(h)).name() + " ко входу";
            tellOnce(status);
            ctx.nav().tick();
            return;
        }
        WorldSnapshot.Portal p = w.portals().values().stream().filter(x -> x.designId() == GateCatalog.ESCORT_PORTAL)
                .filter(x -> Math.hypot(x.x() - GateCatalog.ESCORT_PORTAL_X, x.y() - GateCatalog.ESCORT_PORTAL_Y) <= ENTRY_NEAR)
                .findFirst().orElse(null);
        double px = p != null ? p.x() : GateCatalog.ESCORT_PORTAL_X;
        double py = p != null ? p.y() : GateCatalog.ESCORT_PORTAL_Y;
        double dist = Math.hypot(px - h.x(), py - h.y());
        boolean open = open(w);
        if (open && p != null && w.activatable().containsKey(p.id()) && dist <= JUMP_RANGE
                && now - pressedAt > ENTRY_RETRY_NS) {
            if (pressAt == 0) {
                pressAt = now + ctx.react(200, 1_000);
            }
            if (now >= pressAt) {
                if (++tries > ENTRY_TRIES) {
                    windowDone = true;
                    log.warn("Мимесис: в портал эскорта не пускает ({} попыток) — жду следующего фрейтера", ENTRY_TRIES);
                    return;
                }
                keysAtStart = w.gates().escort().keys();
                ctx.actions().jump();
                pressedAt = now;
                pressAt = 0;
                log.info("Мимесис: прыгаю в портал эскорта (ключей {})", keysAtStart);
            }
        }
        ctx.ship().want(ctx.config().get().general().travel());
        // to the portal; waiting — beside it, not on it
        double near = open ? 50 : 250;
        if (dist > near && (ctx.nav().idle() || ctx.nav().destination().x() != px || ctx.nav().destination().y() != py)) {
            ctx.nav().moveTo(px, py, true);
        }
        ctx.nav().tick();
        status = open ? "Мимесис: вход открыт — в портал" + keysText(w)
                : "Мимесис: жду у портала, фрейтер через " + (long) toSpawn(w) + " с" + keysText(w);
        tellOnce(open ? "Мимесис: вход открыт — лечу в портал" : "Мимесис: у входа на x-1 — жду фрейтера");
    }

    // ---- the run ----

    private void runStarted(WorldSnapshot w) {
        inRun = true;
        runSince = ctx.now();
        runs++;
        windowDone = true;
        tries = 0;
        wasFar = false;
        freighterAt = null;
        log.info("Мимесис: рейс {} начат — {} (ключей {})", runs, ctx.maps().info(w.hero().mapId()).name(),
                w.gates().escort().keys());
    }

    private void runOver(WorldSnapshot w) {
        inRun = false;
        WorldSnapshot.Escort e = w.gates().escort();
        log.info("Мимесис: рейс {} окончен за {} мин — очки за рейс {}, всего {}", runs,
                (ctx.now() - runSince) / 60_000_000_000L, e.runScore(), e.totalScore());
        attacker.clear();
        boxes.clear();
        attacker.gateStyle(null);
        attacker.setAbilityPlan(null);
        guarding = false;
    }

    /** In an escort map: DonorPlugin's tick. */
    private void escort(WorldSnapshot w) {
        if (!inRun) {
            runStarted(w);
        }
        BotConfig c = config();
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        abilityPlan();
        WorldSnapshot.Ship fr = w.ships().values().stream().filter(MimesisModule::freighter).findFirst().orElse(null);
        if (fr != null) {
            freighterAt = new double[] {fr.x(), fr.y()};
            freighterSeen = now;
        }
        boolean portals = !w.portals().isEmpty();
        boolean alive = fr != null && !portals;
        WorldSnapshot.Ship t = pick(c, w, alive ? fr : null);
        boolean box = boxNear(w, alive ? fr : null);
        // the freighter gone, the portal up: out once nothing is left (or the hull is low)
        if (portals && fr == null && shouldLeave(w, t, box)) {
            leave(w);
            return;
        }
        if (fr != null && !portals) {
            guard(c, w, fr, t, box);
            return;
        }
        // the freighter out of sight (a moment), or dead: the fight and the boxes as after its end
        attacker.gateStyle(null);
        JsonNode after = settings().path("after");
        if (t != null && (fr != null || !on(after, "ignoreNpc", false))) {
            fight(w, t);
            return;
        }
        if (box && !on(after, "ignoreBoxes", false) && boxes.tickAnyType(h.x(), h.y(), -1)) {
            status = "Мимесис: подбираю " + boxes.currentName();
            return;
        }
        if (freighterAt != null && fr == null && !portals) {
            // the server tells only what is near: back to where the freighter was
            ctx.ship().want(c.general().roam());
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - freighterAt[0],
                    ctx.nav().destination().y() - freighterAt[1]) > 200) {
                ctx.nav().moveTo(freighterAt[0], freighterAt[1], false);
            }
            ctx.nav().tick();
            status = "Мимесис: ищу фрейтер";
            return;
        }
        ctx.nav().tick();
        status = portals ? "Мимесис: рейс окончен, жду" : "Мимесис: жду фрейтер";
    }

    /** DonorPlugin's shouldLeave: the hull below {@code after.stayHp}, or no NPC (or ignored) and no box (or ignored). */
    private boolean shouldLeave(WorldSnapshot w, WorldSnapshot.Ship t, boolean box) {
        JsonNode after = settings().path("after");
        double hp = w.hero().health().hpPercent();
        if (hp >= 0 && hp < pct(after, "stayHp", 80)) {
            return true;
        }
        return (t == null || on(after, "ignoreNpc", false)) && (!box || on(after, "ignoreBoxes", false));
    }

    private void leave(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Portal p = w.portals().values().stream()
                .min(Comparator.<WorldSnapshot.Portal>comparingInt(x -> x.designId() == GateCatalog.HOME_PORTAL ? 0 : 1)
                        .thenComparingDouble(x -> Math.hypot(x.x() - h.x(), x.y() - h.y())))
                .orElse(null);
        if (p == null) {
            return;
        }
        attacker.clear();
        boxes.clear();
        long now = ctx.now();
        double dist = Math.hypot(p.x() - h.x(), p.y() - h.y());
        if (w.activatable().containsKey(p.id()) && dist <= JUMP_RANGE && now - pressedAt > 3_000_000_000L) {
            if (pressAt == 0) {
                pressAt = now + ctx.react(200, 1_000);
            }
            if (now >= pressAt) {
                ctx.actions().jump();
                pressedAt = now;
                pressAt = 0;
                tellOnce("Мимесис: фрейтер довезён — выхожу порталом");
            }
        }
        ctx.ship().want(ctx.config().get().general().travel());
        if (dist > 100 && (ctx.nav().idle() || ctx.nav().destination().x() != p.x() || ctx.nav().destination().y() != p.y())) {
            ctx.nav().moveTo(p.x(), p.y(), true);
        }
        ctx.nav().tick();
        status = "Мимесис: выхожу порталом";
    }

    /**
     * DonorPlugin's escort: the leash, the orbit round the freighter, back from far off before anything else; else the
     * fight (within 2 000 of it) and the boxes (within 2 500).
     */
    private void guard(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship fr, WorldSnapshot.Ship t, boolean box) {
        WorldSnapshot.Hero h = w.hero();
        // DonorPlugin: «npcs > 1» — some NPC besides the freighter in sight
        boolean enemies = w.ships().values().stream().anyMatch(s -> s.npc() && !freighter(s));
        double leash = box ? LEASH_BOX : enemies ? LEASH_ENEMIES : LEASH_IDLE;
        double dx;
        double dy;
        if (ctx.nav().idle()) {
            dx = h.x();
            dy = h.y();
        } else {
            dx = ctx.nav().destination().x();
            dy = ctx.nav().destination().y();
        }
        double d = Math.hypot(dx - fr.x(), dy - fr.y());
        double heroD = Math.hypot(h.x() - fr.x(), h.y() - fr.y());
        boolean idle = t == null && !box;
        if (!wasFar && heroD > leash && d > leash) {
            wasFar = true;
        } else if (wasFar && heroD <= BACK_WITHIN) {
            wasFar = false;
        }
        if (d > leash || idle && d < IDLE_NEAR || idle && ctx.nav().idle() || wasFar && d > BACK_WITHIN) {
            orbit(fr, h, enemies);
        }
        if (idle || wasFar) {
            if (attacker.targetId() != 0 && wasFar) {
                attacker.clear();
            }
            ctx.ship().want(c.general().roam());
            ctx.nav().tick();
            guarding = true;
            status = wasFar ? "Мимесис: назад к фрейтеру" : "Мимесис: охраняю фрейтер" + score(w);
            return;
        }
        guarding = false;
        attacker.gateStyle(new double[] {fr.x(), fr.y()}, KEEP_NEAR);
        if (t != null) {
            fight(w, t);
            return;
        }
        if (boxes.tickAnyType(fr.x(), fr.y(), BOX_RANGE)) {
            status = "Мимесис: подбираю " + boxes.currentName() + score(w);
            return;
        }
        ctx.nav().tick();
        status = "Мимесис: охраняю фрейтер" + score(w);
    }

    /** A point of the orbit: the freighter → ship angle ± 0.05 (with no enemies anywhere round), 350–900 off it. */
    private void orbit(WorldSnapshot.Ship fr, WorldSnapshot.Hero h, boolean enemies) {
        double a = Math.atan2(h.y() - fr.y(), h.x() - fr.x()) + (ctx.random().nextBoolean() ? 0.05 : -0.05)
                + (enemies ? 0 : ctx.random().nextDouble() * 6);
        double r = ORBIT_MIN + ctx.random().nextDouble() * ORBIT_SPREAD;
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(h.mapId());
        double x = Math.max(100, Math.min(m.width() - 100, fr.x() + Math.cos(a) * r));
        double y = Math.max(100, Math.min(m.height() - 100, fr.y() + Math.sin(a) * r));
        ctx.nav().moveTo(x, y, false);
    }

    private void fight(WorldSnapshot w, WorldSnapshot.Ship t) {
        WorldSnapshot.Hero h = w.hero();
        attacker.target(t);
        if (boxes.busy() || boxNearShip(w)) {
            if (boxes.tickNearInFight(h.x(), h.y(), GateModule.FIGHT_BOX_RADIUS)) {
                attacker.tick(false);
                status = "Мимесис: бой, подбираю " + boxes.currentName() + score(w);
                return;
            }
        }
        attacker.tick(true);
        abilities(w);
        status = "Мимесис: бой — " + t.name() + score(w);
    }

    private static boolean boxNearShip(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        return w.collectables().values().stream()
                .anyMatch(b -> Math.hypot(b.x() - h.x(), b.y() - h.y()) <= GateModule.FIGHT_BOX_RADIUS);
    }

    private static String score(WorldSnapshot w) {
        WorldSnapshot.Escort e = w.gates().escort();
        return e.scoreNanos() != 0 ? " · очки " + e.runScore() : "";
    }

    /** A box within 2 500 of the living freighter, or anywhere in sight after it. */
    private static boolean boxNear(WorldSnapshot w, WorldSnapshot.Ship fr) {
        return w.collectables().values().stream().filter(b -> b.kind() == WorldSnapshot.CollectableKind.BOX)
                .anyMatch(b -> fr == null || Math.hypot(b.x() - fr.x(), b.y() - fr.y()) <= BOX_RANGE);
    }

    /**
     * MimesisGateLootModule: not the freighter, within 2 000 of it while it lives; the list's priority (the current
     * target ahead by 20 − HP·10 when within 600 or locked), then those shooting the freighter, those nobody else has,
     * the lower HP, the nearer. A target the ship could not finish is left (the attacker's give-up).
     */
    WorldSnapshot.Ship pick(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship fr) {
        WorldSnapshot.Hero h = w.hero();
        int map = h.mapId();
        int cur = attacker.targetId();
        List<WorldSnapshot.Ship> all = w.ships().values().stream()
                .filter(s -> s.npc() && !s.cloaked() && !freighter(s) && !attacker.gaveUp(s.id()))
                .filter(s -> {
                    BotConfig.NpcRule r = Targets.rule(c, s, map);
                    return r != null && r.kill();
                })
                .filter(s -> fr == null || Math.hypot(s.x() - fr.x(), s.y() - fr.y()) <= KILL_RANGE)
                .filter(s -> !Targets.passiveIdle(c, w, s))
                .toList();
        if (all.isEmpty()) {
            return null;
        }
        int frId = fr != null ? fr.id() : 0;
        Comparator<WorldSnapshot.Ship> order = Comparator.<WorldSnapshot.Ship>comparingDouble(s -> {
                    double p = Targets.rule(c, s, map).priority();
                    if (s.id() == cur && (s.distanceTo(h.x(), h.y()) <= CURRENT_NEAR || h.attackTargetId() == s.id())) {
                        double hp = s.health().known() ? s.health().hpPercent() : 1;
                        p -= 20 - hp * 10;
                    }
                    return p;
                })
                .thenComparingInt(s -> frId != 0 && s.attackTargetId() == frId ? 0 : 1)
                .thenComparingInt(s -> s.lockedByOther() ? 1 : 0)
                .thenComparingDouble(s -> s.health().known() ? s.health().hpPercent() : 1)
                .thenComparingDouble(s -> s.distanceTo(h.x(), h.y()));
        return all.stream().min(order).orElse(null);
    }

    /**
     * The repair in the run (SharedPlugin's way in a gate — no shelter but the exit): round the target at 2 000 in the
     * repair mode, shooting on; no target — the orbit round the freighter.
     */
    @Override
    public boolean repairTick() {
        WorldSnapshot w = ctx.world().get();
        if (!GateCatalog.escort(w.hero().mapId())) {
            return false;
        }
        repairedHere = true;
        BotConfig c = config();
        WorldSnapshot.Ship fr = w.ships().values().stream().filter(MimesisModule::freighter).findFirst().orElse(null);
        WorldSnapshot.Ship t = pick(c, w, fr);
        if (t != null) {
            attacker.gateStyle(fr != null ? new double[] {fr.x(), fr.y()} : null, KEEP_NEAR);
            attacker.target(t);
            attacker.repairCircle(ctx.config().get().safety().repairMode());
            attacker.tick(true);
            status = "Мимесис: ремонт — кружу вокруг " + t.name() + " на 2000";
        } else if (fr != null) {
            WorldSnapshot.Hero h = w.hero();
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - fr.x(), ctx.nav().destination().y() - fr.y())
                    > LEASH_IDLE + ORBIT_SPREAD) {
                orbit(fr, h, true);
            }
            ctx.nav().tick();
            status = "Мимесис: ремонт у фрейтера";
        } else {
            ctx.nav().stop();
            status = "Мимесис: ремонт на месте";
        }
        return true;
    }

    private boolean repairedHere;

    // ---- AstraBot's automation in the run ----

    /** EMP-01 and ISH-01 by the hull (AstraBot useEmp / empPercent 50, useIsh / ishPercent 45). */
    private void abilityPlan() {
        JsonNode s = settings();
        JsonNode emp = s.path("emp");
        JsonNode ish = s.path("ish");
        attacker.setAbilityPlan(new Attacker.AbilityPlan(on(emp, "on", true), pct(emp, "below", 50), on(ish, "on", true),
                pct(ish, "below", 45)));
    }

    /**
     * In the fight: the Energy Leech below {@code leech.below} (AstraBot «Energy leech percent» 30), the ship's fight
     * ability (Solaris+ first) with {@code ability.minNpc} NPCs about (AstraBot «Min npc to use ability» 5), the Solace's
     * heal below {@code solace.below}. Each only when the game shows it ready.
     */
    private void abilities(WorldSnapshot w) {
        JsonNode s = settings();
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        double hp = h.health().hpPercent();
        if (hp < 0 || !attacker.shooting()) {
            return;
        }
        JsonNode leech = s.path("leech");
        if (on(leech, "on", true) && hp < pct(leech, "below", 30) && use(w, List.of(LEECH), now, "энергетическая пиявка")) {
            return;
        }
        JsonNode solace = s.path("solace");
        if (on(solace, "on", true) && hp < pct(solace, "below", 60) && use(w, SOLACE, now, "лечение Solace")) {
            return;
        }
        JsonNode ab = s.path("ability");
        if (on(ab, "on", true)) {
            long crowd = w.ships().values().stream().filter(x -> x.npc() && !freighter(x)
                    && Math.hypot(x.x() - h.x(), x.y() - h.y()) <= CROWD_NEAR).count();
            if (crowd >= Math.max(1, ab.path("minNpc").asInt(5))) {
                use(w, CROWD_ABILITIES, now, "способность корабля, NPC рядом " + crowd);
            }
        }
    }

    private boolean use(WorldSnapshot w, List<String> ids, long now, String what) {
        for (String id : ids) {
            Long asked = askedItem.get(id);
            WorldSnapshot.Item i = w.items().get(id);
            if (asked != null && now - asked < ITEM_ASK_NS || i == null || !i.available() || i.blocked()
                    || i.remainingMs(now) != 0 || i.maxCounter() > 0 && i.counter() <= 0) {
                continue;
            }
            log.info("Мимесис: {} — {}", what, orbit.config.ItemCatalog.friendly(id));
            ctx.actions().useItem(id);
            askedItem.put(id, now);
            return true;
        }
        return false;
    }

    // ---- helpers ----

    static boolean on(JsonNode n, String key, boolean def) {
        JsonNode v = n.path(key);
        return v.isBoolean() ? v.asBoolean() : def;
    }

    /** A per cent of the settings as a share (0–100 → 0–1). */
    static double pct(JsonNode n, String key, double def) {
        JsonNode v = n.path(key);
        double p = v.isNumber() ? v.asDouble() : def;
        return Math.max(0, Math.min(100, p)) / 100.0;
    }

    private void show(String s) {
        status = s;
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
}
