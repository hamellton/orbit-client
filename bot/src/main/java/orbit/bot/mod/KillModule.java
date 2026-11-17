package orbit.bot.mod;

import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/**
 * KILL / KILL_COLLECT / COLLECT: NPCs by the config's list on the working map, boxes by its box list;
 * with nothing in sight it roams the map (the server only sends what is near).
 */
public final class KillModule implements BotModule {

    private final BotContext ctx;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(KillModule.class);
    private final boolean kill;
    private final boolean collect;
    private final Attacker attacker;
    private final BoxPicker boxes;
    private double[] roamTo;
    private long roamUntil;
    private String status = "";

    public KillModule(BotContext ctx, boolean kill, boolean collect) {
        this.ctx = ctx;
        this.kill = kill;
        this.collect = collect;
        this.attacker = new Attacker(ctx);
        this.boxes = new BoxPicker(ctx);
    }

    @Override
    public String status() {
        return status;
    }

    @Override
    public int workingMap() {
        return ctx.config().get().general().workingMap();
    }

    @Override
    public boolean busy() {
        return attacker.targetId() != 0;
    }

    @Override
    public int fightTarget() {
        return attacker.targetId();
    }

    /** A target flagged {@code RUSH} is fought, or the locator leads to one this very moment. */
    @Override
    public boolean rushing() {
        return attacker.fighting(BotConfig.NpcFlag.RUSH) || ctx.now() - leadRushAt < LEAD_RUSH_NS;
    }

    /** No keeping off offenders while rushing: the runner's circles wait for the target's death. */
    @Override
    public boolean holdsGround() {
        return rushing();
    }

    private long leadRushAt = Long.MIN_VALUE / 2;
    static final long LEAD_RUSH_NS = 1_500_000_000L;

    /** The NPC the locator looks for now is flagged {@code RUSH} in the list. */
    private boolean locatorRush(BotConfig c, WorldSnapshot w) {
        int npc = petLocator();
        if (npc == 0) {
            return false;
        }
        int map = w.hero().mapId();
        return c.npcs().entrySet().stream().anyMatch(e -> e.getValue().kill() && e.getValue().has(BotConfig.NpcFlag.RUSH)
                && e.getValue().appliesOn(map) && npcNumber(e.getKey()) == npc);
    }

    /** HP Link while an NPC flagged so is fought (AstraBot «HP Link»). */
    @Override
    public int petGear() {
        return attacker.fighting(BotConfig.NpcFlag.HP_LINK) ? orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK : -1;
    }

    @Override
    public void pause() {
        attacker.clear();
        boxes.clear();
        roamTo = null;
    }

    @Override
    public void tick() {
        BotConfig c = ctx.config().get();
        WorldSnapshot w = ctx.world().get();
        boolean dropAll = c.moduleSettings("kill").path("dropForHigher").asBoolean(false);
        if (kill && attacker.targetId() != 0) {
            // one of a higher priority came in sight (an invoke while an Impulse is fought): to it at once, unless the
            // one fought is nearly dead or shoots the ship (the user, 2026-11-13) — with «бросать всё» in any case
            WorldSnapshot.Ship now = w.ships().get(attacker.targetId());
            WorldSnapshot.Ship better = now == null ? null : pick(c, w);
            BotConfig.NpcRule nowRule = now == null ? null : Targets.rule(c, now, w.hero().mapId());
            if (better != null && better.id() != now.id() && nowRule != null
                    && Targets.rule(c, better, w.hero().mapId()).priority() < nowRule.priority()
                    && (dropAll || now.attackTargetId() != w.hero().id()
                    && !(now.health().known() && now.health().hpPercent() < 0.3))) {
                log.info("Вижу {} — важнее, чем {}: переключаюсь", better.name(), now.name());
                attacker.clear();
                attacker.target(better);
            }
            // the locator has found one of a higher priority out of sight: everything dropped, to it (the user,
            // 2026-11-17: «бросить всё и бежать к высшему приоритету в любом случае»)
            // (only when there is somewhere to fly: a point just found empty leaves the fight as it is)
            if (dropAll && now != null && nowRule != null && attacker.targetId() == now.id()
                    && locatorPriority(c, w) < nowRule.priority() && locatorFresh(w) && followLocator(c, w)) {
                log.info("Пеленгатор нашёл цель важнее, чем {}: бросаю бой и лечу", now.name());
                attacker.clear();
                return;
            }
        }
        if (collect && attacker.targetId() != 0 && fightBox(c, w)) {
            status = "Бой · подбираю " + boxes.currentName();
            return;
        }
        if (attacker.targetId() != 0 && attacker.tick(true)) {
            status = "Бой";
            return;
        }
        // IGNORE_BOXES counts in the fight only, as DarkBot's: once it is dead its boxes are taken like any (the user,
        // 2026-11-17: «1 в 1»)
        // no fight: any box in sight (DarkBot's collector); its radius only counts next to a target
        if (collect && boxes.tickAnywhere()) {
            status = "Подбираю " + boxes.currentName();
            return;
        }
        if (kill) {
            WorldSnapshot.Ship t = pick(c, w);
            // the locator has found one of a higher priority (the user, 2026-11-13: «почему бьёт импульсов, а не сразу
            // летит на инвока»): to it, past the lesser ones — unless one of them shoots the ship
            if (t != null && t.attackTargetId() != w.hero().id() && locatorPriority(c, w) < Targets.rule(c, t,
                    w.hero().mapId()).priority() && followLocator(c, w)) {
                return;
            }
            if (t != null) {
                roamTo = null;
                attacker.target(t);
                attacker.tick(true);
                status = "Бой";
                return;
            }
        }
        if (kill && followLocator(c, w)) {
            return;
        }
        roam(w);
    }

    /**
     * The best NPC of the list flagged {@code PET_LOCATOR} for this map (the user, 2026-11-13: the locator on Boss
     * Kristallon): the pet's enemy locator looks for it — also while one is in sight and fought (live 2026-11-17 on the
     * VPS: dropped then, the pet took the config's mode, «ищет Cubikon», blind to the next invoke for the fight and some
     * 5 s after it).
     */
    @Override
    public int petLocator() {
        BotConfig c = ctx.config().get();
        WorldSnapshot w = ctx.world().get();
        if (!kill || !c.pet().enabled()) {
            return 0;
        }
        int map = w.hero().mapId();
        return c.npcs().entrySet().stream()
                .filter(e -> e.getValue().kill() && e.getValue().has(BotConfig.NpcFlag.PET_LOCATOR)
                        && e.getValue().appliesOn(map))
                .filter(e -> !attacker.tooStrong(e.getKey()))
                .sorted(java.util.Comparator.comparingInt(e -> e.getValue().priority()))
                .mapToInt(e -> npcNumber(e.getKey())).filter(id -> id > 0).findFirst().orElse(0);
    }

    /** The pet's locator has a fresh point (not one just found empty): something to fly to. */
    private boolean locatorFresh(WorldSnapshot w) {
        WorldSnapshot.Pet pet = w.pet();
        return pet.active() && pet.gear() == orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR
                && pet.locatorWithin(w.nanos(), LEAD_FRESH_MS);
    }

    /** The list's priority of the NPC the locator looks for now, {@link Integer#MAX_VALUE} when it looks for none. */
    private int locatorPriority(BotConfig c, WorldSnapshot w) {
        int npc = petLocator();
        if (npc == 0) {
            return Integer.MAX_VALUE;
        }
        int map = w.hero().mapId();
        return c.npcs().entrySet().stream()
                .filter(e -> e.getValue().kill() && e.getValue().has(BotConfig.NpcFlag.PET_LOCATOR)
                        && e.getValue().appliesOn(map) && npcNumber(e.getKey()) == npc)
                .mapToInt(e -> e.getValue().priority()).min().orElse(Integer.MAX_VALUE);
    }

    /** The game's NPC number of a name the list keeps (its letters and digits), 0 when unknown. */
    static int npcNumber(String name) {
        String n = QuestTargets.norm(name);
        int id = orbit.world.maps.NpcCatalog.bundled().all().stream()
                .filter(npc -> QuestTargets.norm(npc.name()).equals(n))
                .mapToInt(orbit.world.maps.NpcCatalog.Npc::id).findFirst().orElse(0);
        return id != 0 ? id : BLACKLIGHT_NPCS.getOrDefault(n, 0);
    }

    /**
     * The Blacklight NPCs the client's game.xml leaves out, by AstraBot's lists (`npcId` in its NPC table, 2026-11-13):
     * the pet's locator needs their numbers.
     */
    /** An NPC's name by the game's number: the client's table, else the Blacklight ones by AstraBot's list. */
    public static String npcName(int id) {
        String n = orbit.world.maps.NpcCatalog.bundled().name(id);
        if (n != null) {
            return n;
        }
        return switch (id) {
            case 175 -> "Impulse II";
            case 176 -> "Attend IX";
            case 177 -> "Invoke XVI";
            case 266 -> "Strokelight Barrage";
            case 268 -> "Abide I";
            case 159 -> "Barrage Seeker Rocket";
            default -> null;
        };
    }

    static final java.util.Map<String, Integer> BLACKLIGHT_NPCS = java.util.Map.of(
            QuestTargets.norm("Impulse II"), 175, QuestTargets.norm("Attend IX"), 176,
            QuestTargets.norm("Invoke XVI"), 177, QuestTargets.norm("Strokelight Barrage"), 266,
            QuestTargets.norm("Abide I"), 268, QuestTargets.norm("Barrage Seeker Rocket"), 159);

    /** A locator's point is fresh this long; reached with nothing there, it is left this long. */
    static final long LEAD_FRESH_MS = 5_000;
    static final long LEAD_EMPTY_MS = 60_000;
    static final double LEAD_REACHED = 600;
    private int[] emptyLead;
    private long emptyLeadAt;
    private int leadFor;

    /** No target in sight: fly to where the pet's locator shows the wanted NPC. */
    private boolean followLocator(BotConfig c, WorldSnapshot w) {
        int target = petLocator();
        if (target == 0) {
            leadFor = 0;
            return false;
        }
        WorldSnapshot.Pet pet = w.pet();
        if (!pet.active() || pet.gear() != orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR
                || !pet.locatorWithin(w.nanos(), LEAD_FRESH_MS)) {
            return false;
        }
        long now = ctx.now();
        int[] lead = {pet.locatorX(), pet.locatorY()};
        if (emptyLead != null && now - emptyLeadAt < LEAD_EMPTY_MS * 1_000_000
                && Math.hypot(lead[0] - emptyLead[0], lead[1] - emptyLead[1]) < LEAD_REACHED) {
            return false;
        }
        WorldSnapshot.Hero h = w.hero();
        double dist = Math.hypot(lead[0] - h.x(), lead[1] - h.y());
        String name = npcName(target);
        if (dist < LEAD_REACHED) {
            log.info("Пеленгатор: долетел до {}/{}, но {} там нет — ищу дальше", lead[0] / 100, lead[1] / 100, name);
            emptyLead = lead;
            emptyLeadAt = now;
            return false;
        }
        if (leadFor != target) {
            leadFor = target;
            log.info("Пеленгатор нашёл {} на {}/{}, в {} от нас — лечу", name, lead[0] / 100, lead[1] / 100,
                    String.format("%,d", (int) dist).replace(',', ' '));
        }
        boolean rush = locatorRush(c, w);
        if (rush) {
            leadRushAt = now; // the run mode, and nothing turns the ship off its way to it
        }
        ctx.ship().want(rush ? c.general().run() : c.general().travel());
        if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - lead[0], ctx.nav().destination().y() - lead[1]) > 300) {
            ctx.nav().moveTo(lead[0], lead[1], false);
        }
        ctx.nav().tick();
        status = "Лечу к " + name + " по пеленгатору";
        return true;
    }

    /** DarkBot's Kill &amp; Collect leaves the boxes to a target this weak: it is finished first. */
    static final double FINISH_BELOW = 0.25;

    /**
     * DarkBot's Kill &amp; Collect in a fight ({@code LootNCollectorModule.tick}): a box within the collect radius of
     * the ship is taken while the target is shot, unless the target is under a quarter of its HP; for a target
     * flagged {@code IGNORE_BOXES} only boxes within twice its radius (at most 800) of it.
     */
    private boolean fightBox(BotConfig c, WorldSnapshot w) {
        WorldSnapshot.Ship t = w.ships().get(attacker.targetId());
        if (t == null || t.health().known() && t.health().hpPercent() < FINISH_BELOW) {
            boxes.clear();
            return false;
        }
        int map = w.hero().mapId();
        BotConfig.NpcRule r = Targets.rule(c, t, map);
        double nearNpc = r != null && r.has(BotConfig.NpcFlag.IGNORE_BOXES)
                ? Math.min(800, Targets.radius(c, t, map) * 2) : -1;
        if (!boxes.tickFight(t.x(), t.y(), nearNpc)) {
            return false;
        }
        // DarkBot's setConfig(box): the roam mode for a box over three of the target's radii away, else the fight's
        WorldSnapshot.Collectable b = boxes.currentBox(w);
        WorldSnapshot.Hero h = w.hero();
        if (b != null && Math.hypot(b.x() - h.x(), b.y() - h.y()) > Targets.radius(c, t, map) * 3) {
            attacker.boxShipMode(c.general().roam());
        }
        attacker.tick(false);
        return true;
    }

    /** The best target to take now, by the list and the module's skips. */
    private WorldSnapshot.Ship pick(BotConfig c, WorldSnapshot w) {
        double range = c.loot().ignoreFartherThan() > 0 ? c.loot().ignoreFartherThan() : Double.MAX_VALUE;
        MapCatalog.MapInfo here = ctx.maps().info(w.hero().mapId());
        // «only in the preferred zone» (DarkBot's ONLY_KILL_PREFERRED; the user's BL_RINUSK keeps to a zone)
        boolean onlyZone = c.general().onlyKillPreferred();
        int map = w.hero().mapId();
        return Targets.best(c, w, range, s -> attacker.gaveUp(s.id())
                    // one flagged RUSH is taken by the offenders' circles and the trains too (the user, 2026-11-17)
                    || !rushed(c, s, map) && ctx.nav().inDanger(s.x(), s.y())
                    || onlyZone && !Zones.inPreferred(c, here, s.x(), s.y())
                    // not one in a crowd of NPCs that attack on sight (live 2026-11-13 on 2-BL: Abide I packs took
                    // 5–9 % of the hull a second while Impulse II was fought)
                    // (a setting: off it is taken, like DarkBot does — the user, 2026-11-13)
                    || c.loot().skipsCrowded() && Targets.crowd(w, s) >= CROWD_MAX
                    || attacker.tooStrong(s.name())
                    // another player is by it and nearer: he gets there first (live 2026-11-13 on 2-1: 155 of 165
                    // targets gone before the ship came)
                    // (not for an NPC whose rule ignores who hit first: the invoke's reward goes by the damage, the
                    // rule kept the ship off it until it was itself within 700 — live 2026-11-17 on the VPS)
                    || !Targets.ownerIgnored(c, w, s) && Targets.someoneNearer(w, s)
                    // a train by it: the config's «обходить чужие поезда» (AstraBot avoidTrain)
                    || !rushed(c, s, map) && c.loot().fight().avoidTrains() && Targets.train(w, s,
                            c.loot().fight().trainPlayers(), c.loot().fight().trainRadius()));
    }

    private static boolean rushed(BotConfig c, WorldSnapshot.Ship s, int map) {
        BotConfig.NpcRule r = Targets.rule(c, s, map);
        return r != null && r.has(BotConfig.NpcFlag.RUSH);
    }


    /** A target with this many NPCs that attack on sight about it is left for a lone one. */
    static final int CROWD_MAX = 2;

    private void roam(WorldSnapshot w) {
        long now = ctx.now();
        ctx.ship().want(ctx.config().get().general().roam());
        if (roamTo == null || ctx.nav().idle() || now > roamUntil) {
            MapCatalog.MapInfo map = ctx.maps().info(w.hero().mapId());
            double margin = Math.min(map.width(), map.height()) * 0.1;
            // DarkBot's preferred zone: roam inside it (the palladium field); none — the whole map; away from a
            // player who attacked the ship (a few tries, then anywhere)
            for (int i = 0; i < 12 && (i == 0 || ctx.nav().inDanger(roamTo[0], roamTo[1])); i++) {
                double[] inZone = Zones.randomPreferred(ctx.config().get(), map, ctx.random());
                roamTo = inZone != null ? inZone : new double[] {margin + ctx.random().nextDouble() * (map.width() - 2 * margin),
                        margin + ctx.random().nextDouble() * (map.height() - 2 * margin)};
            }
            roamUntil = now + 90_000_000_000L;
            ctx.nav().moveTo(roamTo[0], roamTo[1], false);
        }
        ctx.nav().tick();
        status = "Ищу цели";
    }
}
