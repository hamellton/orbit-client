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
        if (attacker.targetId() != 0 && attacker.tick(true)) {
            status = "Бой";
            return;
        }
        // no fight: any box in sight (DarkBot's collector); its radius only counts next to a target
        if (collect && boxes.tickAnywhere()) {
            status = "Подбираю " + boxes.currentName();
            return;
        }
        if (kill) {
            double range = c.loot().ignoreFartherThan() > 0 ? c.loot().ignoreFartherThan() : Double.MAX_VALUE;
            MapCatalog.MapInfo here = ctx.maps().info(w.hero().mapId());
            // «only in the preferred zone» (DarkBot's ONLY_KILL_PREFERRED; the user's BL_RINUSK keeps to a zone)
            boolean onlyZone = c.general().onlyKillPreferred();
            WorldSnapshot.Ship t = Targets.best(c, w, range, s -> attacker.gaveUp(s.id()) || ctx.nav().inDanger(s.x(), s.y())
                    || onlyZone && !Zones.inPreferred(c, here, s.x(), s.y())
                    // not one in a crowd of NPCs that attack on sight (live 2026-11-13 on 2-BL: Abide I packs took
                    // 5–9 % of the hull a second while Impulse II was fought)
                    || Targets.crowd(w, s) >= CROWD_MAX
                    || attacker.tooStrong(s.name())
                    // another player is by it and nearer: he gets there first (live 2026-11-13 on 2-1: 155 of 165
                    // targets gone before the ship came)
                    || Targets.someoneNearer(w, s));
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
     * The best NPC of the list flagged {@code PET_LOCATOR} for this map that is not in sight (the user, 2026-11-13: the
     * locator on Boss Kristallon): the pet's enemy locator looks for it.
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
                .filter(e -> w.ships().values().stream().noneMatch(s -> s.npc() && e.getKey().equals(s.name())))
                .filter(e -> !attacker.tooStrong(e.getKey()))
                .sorted(java.util.Comparator.comparingInt(e -> e.getValue().priority()))
                .mapToInt(e -> npcNumber(e.getKey())).filter(id -> id > 0).findFirst().orElse(0);
    }

    /** The game's NPC number of a name the list keeps (its letters and digits), 0 when unknown. */
    static int npcNumber(String name) {
        String n = QuestTargets.norm(name);
        return orbit.world.maps.NpcCatalog.bundled().all().stream()
                .filter(npc -> QuestTargets.norm(npc.name()).equals(n))
                .mapToInt(orbit.world.maps.NpcCatalog.Npc::id).findFirst().orElse(0);
    }

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
        String name = orbit.world.maps.NpcCatalog.bundled().name(target);
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
        ctx.ship().want(c.general().travel());
        if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - lead[0], ctx.nav().destination().y() - lead[1]) > 300) {
            ctx.nav().moveTo(lead[0], lead[1], false);
        }
        ctx.nav().tick();
        status = "Лечу к " + name + " по пеленгатору";
        return true;
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
