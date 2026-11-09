package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotModule;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Cubikon camp on the x-6 maps (the user's working DarkBot profile, GE5 Cubi Camp logic):
 * <ul>
 *   <li>a live Cubikon on the map — even one others fight — is attacked at once;</li>
 *   <li>its boxes are picked up while it has more than {@code boxesWhileCubikonHpAbove} HP;</li>
 *   <li>no Cubikon: other NPCs nearby if {@code killWhileWaiting}, boxes, otherwise the spawn spots in
 *       turn, nearest first: fly within {@code arriveDistance}, wait {@code waitSeconds} (0 = until a
 *       Cubikon shows up), next spot;</li>
 *   <li>NPCs the list marks "не бить" (Protegit) are never shot.</li>
 * </ul>
 * Settings: config section {@code modules.cubikon}.
 */
public final class CubikonModule implements BotModule {

    private static final Logger log = LoggerFactory.getLogger(CubikonModule.class);

    static final int[][] DEFAULT_SPOTS = {{7500, 3900}, {13400, 3900}, {13400, 7900}, {7500, 7900}};
    /** Wait-kill only NPCs this close: the camp must not wander off its spot. */
    static final double WAIT_KILL_RANGE = 1_500;

    private final BotContext ctx;
    private final Attacker attacker;
    private final BoxPicker boxes;
    private int spot = -1;
    private long arrivedAt;
    private boolean onCube;
    private String status = "кубы";

    public CubikonModule(BotContext ctx) {
        this.ctx = ctx;
        this.attacker = new Attacker(ctx);
        this.boxes = new BoxPicker(ctx);
    }

    @Override
    public String status() {
        return status + " (убито " + attacker.kills() + ", ящиков " + boxes.boxes() + ")";
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
        arrivedAt = 0;
    }

    @Override
    public void tick() {
        BotConfig c = ctx.config().get();
        JsonNode s = c.modules().get(BotConfig.Module.CUBIKON.key());
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();

        WorldSnapshot.Ship cube = findCubikon(c, w);
        if (cube != null) {
            if (!onCube) {
                log.info("Cubikon на карте — к нему ({} от нас)", (int) cube.distanceTo(h.x(), h.y()));
                onCube = true;
            }
            arrivedAt = 0;
            attacker.target(cube);
            double hp = cube.health().hpPercent();
            boolean boxesOk = setting(s, "collectBoxes", true)
                    && (hp < 0 || hp > setting(s, "boxesWhileCubikonHpAbove", 0.25));
            boolean onBox = boxesOk && boxes.tick();
            attacker.tick(!onBox);
            status = onBox ? "Cubikon: подбираю ящик" : "Cubikon: бой";
            return;
        }
        if (onCube) {
            onCube = false;
            spot = -1;
        }
        if (attacker.targetId() != 0 && !attacker.tick(true)) {
            attacker.clear();
        }
        if (attacker.targetId() != 0) {
            status = "бью, пока жду куб";
            return;
        }
        if (setting(s, "killWhileWaiting", false)) {
            WorldSnapshot.Ship t = Targets.best(c, w, WAIT_KILL_RANGE);
            if (t != null) {
                attacker.target(t);
                attacker.tick(true);
                status = "бью, пока жду куб";
                return;
            }
        }
        if (setting(s, "collectBoxes", true) && boxes.tick()) {
            status = "подбираю ящик";
            return;
        }
        camp(s, h);
    }

    /** Waits on the spawn spots in turn. */
    private void camp(JsonNode s, WorldSnapshot.Hero h) {
        List<int[]> spots = spots(s);
        int arrive = (int) setting(s, "arriveDistance", 450);
        int waitSec = (int) setting(s, "waitSeconds", 90);
        if (spot < 0 || spot >= spots.size()) {
            spot = nearest(spots, h);
        }
        int[] p = spots.get(spot);
        double dist = Math.hypot(p[0] - h.x(), p[1] - h.y());
        ctx.ship().want(ctx.config().get().general().roam());
        if (dist > arrive) {
            arrivedAt = 0;
            if (ctx.nav().idle() || ctx.nav().destination().x() != p[0] || ctx.nav().destination().y() != p[1]) {
                // a far minimap click lands on its grid; the last stretch is a click in space
                ctx.nav().moveTo(p[0], p[1], dist < 2_500);
            }
            ctx.nav().tick();
            status = "лечу на спаун " + (spot + 1) + "/" + spots.size();
            return;
        }
        long now = ctx.now();
        if (arrivedAt == 0) {
            arrivedAt = now;
            ctx.nav().stop();
            log.info("На спауне {}/{} — жду куб", spot + 1, spots.size());
        }
        if (waitSec > 0 && now - arrivedAt >= waitSec * 1_000_000_000L + ctx.pause(0, 5_000)) {
            spot = (spot + 1) % spots.size();
            arrivedAt = 0;
            return;
        }
        status = "жду куб на спауне " + (spot + 1) + "/" + spots.size();
    }

    /** The nearest live Cubikon the list allows to attack (any owner, as the camp does). */
    static WorldSnapshot.Ship findCubikon(BotConfig c, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        return w.ships().values().stream()
                .filter(WorldSnapshot.Ship::npc)
                .filter(x -> x.name().toLowerCase().contains("cubikon"))
                .filter(x -> {
                    BotConfig.NpcRule r = Targets.rule(c, x, h.mapId());
                    return r == null || r.kill();
                })
                .min(Comparator.comparingDouble(x -> x.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    static List<int[]> spots(JsonNode s) {
        List<int[]> out = new ArrayList<>();
        if (s != null && s.path("spots").isArray()) {
            for (JsonNode p : s.path("spots")) {
                out.add(new int[] {p.path(0).asInt(), p.path(1).asInt()});
            }
        }
        if (out.isEmpty()) {
            for (int[] p : DEFAULT_SPOTS) {
                out.add(p.clone());
            }
        }
        return out;
    }

    private static int nearest(List<int[]> spots, WorldSnapshot.Hero h) {
        int best = 0;
        for (int i = 1; i < spots.size(); i++) {
            if (Math.hypot(spots.get(i)[0] - h.x(), spots.get(i)[1] - h.y())
                    < Math.hypot(spots.get(best)[0] - h.x(), spots.get(best)[1] - h.y())) {
                best = i;
            }
        }
        return best;
    }

    private static boolean setting(JsonNode s, String key, boolean def) {
        return s == null || !s.has(key) ? def : s.get(key).asBoolean(def);
    }

    private static double setting(JsonNode s, String key, double def) {
        return s == null || !s.has(key) ? def : s.get(key).asDouble(def);
    }
}
