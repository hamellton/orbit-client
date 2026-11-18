package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.bot.run.GroupWatch;
import orbit.config.BotConfig;
import orbit.config.GateCatalog;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The LoW group gate (docs/LOW.md) as DarkBot's SharedPlugin {@code LowGate} plays it, with KekkaReloaded's waiting for
 * the group, the community's «leave 3 Vagrants» and the user's group of own accounts (2026-11-18: «в точности как у
 * даркбот, но с фишками оставлять вагрантов 3 шт; три аккаунта приглашают друг друга, лидером всегда один; лидер сам
 * проходит, а группа после захода выходит; порт пройден — вся группа снова идёт в лоу порт»).
 *
 * <p>Roles by the config's {@code leader} (the name in the game): the leader gathers the group on x-3 at the portal,
 * jumps first (the server queues him for an instance and beams the group in), fights the waves, charges the relays in
 * SharedPlugin's order (only when no NPC is left; relay 1 not before everybody is in), kills the Century Falcon leaving
 * {@code leaveVagrants} of its escort alive, and after «completed» (the server puts him on x-1) gathers again. The others
 * come to the portal when the leader gathers, accept his invitations, jump after him (the group packets tell the map of each member; the «raid gate stage» there is Hades', not LoW's); in the {@code solo} mode they leave
 * the group once the gate is on and get out (there is no way out but death: they stop shooting at an NPC till destroyed,
 * revive at the base and log out till the leader gathers again); {@code anchor} — the same without leaving the group (a
 * logged-out member keeps the group alive for the leader's return after a death); {@code together} — everybody fights
 * in the group. The accounts talk through {@link LowBoard} (one program), the game's group packets tell the rest (who is
 * on which map, in the gate, what the leader shoots).
 *
 * <p>Settings ({@code modules.low}): {@code leader}, {@code members} [names], {@code mode} {@code solo|anchor|together},
 * {@code minGroup} (3), {@code gatherMinutes} (10), {@code waitForAll} (true), {@code waitAtRelay} (0 = none),
 * {@code leaveVagrants} (3), {@code chaseMax} (1 500), {@code followLeaderTarget} (true), {@code boxRadius} (1 500),
 * {@code lifeSaver} {@code {emp, empBelow, ish, ishBelow, ability}} on the Century Falcon, {@code mines}
 * {@code {on, item, minNpcs}}, {@code helpersExit} {@code radiation|npc|stay}, {@code restMinutes} (60).
 */
public final class LowModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(LowModule.class);

    /** The jump is pressed this close to the portal (the server offers it up to ~500). */
    static final double JUMP_RANGE = 450;
    /** The group waits this near the portal; a member farther is «not there yet». */
    static final double AT_PORTAL = 1_200;
    static final double GATHER_RADIUS = 2_500;
    /** The gate's middle (the map is 21 000 × 13 100): the circling's reference, Kekka's relay choice point is (12 000, 6 000). */
    static final double[] CENTRE = {10_500, 6_550};
    static final double[] RELAY_POINT = {12_000, 6_000};
    /** «Online» or everybody in: the helpers wait this much more before leaving (the server's word may lag). */
    static final long START_GRACE_MS = 10_000;
    /** Waiting at a relay for the others: at most this long. */
    static final long RELAY_WAIT_MS = 90_000;
    static final double RELAY_NEAR = 1_500;
    /** A helper getting out stands this near an NPC without shooting (EBG's suicide brake). */
    static final double SUICIDE_NEAR = 800;
    /** A late member still jumps this long after the leader (the server gives the group 5 minutes). */
    static final long LATE_JUMP_MS = 4 * 60_000L;
    static final double FIGHT_BOX_RADIUS = 400;
    static final double SWITCH_OFFSET = 100;
    static final double LASER_REACH = 650;
    static final long LOCK_MS = 2_000;
    static final long INVITE_LOG_MS = 60_000;
    /** A return into the gate after a death is tried this long. */
    static final long REENTER_MS = 3 * 60_000L;
    /** Queued for an instance: the jump is not pressed again this long (the server beams the group in itself). */
    static final long QUEUE_MS = 5 * 60_000L;

    enum Role { LEADER, MEMBER, HELPER }

    enum Mode { SOLO, ANCHOR, TOGETHER }

    private final BotContext ctx;
    /** The module's view of the context: the config with the gate's NPC rules and the group's rules. */
    private final BotContext gctx;
    private final Attacker attacker;
    private final BoxPicker boxes;
    private final GroupWatch group;
    private String status = "";
    private String told = "";

    private boolean inside;
    private long enteredAt;
    private long gatherSince;
    private long startSeenAt;
    private boolean startedTold;
    private boolean leftGroup;
    private boolean suiciding;
    private boolean completedTold;
    private int waitedRelay;
    private long relayWaitSince;
    private long pressAt;
    private long pressedAt = Long.MIN_VALUE / 2;
    private long waitTold = Long.MIN_VALUE / 2;
    private double uridiumAtEntry;
    private int deathsAtEntry;
    private int runs;
    private volatile Rest rest;
    private boolean gateLeftByDeath;

    private BotConfig cachedBase;
    private Set<String> cachedNames = Set.of();
    private int cachedMap;
    private BotConfig cached;

    public LowModule(BotContext ctx) {
        this.ctx = ctx;
        this.gctx = new BotContext(ctx.world(), this::config, ctx.actions(), ctx.nav(), ctx.traveler(), ctx.maps(),
                ctx.random(), ctx.clock(), ctx.repair(), ctx.ship(), ctx.dataDir(), ctx.stats(), ctx.ships());
        this.attacker = new Attacker(gctx);
        this.boxes = new BoxPicker(gctx);
        this.group = new GroupWatch(gctx);
    }

    // ---- settings ----

    JsonNode settings() {
        JsonNode s = ctx.config().get().modules().get(BotConfig.Module.LOW.key());
        return s != null ? s : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    String leader() {
        return settings().path("leader").asText("").strip();
    }

    List<String> members() {
        List<String> out = new ArrayList<>();
        for (JsonNode n : settings().path("members")) {
            String x = n.asText("").strip();
            if (!x.isEmpty() && !x.equalsIgnoreCase(leader())) {
                out.add(x);
            }
        }
        return out;
    }

    Mode mode() {
        return switch (settings().path("mode").asText("solo").toLowerCase(Locale.ROOT)) {
            case "anchor" -> Mode.ANCHOR;
            case "together" -> Mode.TOGETHER;
            default -> Mode.SOLO;
        };
    }

    Role role(WorldSnapshot w) {
        String me = w.hero().name();
        if (me != null && me.equalsIgnoreCase(leader())) {
            return Role.LEADER;
        }
        return mode() == Mode.TOGETHER ? Role.MEMBER : Role.HELPER;
    }

    LowBoard board() {
        return LowBoard.of(leader());
    }

    static boolean on(JsonNode s, String key, boolean def) {
        JsonNode n = s.path(key);
        return n.isBoolean() ? n.asBoolean() : def;
    }

    static double num(JsonNode s, String key, double def) {
        JsonNode n = s.path(key);
        return n.isNumber() ? n.asDouble() : def;
    }

    int leaveVagrants() {
        return (int) num(settings(), "leaveVagrants", 3);
    }

    /** The company's x-3, where the portal stands. */
    int portalMap(WorldSnapshot w) {
        int f = w.hero().factionId();
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().byName((f >= 1 && f <= 3 ? f : 1) + "-3");
        return m != null ? m.id() : 0;
    }

    // ---- BotModule ----

    @Override
    public String status() {
        return status;
    }

    @Override
    public int workingMap() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (h.mapId() == GateCatalog.LOW_MAP) {
            return h.mapId();
        }
        if (rest != null) {
            return 0; // leaving the game: where the ship is
        }
        if (role(w) != Role.LEADER && !board().gathering() && !(board().gateActive(wall()) && w.group().exists())) {
            return 0; // nothing to do till the leader gathers: stay
        }
        return portalMap(w);
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0 || inside && !suiciding;
    }

    @Override
    public boolean calm() {
        return !inside && attacker.targetId() == 0;
    }

    @Override
    public double targetHp() {
        WorldSnapshot.Ship t = ctx.world().get().ships().get(attacker.targetId());
        return t != null && t.health().known() ? t.health().hpPercent() : -1;
    }

    @Override
    public int fightTarget() {
        return attacker.targetId();
    }

    @Override
    public Rest restRequest() {
        return rest;
    }

    @Override
    public int petGear() {
        if (suiciding) {
            return orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_PASSIVE;
        }
        return attacker.fighting(BotConfig.NpcFlag.HP_LINK) ? orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK : -1;
    }

    @Override
    public void pause() {
        if (repairedHere) {
            repairedHere = false;
            return;
        }
        attacker.clear();
        boxes.clear();
    }

    private boolean repairedHere;

    /** In the gate the repair is the module's: round the target at 2 000 shooting on (SharedPlugin); a helper on the way out: none. */
    @Override
    public boolean repairTick() {
        WorldSnapshot w = ctx.world().get();
        if (w.hero().mapId() != GateCatalog.LOW_MAP) {
            return false;
        }
        if (suiciding) {
            status = "LoW: выхожу — жду гибели у NPC";
            return true;
        }
        repairedHere = true;
        attacker.gateStyle(CENTRE);
        WorldSnapshot.Ship t = pick(config(), w, false);
        if (t != null) {
            attacker.target(t);
            attacker.repairCircle(ctx.config().get().safety().repairMode());
            attacker.tick(true);
            status = "LoW: ремонт — кружу вокруг " + t.name() + " на 2000";
        } else {
            ctx.nav().stop();
            status = "LoW: ремонт на месте";
        }
        return true;
    }

    /**
     * The config as the module fights by: the gate's NPCs in sight the config does not list get the catalog's rules
     * (SharedPlugin's radii, the Falcon without circling, the relay at 400); the ability flags of the life saver on the
     * Falcon; the group's rules — the leader invites the listed, everybody accepts them, the lead goes to the leader.
     */
    public BotConfig config() {
        BotConfig base = ctx.config().get();
        WorldSnapshot w = ctx.world().get();
        int map = w.hero().mapId();
        Set<String> names = new java.util.TreeSet<>();
        GateCatalog.Gate gate = GateCatalog.byKey("low");
        if (map == GateCatalog.LOW_MAP) {
            for (WorldSnapshot.Ship s : w.ships().values()) {
                if (s.npc() && s.name() != null && GateCatalog.defaultRule(gate, s.name()) != null) {
                    names.add(s.name());
                }
            }
        }
        synchronized (this) {
            if (base == cachedBase && map == cachedMap && names.equals(cachedNames)) {
                return cached;
            }
            JsonNode s = base.modules().getOrDefault(BotConfig.Module.LOW.key(),
                    com.fasterxml.jackson.databind.node.MissingNode.getInstance());
            Map<String, BotConfig.NpcRule> npcs = new java.util.LinkedHashMap<>(base.npcs());
            for (String n : names) {
                BotConfig.NpcRule own = base.npc(n);
                BotConfig.NpcRule r = own != null && own.appliesOn(map) ? own : GateCatalog.defaultRule(gate, n);
                if ("century falcon".equals(BotConfig.bare(n)) && on(s.path("lifeSaver"), "ability", true)) {
                    Set<String> f = new java.util.TreeSet<>(r.flags());
                    f.add(BotConfig.NpcFlag.USE_ABILITY.name());
                    r = new BotConfig.NpcRule(r.radius(), r.priority(), r.kill(), f, r.maps(), r.ammo(), r.sab(),
                            r.rocket(), r.launcher(), r.move(), r.formation());
                }
                npcs.put(n, r);
            }
            BotConfig c = base.withNpcs(npcs);
            Set<String> players = new java.util.TreeSet<>(members());
            if (!leader().isEmpty()) {
                players.add(leader());
            }
            String me = w.hero().name() == null ? "" : w.hero().name();
            boolean lead = me.equalsIgnoreCase(leader());
            c = c.withGroup(new BotConfig.GroupRules(BotConfig.Accept.LIST, false, true, players, false, false,
                    lead ? "" : leader(), Boolean.TRUE));
            if (map == GateCatalog.LOW_MAP) {
                BotConfig.General g = c.general();
                BotConfig.ShipMode fight = g.offensive();
                c = c.withGeneral(new BotConfig.General(g.workingMap(), fight, fight, g.run(), g.runFromEnemies(),
                        g.runFromEnemiesInSight(), g.rememberEnemiesSec(), g.roamSequential(), g.onlyKillPreferred(),
                        false, false, g.avoidMines(), g.baseRadius()));
            }
            cached = c;
            cachedBase = base;
            cachedMap = map;
            cachedNames = names;
            return cached;
        }
    }

    @Override
    public void tick() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (w.jumpingTo() != 0) {
            status = "Прыжок";
            return;
        }
        if (leader().isEmpty()) {
            show("LoW: в настройках не указан лидер группы");
            ctx.nav().tick();
            return;
        }
        if (h.mapId() == GateCatalog.LOW_MAP) {
            inGate(w);
        } else {
            outside(w);
        }
    }

    private long wall() {
        return System.currentTimeMillis();
    }

    // ---- out of the gate ----

    private void outside(WorldSnapshot w) {
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        if (inside) {
            left(w);
        }
        Role role = role(w);
        LowBoard b = board();
        if (rest != null) {
            show("LoW: выхожу из игры до следующего захода");
            ctx.nav().tick();
            return;
        }
        if (role == Role.LEADER) {
            leaderOutside(w, b, now);
            return;
        }
        // a member or helper
        boolean active = b.gateActive(wall()) && (!gateLeftByDeath || reentryOpen(w, now));
        if (gateLeftByDeath && role == Role.HELPER && (b.phase() == LowBoard.Phase.STARTED || b.phase() == LowBoard.Phase.DONE
                || !active || !w.group().exists())) {
            // out by death on purpose (the gate on without us): the rest till the leader gathers again
            gateLeftByDeath = false;
            askRest(b);
            return;
        }
        if (active && w.group().exists() && gateLeftByDeath) {
            // killed before the start (a helper) or in the together mode: back in while the gate is on and we are in the group
            if (reenterSince == 0) {
                reenterSince = now;
            }
            if (h.mapId() != portalMap(w)) {
                show("LoW: погиб во вратах — лечу обратно на x-3");
                ctx.nav().tick();
                return;
            }
            goPortal(w, now, "LoW: возвращаюсь во врата после гибели");
            return;
        }
        if (b.phase() == LowBoard.Phase.STARTED && !active) {
            // the leader's run went stale with no word: back to waiting
            b.set(LowBoard.Phase.IDLE, wall());
        }
        if (!b.gathering() && !(b.phase() == LowBoard.Phase.STARTED && wall() - b.startedAt() < LATE_JUMP_MS)) {
            group.tick(); // invitations still answered: the leader may be a step ahead of the board
            b.helper(h.name(), "жду лидера");
            ctx.nav().tick();
            show(b.phase() == LowBoard.Phase.IDLE ? "LoW: жду лидера (" + leader() + " не собирает группу)"
                    : "LoW: жду, пока лидер пройдёт врата");
            return;
        }
        if (h.mapId() != portalMap(w)) {
            show("LoW: лечу на x-3 к порталу");
            ctx.nav().tick();
            return;
        }
        group.tick();
        WorldSnapshot.Portal p = portal(w);
        if (p == null) {
            show("LoW: портала (тип 34) на " + ctx.maps().info(h.mapId()).name() + " не видно");
            ctx.nav().tick();
            return;
        }
        WorldSnapshot.Member lead = member(w, leader());
        boolean leaderIn = lead != null && lead.mapId() == GateCatalog.LOW_MAP;
        boolean follow = leaderIn || b.phase() == LowBoard.Phase.ENTERING
                || b.phase() == LowBoard.Phase.STARTED && wall() - b.startedAt() < LATE_JUMP_MS;
        if (follow && w.group().exists()) {
            b.helper(h.name(), "прыгаю");
            if (jumpInto(w, p, now)) {
                show("LoW: лидер вошёл — прыгаю за ним");
            }
            return;
        }
        b.helper(h.name(), w.group().exists() ? "у портала, в группе" : "у портала, жду приглашения");
        waitAt(w, p.x(), p.y());
        show(w.group().exists() ? "LoW: у портала, жду прыжка лидера" : "LoW: у портала, жду приглашения в группу");
    }

    private void leaderOutside(WorldSnapshot w, LowBoard b, long now) {
        WorldSnapshot.Hero h = w.hero();
        boolean active = b.gateActive(wall()) && gateLeftByDeath && reentryOpen(w, now);
        if (active && w.group().exists()) {
            // killed in the gate: back in while it is on and the group stands (the client, 2026: «If three or more group
            // members are still on the map, you can jump back to it»)
            if (reenterSince == 0) {
                reenterSince = now;
            }
            if (h.mapId() != portalMap(w)) {
                show("LoW: погиб во вратах — лечу обратно на x-3");
                ctx.nav().tick();
                return;
            }
            goPortal(w, now, "LoW: возвращаюсь во врата после гибели (" + onMap(w) + " в группе ещё там)");
            return;
        }
        reenterSince = 0;
        if (b.phase() != LowBoard.Phase.GATHER && b.phase() != LowBoard.Phase.ENTERING) {
            b.set(LowBoard.Phase.GATHER, wall());
            gatherSince = now;
            gateLeftByDeath = false;
        }
        if (h.mapId() != portalMap(w)) {
            show("LoW: лечу на x-3 собирать группу");
            ctx.nav().tick();
            return;
        }
        group.tick();
        WorldSnapshot.Portal p = portal(w);
        if (p == null) {
            show("LoW: портала (тип 34) на " + ctx.maps().info(h.mapId()).name() + " не видно");
            ctx.nav().tick();
            return;
        }
        if (gatherSince == 0) {
            gatherSince = now;
        }
        int minGroup = (int) num(settings(), "minGroup", 3);
        long gatherMs = (long) (num(settings(), "gatherMinutes", 10) * 60_000);
        WorldSnapshot.Group g = w.group();
        List<String> wanted = members();
        int present = 0;
        int inGroup = 0;
        for (String n : wanted) {
            WorldSnapshot.Member m = member(w, n);
            if (m == null) {
                continue;
            }
            inGroup++;
            if (m.mapId() == h.mapId() && Math.hypot(m.x() - p.x(), m.y() - p.y()) <= GATHER_RADIUS) {
                present++;
            }
        }
        int size = g.exists() ? g.members().size() : 1;
        boolean all = inGroup == wanted.size() && present == wanted.size();
        boolean timeout = now - gatherSince > gatherMs * 1_000_000;
        boolean ready = g.exists() && size >= minGroup && (all || timeout);
        if (ready) {
            if (b.phase() != LowBoard.Phase.ENTERING) {
                log.info("LoW: группа собрана ({} в группе, {} из {} у портала{}) — прыгаю первым", size, present,
                        wanted.size(), timeout && !all ? ", ждать дольше не стал" : "");
                b.set(LowBoard.Phase.ENTERING, wall());
            }
            if (jumpInto(w, p, now)) {
                show("LoW: прыгаю во врата первым (группа " + size + ")");
            }
            return;
        }
        waitAt(w, p.x(), p.y());
        if (now - waitTold > INVITE_LOG_MS * 1_000_000) {
            waitTold = now;
            if (g.exists() && size < minGroup) {
                log.info("LoW: в группе {} — во врата пускают от {}; жду остальных ({} из {} приглашены)", size, minGroup,
                        inGroup, wanted.size());
            }
        }
        show("LoW: собираю группу у портала — в группе " + (g.exists() ? size : 1) + ", у портала " + present + " из "
                + wanted.size());
    }

    private long reenterSince;

    /** Members of the group on the gate's map besides us (the group packets). */
    int onMap(WorldSnapshot w) {
        int n = 0;
        for (WorldSnapshot.Member m : w.group().members()) {
            if (m.id() != w.hero().id() && m.mapId() == GateCatalog.LOW_MAP) {
                n++;
            }
        }
        return n;
    }

    /**
     * A return after a death is still worth trying: the gate was not failed («Mission failed»), somebody of the group is
     * still on its map, and the try is younger than {@link #REENTER_MS} (the server's word on the way back is a text only).
     */
    boolean reentryOpen(WorldSnapshot w, long now) {
        WorldSnapshot.Low low = w.gates().low();
        if (low.failedNanos() > enteredAt || low.completedNanos() > enteredAt) {
            return false;
        }
        if (reenterSince != 0 && ms(now - reenterSince) > REENTER_MS) {
            log.info("LoW: за {} мин вернуться во врата не вышло — собираю группу заново", REENTER_MS / 60_000);
            return false;
        }
        return onMap(w) > 0;
    }

    private void goPortal(WorldSnapshot w, long now, String what) {
        WorldSnapshot.Portal p = portal(w);
        if (p == null) {
            show(what + " — портала не видно");
            ctx.nav().tick();
            return;
        }
        if (jumpInto(w, p, now)) {
            show(what);
        }
    }

    /** The portal of the gate on this map (type 34; the nearest when the map had several). */
    WorldSnapshot.Portal portal(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        return w.portals().values().stream().filter(p -> p.designId() == GateCatalog.LOW_PORTAL)
                .min(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - h.x(), p.y() - h.y())))
                .orElse(null);
    }

    WorldSnapshot.Member member(WorldSnapshot w, String name) {
        for (WorldSnapshot.Member m : w.group().members()) {
            if (m.name() != null && m.name().equalsIgnoreCase(name)) {
                return m;
            }
        }
        return null;
    }

    /** Near the portal (not on it: the jump is not offered meanwhile), standing. */
    private void waitAt(WorldSnapshot w, int x, int y) {
        WorldSnapshot.Hero h = w.hero();
        double d = Math.hypot(x - h.x(), y - h.y());
        if (d > AT_PORTAL) {
            double tx = x + (h.x() - x) / d * (AT_PORTAL - 300);
            double ty = y + (h.y() - y) / d * (AT_PORTAL - 300);
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - tx, ctx.nav().destination().y() - ty) > 200) {
                ctx.ship().want(ctx.config().get().general().travel());
                ctx.nav().moveTo(tx, ty, false);
            }
        } else if (!ctx.nav().idle()) {
            ctx.nav().stop();
        }
        ctx.nav().tick();
    }

    /** Flies onto the portal and presses jump once the server offers it (as GateModule); false while queued (status set). */
    private boolean jumpInto(WorldSnapshot w, WorldSnapshot.Portal p, long now) {
        WorldSnapshot.Hero h = w.hero();
        double dist = Math.hypot(p.x() - h.x(), p.y() - h.y());
        WorldSnapshot.Low low = w.gates().low();
        boolean queued = low.queueNanos() > 0 && low.queueNanos() > pressedAt && ms(now - low.queueNanos()) < QUEUE_MS;
        if (queued) {
            // the server has us in the queue for an instance: no pressing again, it beams us in when found
            status = "LoW: в очереди во врата" + (low.queuePosition() > 0 ? ", место " + low.queuePosition() : "");
            if (!ctx.nav().idle()) {
                ctx.nav().stop();
            }
            ctx.nav().tick();
            return false;
        }
        if (w.activatable().containsKey(p.id()) && dist <= JUMP_RANGE && ms(now - pressedAt) > 3_000) {
            if (pressAt == 0) {
                pressAt = now + ctx.react(200, 1_000);
            }
            if (now >= pressAt) {
                ctx.actions().jump();
                pressedAt = now;
                pressAt = 0;
                log.info("LoW: прыжок в портал (тип {}) на {}/{}", p.designId(), p.x() / 100, p.y() / 100);
            }
        }
        if (dist > 100 && (ctx.nav().idle() || ctx.nav().destination().x() != p.x() || ctx.nav().destination().y() != p.y())) {
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.nav().moveTo(p.x(), p.y(), true);
        }
        ctx.nav().tick();
        return true;
    }

    private void askRest(LowBoard b) {
        long max = (long) (num(settings(), "restMinutes", 60) * 60_000);
        String why = "LoW: врата идут без меня — жду следующего захода";
        rest = new Rest(why, () -> b.phase() == LowBoard.Phase.GATHER || b.phase() == LowBoard.Phase.IDLE
                && wall() - b.phaseAt() > 60_000, max);
        b.helper(ctx.world().get().hero().name(), "вышел до следующего захода");
        log.info("{} (не дольше {} мин)", why, max / 60_000);
    }

    // ---- in the gate ----

    private void inGate(WorldSnapshot w) {
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        LowBoard b = board();
        Role role = role(w);
        if (!inside) {
            inside = true;
            enteredAt = now;
            startSeenAt = 0;
            startedTold = false;
            leftGroup = false;
            suiciding = false;
            completedTold = false;
            waitedRelay = 0;
            relayWaitSince = 0;
            gateLeftByDeath = false;
            uridiumAtEntry = w.rewards().uridium();
            falconSeen = false;
            deathsAtEntry = ctx.stats().deaths.get();
            attacker.setAbilityPlan(null);
            log.info("LoW: во вратах ({}), роль {}", role == Role.LEADER ? "лидер" : role == Role.MEMBER ? "участник" : "помощник",
                    mode().name().toLowerCase(Locale.ROOT));
            if (role == Role.LEADER && b.phase() != LowBoard.Phase.STARTED) {
                b.set(LowBoard.Phase.ENTERING, wall()); // back after a death: the gate stays «started»
            }
        }
        WorldSnapshot.Low low = w.gates().low();
        boolean online = low.onlineNanos() > enteredAt;
        boolean completed = low.completedNanos() > enteredAt;
        boolean allIn = allIn(w);
        boolean started = online || allIn || b.phase() == LowBoard.Phase.STARTED;
        if (started && startSeenAt == 0) {
            startSeenAt = now;
        }
        if (role == Role.LEADER && started && !startedTold) {
            startedTold = true;
            b.set(LowBoard.Phase.STARTED, wall());
            log.info("LoW: врата запущены ({})", online ? "сервер: brought online" : "все участники внутри");
        }
        if (completed) {
            if (!completedTold) {
                completedTold = true;
                runs++;
                double got = w.rewards().uridium() - uridiumAtEntry;
                log.info("LoW: врата пройдены за {} мин (проход {}, +{} уридия)", ms(now - enteredAt) / 60_000, runs,
                        Math.round(got));
                if (role == Role.LEADER) {
                    b.runDone(wall());
                }
            }
            boxes.tick();
            status = "LoW: пройдены — жду переноса на x-1";
            return;
        }
        if (role == Role.HELPER && started && ms(now - startSeenAt) > START_GRACE_MS) {
            helperOut(w, b, now);
            return;
        }
        fight(w, role, started, now, low);
    }

    /** Everybody of the group is in the gate (the leader's word of the start when the server's text does not come). */
    boolean allIn(WorldSnapshot w) {
        WorldSnapshot.Group g = w.group();
        if (!g.exists()) {
            return false;
        }
        for (WorldSnapshot.Member m : g.members()) {
            if (m.id() == w.hero().id() || m.loggedOut()) {
                continue;
            }
            if (m.mapId() != GateCatalog.LOW_MAP) {
                return false;
            }
        }
        return true;
    }

    /**
     * A helper once the gate is on: out of the group (the solo mode), then out of the gate the only way there is — no
     * shooting, close to an NPC till the ship is destroyed; {@code helpersExit = stay}: stays and fights its own kills.
     */
    private void helperOut(WorldSnapshot w, LowBoard b, long now) {
        WorldSnapshot.Hero h = w.hero();
        if (mode() == Mode.SOLO && w.group().exists() && !leftGroup) {
            leftGroup = true;
            log.info("LoW: врата запущены — выхожу из группы, лидер проходит один");
            ctx.actions().leaveGroup();
            b.helper(h.name(), "вышел из группы");
            return;
        }
        String how = settings().path("helpersExit").asText("radiation").toLowerCase(Locale.ROOT);
        if ("stay".equals(how)) {
            fight(w, Role.HELPER, true, now, w.gates().low());
            return;
        }
        if (!suiciding) {
            suiciding = true;
            attacker.clear();
            attacker.setAbilityPlan(null);
            ctx.actions().stopAttack();
            log.info("LoW: ухожу из врат — стоп огонь, жду гибели в радиации или у NPC (выхода из врат нет, возрождение на базе)");
            b.helper(h.name(), "покидаю врата");
        }
        // Kentachi (2025–2026): «let the 2 alt accounts die in the radiation zone so they don't steal any alien rewards»
        double[] rad = "npc".equals(how) ? null : radiationPoint(w);
        if (rad != null) {
            double d = Math.hypot(rad[0] - h.x(), rad[1] - h.y());
            boolean inRad = orbit.world.maps.ZoneGeometry.inAny(w, orbit.protocol.gen.POITypeModule.POITYPE_RADIATION, h.x(), h.y());
            ctx.ship().want(ctx.config().get().general().run());
            if (d <= 150) {
                if (!ctx.nav().idle()) {
                    ctx.nav().stop();
                }
                ctx.nav().tick();
            } else if (inRad || d <= STRAIGHT_INTO_RADIATION) {
                // the last stretch: a straight click into the zone (the navigator keeps out of it and would stop at its edge)
                if (!ctx.nav().idle()) {
                    ctx.nav().stop();
                }
                if (ms(now - straightAt) > 3_000 && (!h.moving() || Math.hypot(h.targetX() - rad[0], h.targetY() - rad[1]) > 200)) {
                    straightAt = now;
                    ctx.actions().flyStraight((int) rad[0], (int) rad[1]);
                }
            } else {
                if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - rad[0], ctx.nav().destination().y() - rad[1]) > 200) {
                    ctx.nav().moveTo(rad[0], rad[1], true);
                }
                ctx.nav().tick();
            }
            status = "LoW: выхожу — жду гибели в радиации";
            return;
        }
        WorldSnapshot.Ship near = w.ships().values().stream().filter(WorldSnapshot.Ship::npc)
                .filter(s -> !World.RELAY_NAME.equals(s.name()))
                .min(java.util.Comparator.comparingDouble(s -> s.distanceTo(h.x(), h.y()))).orElse(null);
        if (near != null && near.distanceTo(h.x(), h.y()) > SUICIDE_NEAR) {
            ctx.ship().want(ctx.config().get().general().run());
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - near.x(), ctx.nav().destination().y() - near.y()) > 300) {
                ctx.nav().moveTo(near.x(), near.y(), false);
            }
        } else if (!ctx.nav().idle()) {
            ctx.nav().stop();
        }
        ctx.nav().tick();
        status = "LoW: выхожу — жду гибели у NPC";
    }

    /** This near the radiation point the ship is clicked straight in. */
    static final double STRAIGHT_INTO_RADIATION = 2_000;
    private long straightAt = Long.MIN_VALUE / 2;
    private double[] radiationTarget;
    private int radiationZones = -1;

    /**
     * The nearest point of the map inside a radiation zone the server laid out ({@code POITYPE_RADIATION}; the navigator
     * flies through danger zones when the goal lies in one), 400 into it; {@code null} when the map shows none. Sampled on
     * a 250 grid once per set of zones.
     */
    double[] radiationPoint(WorldSnapshot w) {
        int zones = (int) w.zones().values().stream().filter(z -> z.active()
                && z.type() == orbit.protocol.gen.POITypeModule.POITYPE_RADIATION).count();
        if (zones == 0) {
            return null;
        }
        if (zones == radiationZones && radiationTarget != null) {
            return radiationTarget;
        }
        radiationZones = zones;
        orbit.world.maps.MapCatalog.MapInfo map = ctx.maps().info(w.hero().mapId());
        WorldSnapshot.Hero h = w.hero();
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        int step = 250;
        for (int x = step / 2; x < map.width(); x += step) {
            for (int y = step / 2; y < map.height(); y += step) {
                if (!deepRadiation(w, x, y) || orbit.world.maps.ZoneGeometry.blocked(w, x, y)) {
                    continue;
                }
                double d = Math.hypot(x - h.x(), y - h.y());
                if (d < bestD) {
                    bestD = d;
                    best = new double[] {x, y};
                }
            }
        }
        radiationTarget = best;
        if (best != null) {
            log.info("LoW: ближайшая радиация у {}/{} (в {})", (int) best[0] / 100, (int) best[1] / 100, (int) bestD);
        }
        return best;
    }

    /** The point and four around it at 400 lie in radiation: well inside the zone, not on its edge. */
    private static boolean deepRadiation(WorldSnapshot w, double x, double y) {
        int r = orbit.protocol.gen.POITypeModule.POITYPE_RADIATION;
        return orbit.world.maps.ZoneGeometry.inAny(w, r, x, y) && orbit.world.maps.ZoneGeometry.inAny(w, r, x + 400, y)
                && orbit.world.maps.ZoneGeometry.inAny(w, r, x - 400, y) && orbit.world.maps.ZoneGeometry.inAny(w, r, x, y + 400)
                && orbit.world.maps.ZoneGeometry.inAny(w, r, x, y - 400);
    }

    private void fight(WorldSnapshot w, Role role, boolean started, long now, WorldSnapshot.Low low) {
        WorldSnapshot.Hero h = w.hero();
        BotConfig c = config();
        attacker.gateStyle(CENTRE);
        WorldSnapshot.Ship t = pick(c, w, started);
        if (t == null && attacker.targetId() != 0 && attacker.tick(true)) {
            status = "LoW: бой";
            return;
        }
        if (t != null && World.RELAY_NAME.equals(t.name()) && !relayAllowed(w, t, now)) {
            return; // waiting at the relay: the status is set
        }
        if (t != null) {
            boolean falcon = "century falcon".equals(BotConfig.bare(t.name()));
            attacker.setAbilityPlan(falcon ? falconPlan() : null);
            attacker.target(t);
            double tr = orbit.bot.mod.Targets.radius(c, t, h.mapId());
            if (boxes.busy() || !boxNearTarget(w, t, tr)) {
                if (boxes.tickNearInFight(h.x(), h.y(), FIGHT_BOX_RADIUS)) {
                    attacker.tick(false);
                    status = "LoW: бой, подбираю " + boxes.currentName();
                    return;
                }
            }
            mines(w, t, now);
            attacker.tick(true);
            status = World.RELAY_NAME.equals(t.name()) ? "LoW: заряжаю реле " + relayNumber(t.id())
                    : "LoW: бой — " + t.name() + (falcon ? vagrantsNote(w) : "") + relaysNote(low);
            return;
        }
        if (falconSeen && !falconPresent(w)) {
            // the boss out of sight, its escort left alive: to where it was last seen
            if (Math.hypot(falconX - h.x(), falconY - h.y()) > 500) {
                if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - falconX, ctx.nav().destination().y() - falconY) > 300) {
                    ctx.ship().want(c.general().roam());
                    ctx.nav().moveTo(falconX, falconY, false);
                }
                ctx.nav().tick();
                status = "LoW: Century Falcon не видно — лечу туда, где он был" + vagrantsNote(w);
                return;
            }
            falconSeen = false; // there and nothing: forget it, the usual fight again
        }
        double radius = num(settings(), "boxRadius", 1_500);
        if (boxes.tick(h.x(), h.y(), radius)) {
            status = "LoW: подбираю " + boxes.currentName();
            return;
        }
        ctx.ship().want(c.general().run());
        if (!ctx.nav().idle()) {
            ctx.nav().stop();
        }
        ctx.nav().tick();
        boolean relays = w.ships().values().stream().anyMatch(s -> World.RELAY_NAME.equals(s.name()));
        if (!started && relays && on(settings(), "waitForAll", true)) {
            status = "LoW: жду, пока все войдут (" + inGateCount(w) + " из " + groupSize(w) + "), реле не трогаю";
        } else {
            status = "LoW: жду волну" + relaysNote(low);
        }
    }

    private String relaysNote(WorldSnapshot.Low low) {
        return low.relays() > 0 ? " (реле " + Math.min(4, low.relays()) + "/4)" : "";
    }

    private String vagrantsNote(WorldSnapshot w) {
        long v = vagrants(w);
        int swarms = w.gates().low().swarms();
        return (v > 0 ? ", Vagrant рядом " + v + " (оставляю " + leaveVagrants() + ")" : "")
                + (swarms > 0 && w.gates().low().bossNanos() > enteredAt ? ", подкреплений " + swarms : "");
    }

    private int inGateCount(WorldSnapshot w) {
        int n = 0;
        for (WorldSnapshot.Member m : w.group().members()) {
            if (m.id() == w.hero().id() || m.mapId() == GateCatalog.LOW_MAP) {
                n++;
            }
        }
        return n;
    }

    private int groupSize(WorldSnapshot w) {
        return w.group().exists() ? w.group().members().size() : 1;
    }

    static int relayNumber(int id) {
        int n = id - GateCatalog.LOW_RELAY_BASE;
        return n >= 1 && n <= 4 ? n : 0;
    }

    long vagrants(WorldSnapshot w) {
        return w.ships().values().stream().filter(s -> s.npc() && "vagrant".equals(BotConfig.bare(s.name()))).count();
    }

    boolean falconPresent(WorldSnapshot w) {
        WorldSnapshot.Ship f = w.ships().values().stream().filter(s -> s.npc() && "century falcon".equals(BotConfig.bare(s.name())))
                .findFirst().orElse(null);
        if (f != null) {
            falconSeen = true;
            falconX = f.x();
            falconY = f.y();
        }
        return f != null;
    }

    /**
     * The Falcon seen this run (till the gate is over): out of sight it is still there — the Vagrants left are not killed
     * (a new swarm would come), the ship flies to where it was last seen. SharedPlugin dropped its own «no Vagrant at
     * the boss» on 2026-04-13 for a ship stuck at the gate (PR #128): this is the way out of that.
     */
    private boolean falconSeen;
    private int falconX;
    private int falconY;

    /**
     * The target (SharedPlugin's LowGate with Kekka's and the community's rules): the NPCs by priority and distance, the
     * current kept while shot at and within reach; the Vagrants let be once the Falcon is there and {@code leaveVagrants}
     * or fewer of them are left (else its escort comes again); no relay while an NPC lives; relay 1 not before everybody
     * is in ({@code waitForAll}); the relays in SharedPlugin's order (104, 102, 103, 101), unknown ids by Kekka's point;
     * a member shoots what the leader shoots ({@code followLeaderTarget}); a target run off farther than {@code chaseMax}
     * is let go for a nearer one.
     */
    WorldSnapshot.Ship pick(BotConfig c, WorldSnapshot w, boolean started) {
        WorldSnapshot.Hero h = w.hero();
        boolean falcon = falconPresent(w) || falconSeen;
        long vagrants = falcon ? vagrants(w) : 0;
        int leave = leaveVagrants();
        List<WorldSnapshot.Ship> npcs = new ArrayList<>();
        List<WorldSnapshot.Ship> relays = new ArrayList<>();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (!Targets.attackable(c, s, h.mapId()) || Targets.taken(c, w, s) || attacker.gaveUp(s.id())) {
                continue;
            }
            if (World.RELAY_NAME.equals(s.name())) {
                relays.add(s);
            } else if (falcon && vagrants <= leave && "vagrant".equals(BotConfig.bare(s.name()))) {
                continue; // the Falcon's escort left alive: no new swarm
            } else {
                npcs.add(s);
            }
        }
        if (npcs.isEmpty()) {
            if (relays.isEmpty() || !started && on(settings(), "waitForAll", true)) {
                return null;
            }
            for (int id : GateCatalog.LOW_RELAY_ORDER) {
                for (WorldSnapshot.Ship r : relays) {
                    if (r.id() == id) {
                        return r;
                    }
                }
            }
            return relays.stream().min(java.util.Comparator.comparingDouble(
                    r -> Math.hypot(r.x() - RELAY_POINT[0], r.y() - RELAY_POINT[1]))).orElse(null);
        }
        WorldSnapshot.Ship cur = w.ships().get(attacker.targetId());
        if (cur != null && !npcs.contains(cur)) {
            cur = null;
        }
        if (role(w) != Role.LEADER && on(settings(), "followLeaderTarget", true)) {
            WorldSnapshot.Ship lt = leaderTarget(w, npcs);
            if (lt != null) {
                return lt;
            }
        }
        if (falcon) {
            // Kekka's LoWModule with the boss about: the nearest (the current 100 nearer, the Falcon 400 nearer) — the
            // escort round the ship is thinned to {@code leaveVagrants}, the boss fought between
            final WorldSnapshot.Ship kept = cur;
            return npcs.stream().min(java.util.Comparator.comparingDouble(s -> s.distanceTo(h.x(), h.y())
                    + (kept != null && s.id() == kept.id() ? 0 : 100)
                    - ("century falcon".equals(BotConfig.bare(s.name())) ? 400 : 0))).orElse(null);
        }
        WorldSnapshot.Ship best = npcs.stream().min(java.util.Comparator
                        .<WorldSnapshot.Ship>comparingInt(s -> Targets.rule(c, s, h.mapId()).priority())
                        .thenComparingDouble(s -> s.distanceTo(h.x(), h.y()))
                        .thenComparingDouble(s -> s.health().known() ? s.health().hpPercent() : 1))
                .orElse(null);
        if (cur == null || best == null || cur.id() == best.id()) {
            return cur != null ? cur : best;
        }
        double chaseMax = num(settings(), "chaseMax", 1_500);
        double curD = cur.distanceTo(h.x(), h.y());
        if (chaseMax > 0 && curD > chaseMax && best.distanceTo(h.x(), h.y()) < chaseMax) {
            return best; // run off too far: a nearer one (AstraBot maxDistanceToChase)
        }
        boolean onIt = h.attackTargetId() == cur.id() || h.selectedId() == cur.id() || attacker.shooting()
                || attacker.targetAgeMs() < LOCK_MS;
        if (onIt && (curD < best.distanceTo(h.x(), h.y()) + SWITCH_OFFSET || curD <= LASER_REACH)) {
            return cur;
        }
        return best;
    }

    /** The NPC the leader has selected, among ours: the same name, the health alike (the packets carry no id). */
    WorldSnapshot.Ship leaderTarget(WorldSnapshot w, List<WorldSnapshot.Ship> npcs) {
        WorldSnapshot.Member lead = member(w, leader());
        if (lead == null || lead.targetName() == null || lead.targetName().isBlank()) {
            return null;
        }
        String bare = BotConfig.bare(lead.targetName());
        WorldSnapshot.Ship best = null;
        double bestDiff = Double.MAX_VALUE;
        for (WorldSnapshot.Ship s : npcs) {
            if (!bare.equals(BotConfig.bare(s.name()))) {
                continue;
            }
            double diff = lead.targetHealth().known() && s.health().known()
                    ? Math.abs(lead.targetHealth().hp() - s.health().hp()) + Math.abs(lead.targetHealth().shield() - s.health().shield())
                    : 1e9;
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    /**
     * {@code waitAtRelay} N: before relay N is charged the leader waits by it till the group's members in the gate are
     * within {@link #RELAY_NEAR} of it (they heal faster at the middle after each relay) or {@link #RELAY_WAIT_MS}.
     */
    private boolean relayAllowed(WorldSnapshot w, WorldSnapshot.Ship relay, long now) {
        int n = relayNumber(relay.id());
        int wait = (int) num(settings(), "waitAtRelay", 0);
        if (n == 0 || wait != n || waitedRelay == n) {
            return true;
        }
        if (relayWaitSince == 0) {
            relayWaitSince = now;
            attacker.clear();
            log.info("LoW: реле {} — жду у него участников группы перед зарядом", n);
        }
        boolean near = true;
        for (WorldSnapshot.Member m : w.group().members()) {
            if (m.id() == w.hero().id() || m.loggedOut() || m.mapId() != GateCatalog.LOW_MAP) {
                continue;
            }
            if (Math.hypot(m.x() - relay.x(), m.y() - relay.y()) > RELAY_NEAR) {
                near = false;
            }
        }
        if (near || ms(now - relayWaitSince) > RELAY_WAIT_MS) {
            waitedRelay = n;
            relayWaitSince = 0;
            log.info("LoW: реле {} — {}", n, near ? "все рядом, заряжаю" : "ждать дольше не стал, заряжаю");
            return true;
        }
        WorldSnapshot.Hero h = w.hero();
        if (Math.hypot(relay.x() - h.x(), relay.y() - h.y()) > 600) {
            if (ctx.nav().idle()) {
                ctx.nav().moveTo(relay.x(), relay.y(), false);
            }
        } else if (!ctx.nav().idle()) {
            ctx.nav().stop();
        }
        ctx.nav().tick();
        status = "LoW: жду участников у реле " + n;
        return false;
    }

    /** The life saver on the Century Falcon (Kekka's FALCON_LIFE_SAVER, AstraBot's useEmp/useIsh): EMP and ISH by the hull. */
    Attacker.AbilityPlan falconPlan() {
        JsonNode l = settings().path("lifeSaver");
        boolean emp = on(l, "emp", false);
        boolean ish = on(l, "ish", false);
        if (!emp && !ish) {
            return null;
        }
        double empBelow = num(l, "empBelow", 45) / 100.0;
        double ishBelow = num(l, "ishBelow", 40) / 100.0;
        return new Attacker.AbilityPlan(emp, empBelow, ish, ishBelow, 0, 0, 0, false, false, 0);
    }

    private long mineAt = Long.MIN_VALUE / 2;

    /**
     * Mines ({@code mines.on}, {@code item} — {@code ammunition_mine_ddm-01}…): one dropped when {@code minNpcs} or more NPCs
     * stand within 500 of the ship and the item is ready (the Falcon's swarm about the ship).
     */
    private void mines(WorldSnapshot w, WorldSnapshot.Ship target, long now) {
        JsonNode m = settings().path("mines");
        if (!on(m, "on", false) || ms(now - mineAt) < 15_000) {
            return;
        }
        String item = m.path("item").asText("ammunition_mine_ddm-01");
        WorldSnapshot.Item it = w.items().get(item);
        if (it == null || it.counter() <= 0 || it.timerState() == orbit.protocol.gen.TimerStateModule.TIMERSTATE_ACTIVE
                || it.timerState() == orbit.protocol.gen.TimerStateModule.TIMERSTATE_COOLING_DOWN) {
            return;
        }
        WorldSnapshot.Hero h = w.hero();
        long near = w.ships().values().stream().filter(s -> s.npc() && !World.RELAY_NAME.equals(s.name())
                && s.distanceTo(h.x(), h.y()) <= 500).count();
        if (near >= (int) num(m, "minNpcs", 4)) {
            mineAt = now;
            log.info("LoW: мина {} — рядом {} NPC", orbit.config.ItemCatalog.label(item), near);
            ctx.actions().useItem(item);
        }
    }

    private static boolean boxNearTarget(WorldSnapshot w, WorldSnapshot.Ship t, double radius) {
        for (WorldSnapshot.Collectable c : w.collectables().values()) {
            if (c.kind() == WorldSnapshot.CollectableKind.BOX && Math.hypot(c.x() - t.x(), c.y() - t.y()) <= radius) {
                return true;
            }
        }
        return false;
    }

    /** Out of the gate: completed (the server put the ship on x-1) or destroyed. */
    private void left(WorldSnapshot w) {
        inside = false;
        boolean died = ctx.stats().deaths.get() > deathsAtEntry;
        WorldSnapshot.Low low = w.gates().low();
        long inMs = ms(ctx.now() - enteredAt);
        // alive and out within 5 minutes with no start: the instance was cancelled (too few in the group), not completed
        boolean cancelled = low.cancelledNanos() > enteredAt
                || !died && low.completedNanos() <= enteredAt && low.onlineNanos() <= enteredAt && inMs < 5 * 60_000;
        boolean failed = low.failedNanos() > enteredAt;
        boolean done = low.completedNanos() > enteredAt || !died && !cancelled && !failed;
        gateLeftByDeath = died;
        reenterSince = 0;
        if (cancelled) {
            log.info("LoW: из врат перенесён через {} с без запуска — инстанс отменён (группа не собралась за 5 минут)", inMs / 1_000);
        } else if (failed) {
            log.info("LoW: сервер: «Mission failed» — врата провалены через {} мин", inMs / 60_000);
        }
        if (done && !completedTold && !died) {
            // the server's text did not come (or was not seen): alive and out of the gate — it is over
            runs++;
            log.info("LoW: из врат перенесён живым за {} мин — считаю пройденными (проход {})", ms(ctx.now() - enteredAt) / 60_000, runs);
            if (role(w) == Role.LEADER) {
                board().runDone(wall());
            }
        }
        String how = suiciding ? "ушёл гибелью" : died ? "гибель" : "перенос на x-1";
        attacker.setAbilityPlan(null);
        attacker.gateStyle(null);
        attacker.clear();
        boxes.clear();
        suiciding = false;
        pressAt = 0;
        gatherSince = 0;
        log.info("LoW: из врат вышел ({}){}", how, done ? " — пройдены" : "");
    }

    // ---- helpers ----

    private void show(String s) {
        status = s;
        if (!s.equals(told)) {
            told = s;
            log.info(s);
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
