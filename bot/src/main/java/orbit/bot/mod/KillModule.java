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
            WorldSnapshot.Ship t = Targets.best(c, w, range, s -> attacker.gaveUp(s.id()) || ctx.nav().inDanger(s.x(), s.y()));
            if (t != null) {
                roamTo = null;
                attacker.target(t);
                attacker.tick(true);
                status = "Бой";
                return;
            }
        }
        roam(w);
    }

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
