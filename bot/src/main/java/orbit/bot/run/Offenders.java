package orbit.bot.run;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The players who shot at the ship or killed it this session (the config's «Обходить тех, кто стрелял или убил»):
 * remembered by id and name for as long as the bot runs (deaths and reconnects included). While one is in sight
 * (or was, a short while ago) a circle of the config's radius round him is kept clear: no targets, no boxes, no way
 * through it ({@link orbit.bot.nav.Navigator#setAvoidedPlayers}); a ship inside such a circle flies out the other
 * way ({@link #escape}) and goes on working there. It never runs to a portal — that is {@link Flee}'s job.
 *
 * <p>Live 2026-11-12 on int1 (5-3): one player killed the palladium ship five times in nine minutes; the bot came
 * back to the same field each time.
 */
public final class Offenders {

    private static final Logger log = LoggerFactory.getLogger(Offenders.class);

    /** A remembered player out of sight is still avoided where he was seen last, this long. */
    static final long LAST_SEEN_NS = 20_000_000_000L;
    /** The ship leaves a circle to this far beyond its edge. */
    static final double OUT = 600;
    /** The way out is chosen again this often (the player moves). */
    static final long REPLAN_NS = 2_000_000_000L;

    private record Seen(String name, double x, double y, int map, long nanos) {
    }

    /** A circle to keep clear, for the window's map. */
    public record Circle(String name, double x, double y, double radius) {
    }

    /** What {@link #circles} gave last, for another thread (the window). */
    private volatile List<Circle> shown = List.of();

    private final BotContext ctx;
    private final Set<Integer> ids = new java.util.HashSet<>();
    private final Set<String> names = new java.util.HashSet<>();
    private final Map<Integer, Seen> seen = new HashMap<>();
    private boolean deathTaken;
    private long escapePlannedAt = Long.MIN_VALUE / 2;
    private String near = "";

    Offenders(BotContext ctx) {
        this.ctx = ctx;
    }

    private BotConfig.Flee config() {
        return ctx.config().get().flee();
    }

    boolean on() {
        return config().avoid();
    }

    /** Anything to keep clear: remembered players, another company's battle stations, NPCs shooting the ship. */
    boolean active() {
        return on() || config().stationRadius() > 0 || config().npcs();
    }

    /** An NPC shooting the ship is kept off this much beyond the radius the list keeps from it. */
    static final double NPC_EXTRA = 250;
    /** It is avoided this long after its last shot. */
    static final long NPC_MEMORY_NS = 15_000_000_000L;

    private record Shooter(String name, double x, double y, double radius, long nanos) {
    }

    private final Map<Integer, Shooter> shooters = new HashMap<>();
    private volatile List<Circle> npcCircles = List.of();

    /**
     * The NPCs shooting the ship that the config does not fight (it would answer those): kept off — circles of their
     * radius and a little more, while they shoot and a short while after.
     */
    private void noteShooters(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = w.nanos();
        BotConfig c = ctx.config().get();
        if (!config().npcs()) {
            shooters.clear();
            npcCircles = List.of();
            return;
        }
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.npc() && s.attackTargetId() == h.id() && h.attackTargetId() != s.id()
                    && !orbit.bot.mod.Targets.attackable(c, s, h.mapId())) {
                double r = orbit.bot.mod.Targets.radius(c, s, h.mapId()) + NPC_EXTRA;
                if (!shooters.containsKey(s.id())) {
                    log.warn("{} стреляет, а его не бью — отхожу из его досягаемости", s.name());
                }
                shooters.put(s.id(), new Shooter(s.name(), s.x(), s.y(), r, now));
            } else if (shooters.containsKey(s.id())) {
                Shooter old = shooters.get(s.id());
                shooters.put(s.id(), new Shooter(old.name(), s.x(), s.y(), old.radius(), old.nanos()));
            }
        }
        shooters.entrySet().removeIf(e -> now - e.getValue().nanos() > NPC_MEMORY_NS || !w.ships().containsKey(e.getKey()));
        npcCircles = shooters.values().stream().map(s -> new Circle(s.name(), s.x(), s.y(), s.radius())).toList();
    }

    /** {@code AssetTypeModule}: a clan battle station. */
    static final int BATTLESTATION = 36;

    /** Another company's clan battle stations on the map (they fire at the ship), when the config keeps away. */
    private volatile List<Circle> stations = List.of();

    private static List<Circle> stations(WorldSnapshot w, int radius) {
        if (radius <= 0) {
            return List.of();
        }
        WorldSnapshot.Hero h = w.hero();
        List<Circle> out = new ArrayList<>();
        for (WorldSnapshot.Asset a : w.assets().values()) {
            if (a.type() == BATTLESTATION && a.factionId() != 0 && a.factionId() != h.factionId()
                    && (h.clanId() == 0 || a.clanId() != h.clanId())) {
                String tag = a.clanTag() == null || a.clanTag().isBlank() ? "" : " [" + a.clanTag() + "]";
                out.add(new Circle("станция" + tag, a.x(), a.y(), radius));
            }
        }
        return out;
    }

    /** Learns who shoots or killed the ship, and where the remembered ones are; every tick, dead or alive. */
    void observe(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = w.nanos();
        notePlayers(w);
        Set<Integer> group = new java.util.HashSet<>();
        w.group().members().forEach(m -> group.add(m.id()));
        BotConfig.Flee f = config();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.kind() != WorldSnapshot.ShipKind.PLAYER || s.id() == h.id() || group.contains(s.id())
                    || Flee.listed(f.never(), s)) {
                continue;
            }
            if (Flee.shooting(s, h) && remember(s.id(), s.name())) {
                log.warn("Запомнил обидчика {}: стрелял в корабль", s.name());
            }
        }
        if (w.death() == null) {
            deathTaken = false;
        } else if (!deathTaken) {
            deathTaken = true;
            // killed while getting away: no «got away» after the revive (live 2026-11-12 on gb2 it said so)
            near = "";
            String killer = w.death().killer();
            // a player's kill: he is (or was) a player ship in sight; NPC kills are not remembered
            WorldSnapshot.Ship k = w.ships().values().stream()
                    .filter(s -> s.kind() == WorldSnapshot.ShipKind.PLAYER && killer != null && killer.equals(s.name()))
                    .findFirst().orElse(null);
            boolean known = killer != null && names.contains(killer.toLowerCase(Locale.ROOT));
            if (k != null && !group.contains(k.id()) && remember(k.id(), k.name())) {
                log.warn("Запомнил обидчика {}: убил корабль", killer);
            } else if (k == null && !known && killer != null && seenPlayer(killer)) {
                names.add(killer.toLowerCase(Locale.ROOT));
                log.warn("Запомнил обидчика {}: убил корабль", killer);
            }
        }
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.kind() == WorldSnapshot.ShipKind.PLAYER && known(s)) {
                ids.add(s.id());
                if (seen.put(s.id(), new Seen(s.name(), s.x(), s.y(), h.mapId(), now)) == null) {
                    sightings++;
                }
            }
        }
        seen.values().removeIf(s -> now - s.nanos() > LAST_SEEN_NS || s.map() != h.mapId());
        double r = f.avoidRadius();
        stations = stations(w, f.stationRadius());
        noteShooters(w);
        List<Circle> all = new ArrayList<>(stations);
        all.addAll(npcCircles);
        if (f.avoid()) {
            seen.values().forEach(s -> all.add(new Circle(s.name(), s.x(), s.y(), r)));
        }
        shown = List.copyOf(all);
    }

    /** The circles kept clear now, any thread. */
    List<Circle> shown() {
        return shown;
    }

    /** The same session goes on after a ship change (logout, the site, login): who attacked is still known. */
    void takeFrom(Offenders previous) {
        ids.addAll(previous.ids);
        names.addAll(previous.names);
        playerNames.putAll(previous.playerNames);
    }

    private final Map<String, Long> playerNames = new HashMap<>();

    /** A name the bot has seen on a player ship (a killer's name may be an NPC's). */
    private boolean seenPlayer(String name) {
        return playerNames.containsKey(name.toLowerCase(Locale.ROOT));
    }

    private void notePlayers(WorldSnapshot w) {
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.kind() == WorldSnapshot.ShipKind.PLAYER && s.name() != null) {
                playerNames.put(s.name().toLowerCase(Locale.ROOT), w.nanos());
            }
        }
    }

    private boolean remember(int id, String name) {
        boolean added = ids.add(id);
        if (name != null && !name.isBlank()) {
            added |= names.add(name.toLowerCase(Locale.ROOT));
        }
        return added;
    }

    private boolean known(WorldSnapshot.Ship s) {
        return ids.contains(s.id()) || (s.name() != null && names.contains(s.name().toLowerCase(Locale.ROOT)));
    }

    private int sightings;

    /** Grows each time a remembered player comes into sight: the module's plans near him are to be dropped. */
    int sightings() {
        return sightings;
    }

    /** Remembered players this session. */
    int count() {
        return names.size();
    }

    /** Circles {x, y, r} to keep clear now: remembered players in sight or seen a short while ago. Empty when off. */
    List<double[]> circles() {
        List<double[]> out = new ArrayList<>();
        for (Circle c : stations) {
            out.add(new double[] {c.x(), c.y(), c.radius()});
        }
        for (Circle c : npcCircles) {
            out.add(new double[] {c.x(), c.y(), c.radius()});
        }
        if (on()) {
            double r = config().avoidRadius();
            for (Seen s : seen.values()) {
                out.add(new double[] {s.x(), s.y(), r});
            }
        }
        return out;
    }

    /** The name of the remembered player whose circle the ship is in, or {@code null}. */
    String inside(WorldSnapshot.Hero h) {
        for (Circle c : npcCircles) {
            if (Math.hypot(c.x() - h.x(), c.y() - h.y()) <= c.radius()) {
                return c.name();
            }
        }
        for (Circle c : stations) {
            if (Math.hypot(c.x() - h.x(), c.y() - h.y()) <= c.radius()) {
                return c.name();
            }
        }
        if (!on()) {
            return null;
        }
        double r = config().avoidRadius();
        return seen.values().stream()
                .filter(s -> Math.hypot(s.x() - h.x(), s.y() - h.y()) <= r)
                .min(java.util.Comparator.comparingDouble(s -> Math.hypot(s.x() - h.x(), s.y() - h.y())))
                .map(Seen::name).orElse(null);
    }

    /**
     * The ship is inside a circle: fly out of it the other way, to the nearest point beyond every circle (so away
     * from the player, not past him). Re-planned every couple of seconds as the player moves.
     *
     * @return the player's name while escaping, {@code null} once out
     */
    String escape(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        String who = inside(h);
        if (who == null) {
            if (!near.isEmpty()) {
                log.info("Отошёл от {} — работаю дальше здесь", near);
                near = "";
            }
            return null;
        }
        long now = ctx.now();
        if (!who.equals(near)) {
            near = who;
            log.warn("{} рядом — ухожу на другую сторону и работаю там", who);
            escapePlannedAt = Long.MIN_VALUE / 2;
        }
        if (now - escapePlannedAt >= REPLAN_NS || ctx.nav().idle()) {
            escapePlannedAt = now;
            double[] to = wayOut(w);
            if (to != null) {
                ctx.nav().moveTo(to[0], to[1], false);
            }
        }
        ctx.nav().tick();
        return who;
    }

    /**
     * What a way-out point costs for the NPCs about it, not yet shooting: those that attack on sight much, the others a
     * little (live 2026-11-13 on 2-BL: away from Abide I into Steadfast III and back, to and fro, killed).
     */
    static double crowd(WorldSnapshot w, double x, double y) {
        double cost = 0;
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (!s.npc()) {
                continue;
            }
            double d = Math.hypot(s.x() - x, s.y() - y);
            if (d < CROWD_REACH) {
                cost += (CROWD_REACH - d) * (orbit.bot.mod.Threats.aggressive(s.name()) ? 4 : 1);
            }
        }
        return cost;
    }

    /** NPCs this close to a way-out point weigh on it. */
    static final double CROWD_REACH = 1_200;

    /** The nearest point on the map beyond all circles: 32 directions round each circle the ship is in. */
    private double[] wayOut(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        MapCatalog.MapInfo map = ctx.maps().info(h.mapId());
        double margin = 300;
        List<double[]> circles = circles();
        double[] best = null;
        double bestCost = Double.MAX_VALUE;
        for (double[] c : circles) {
            if (Math.hypot(c[0] - h.x(), c[1] - h.y()) > c[2]) {
                continue;
            }
            for (int i = 0; i < 32; i++) {
                double a = i * Math.PI / 16;
                double x = c[0] + Math.cos(a) * (c[2] + OUT);
                double y = c[1] + Math.sin(a) * (c[2] + OUT);
                if (x < margin || y < margin || x > map.width() - margin || y > map.height() - margin) {
                    continue;
                }
                boolean clear = circles.stream().noneMatch(o -> Math.hypot(o[0] - x, o[1] - y) <= o[2]);
                // away from the player: the flight there must not pass closer to him than the ship is now
                double toward = orbit.bot.nav.Navigator.distanceToSegment(c[0], c[1], h.x(), h.y(), x, y);
                double now = Math.hypot(c[0] - h.x(), c[1] - h.y());
                double cost = Math.hypot(x - h.x(), y - h.y()) + (clear ? 0 : 1e6) + (toward < now - 1 ? 1e5 : 0)
                        + crowd(w, x, y);
                if (cost < bestCost) {
                    bestCost = cost;
                    best = new double[] {x, y};
                }
            }
        }
        if (best == null) {
            // in a corner with no room: straight away from the nearest one, as far as the map lets
            double[] c = circles.stream().min(java.util.Comparator.comparingDouble(o -> Math.hypot(o[0] - h.x(), o[1] - h.y())))
                    .orElse(null);
            if (c == null) {
                return null;
            }
            double d = Math.max(1, Math.hypot(h.x() - c[0], h.y() - c[1]));
            double x = Math.max(margin, Math.min(map.width() - margin, c[0] + (h.x() - c[0]) / d * (c[2] + OUT)));
            double y = Math.max(margin, Math.min(map.height() - margin, c[1] + (h.y() - c[1]) / d * (c[2] + OUT)));
            best = new double[] {x, y};
        }
        return best;
    }
}
