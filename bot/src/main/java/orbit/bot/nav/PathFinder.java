package orbit.bot.nav;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.PriorityQueue;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.POITypeModule;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/**
 * Shortest path around the zones a ship must not cross. The client itself does not path-find: it
 * cuts a move short where it meets a no-access zone ({@code EventManager} → {@code POIZoneManager.checkCollisions},
 * only zones of type {@code NOA}: {@code POITYPE_NO_ACCESS} and {@code POITYPE_FACTION_NO_ACCESS}, their
 * border pushed out by {@code POIZone.COLLISION_OFFSET} = 20). Radiation and damage zones do not stop a
 * ship but hurt it, so they are avoided when a way around exists.
 *
 * <p>Method: visibility graph over the corners of the inflated zones plus A*.
 */
public final class PathFinder {

    /** The client's collision offset plus room for prediction error and a click spread. */
    static final double MARGIN = 20 + 60;
    /** Circles become polygons with this many corners (drawn around the circle). */
    static final int CIRCLE_CORNERS = 16;
    /** Waypoints are moved this far off the corner they come from. */
    static final double NUDGE = 3;
    /** Points along a flight are tested this far apart (zones are far larger). */
    static final double SAMPLE_STEP = 50;

    /** A zone as a polygon; {@code inverted}: everything outside the polygon is the zone. */
    record Obstacle(double[] xs, double[] ys, boolean inverted) {

        boolean contains(double x, double y) {
            return inside(x, y) != inverted;
        }

        private boolean inside(double x, double y) {
            boolean in = false;
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if ((ys[i] > y) != (ys[j] > y) && x < (xs[j] - xs[i]) * (y - ys[i]) / (ys[j] - ys[i]) + xs[i]) {
                    in = !in;
                }
            }
            return in;
        }

        boolean crosses(double ax, double ay, double bx, double by) {
            for (int i = 0, j = xs.length - 1; i < xs.length; j = i++) {
                if (segmentsIntersect(ax, ay, bx, by, xs[j], ys[j], xs[i], ys[i])) {
                    return true;
                }
            }
            return false;
        }
    }

    private final List<Obstacle> obstacles;
    /** The enemy bases among {@link #obstacles}: given up when the goal itself lies in one (a portal by a base). */
    private final List<Obstacle> bases;
    private final double width;
    private final double height;

    PathFinder(List<Obstacle> obstacles, double width, double height) {
        this(obstacles, List.of(), width, height);
    }

    PathFinder(List<Obstacle> obstacles, List<Obstacle> bases, double width, double height) {
        this.obstacles = obstacles;
        this.bases = bases;
        this.width = width;
        this.height = height;
    }

    /** Asset types of stations that shoot ships of other factions ({@code AssetTypeModule}). */
    static final java.util.Set<Integer> BASE_TYPES = java.util.Set.of(36, 37, 46, 47, 55, 56, 57, 58);

    /** An enemy station: a battle station or its module, a headquarters or a station turret of another faction. */
    public static boolean enemyBase(WorldSnapshot.Asset a, int heroFaction) {
        return BASE_TYPES.contains(a.type()) && a.factionId() != 0 && a.factionId() != heroFaction;
    }

    /**
     * For the current map: no-access zones always, radiation and damage zones too unless
     * {@code throughDanger}.
     */
    public static PathFinder of(WorldSnapshot w, MapCatalog catalog, boolean throughDanger) {
        return of(w, catalog, throughDanger, false);
    }

    /**
     * How far around a mine the ship keeps off (plus {@link #MARGIN}). The client does not know the blast
     * radius (the server decides), so this is a safe guess.
     */
    public static final double MINE_RADIUS = 250;

    /** As {@link #of(WorldSnapshot, MapCatalog, boolean)}, with the mines as round obstacles if {@code avoidMines}. */
    public static PathFinder of(WorldSnapshot w, MapCatalog catalog, boolean throughDanger, boolean avoidMines) {
        return of(w, catalog, throughDanger, avoidMines, 0);
    }

    /** The same, keeping {@code baseRadius} off enemy stations ({@code 0} = not). */
    public static PathFinder of(WorldSnapshot w, MapCatalog catalog, boolean throughDanger, boolean avoidMines,
                                int baseRadius) {
        return of(w, catalog, throughDanger, avoidMines, baseRadius, null);
    }

    /**
     * The same, flying round the config's avoided zone of the map ({@code null} = none) — DarkBot's «avoided
     * zones»: its cells merged into rectangles; one that holds the goal is given up, like a base by a portal.
     */
    public static PathFinder of(WorldSnapshot w, MapCatalog catalog, boolean throughDanger, boolean avoidMines,
                                int baseRadius, orbit.config.BotConfig.ZoneGrid avoided) {
        MapCatalog.MapInfo map = catalog.info(w.hero().mapId());
        List<Obstacle> list = new ArrayList<>();
        List<Obstacle> bases = new ArrayList<>();
        if (avoided != null && !avoided.isEmpty()) {
            for (Obstacle o : rects(avoided, map.width(), map.height())) {
                list.add(o);
                bases.add(o); // soft: given up when the goal lies inside
            }
        }
        if (baseRadius > 0) {
            for (WorldSnapshot.Asset a : w.assets().values()) {
                if (enemyBase(a, w.hero().factionId())) {
                    Obstacle o = circle(a.x(), a.y(), baseRadius);
                    list.add(o);
                    bases.add(o);
                }
            }
        }
        if (avoidMines) {
            WorldSnapshot.Hero h = w.hero();
            for (WorldSnapshot.Mine m : w.mines().values()) {
                // a mine dropped on the ship: fly straight out, there is no way round
                if (Math.hypot(m.x() - h.x(), m.y() - h.y()) > MINE_RADIUS + MARGIN) {
                    list.add(circle(m.x(), m.y(), MINE_RADIUS + MARGIN));
                }
            }
        }
        for (WorldSnapshot.Zone z : w.zones().values()) {
            boolean blocking = z.type() == POITypeModule.POITYPE_NO_ACCESS || z.type() == POITypeModule.POITYPE_FACTION_NO_ACCESS;
            boolean danger = z.type() == POITypeModule.POITYPE_RADIATION || z.type() == POITypeModule.POITYPE_DAMAGE;
            if (z.active() && (blocking || (danger && !throughDanger))) {
                Obstacle o = polygon(z);
                if (o != null) {
                    list.add(o);
                }
            }
        }
        return new PathFinder(List.copyOf(list), List.copyOf(bases), map.width(), map.height());
    }

    /**
     * A zone grid's cells as few rectangles: runs of cells in a row, joined with the same run in the rows below.
     */
    static List<Obstacle> rects(orbit.config.BotConfig.ZoneGrid g, double width, double height) {
        int n = g.resolution();
        List<String> rows = g.rows();
        boolean[][] used = new boolean[rows.size()][];
        for (int y = 0; y < rows.size(); y++) {
            used[y] = new boolean[rows.get(y).length()];
        }
        List<Obstacle> out = new ArrayList<>();
        double cw = width / n;
        double ch = height / n;
        for (int y = 0; y < rows.size(); y++) {
            String row = rows.get(y);
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) != '#' || used[y][x]) {
                    continue;
                }
                int x2 = x;
                while (x2 + 1 < row.length() && row.charAt(x2 + 1) == '#' && !used[y][x2 + 1]) {
                    x2++;
                }
                int y2 = y;
                while (y2 + 1 < rows.size() && runAt(rows.get(y2 + 1), used[y2 + 1], x, x2)) {
                    y2++;
                }
                for (int yy = y; yy <= y2; yy++) {
                    for (int xx = x; xx <= x2; xx++) {
                        used[yy][xx] = true;
                    }
                }
                double x0 = x * cw;
                double y0 = y * ch;
                double x1 = (x2 + 1) * cw;
                double y1 = (y2 + 1) * ch;
                out.add(new Obstacle(new double[] {x0, x1, x1, x0}, new double[] {y0, y0, y1, y1}, false));
                x = x2;
            }
        }
        return out;
    }

    private static boolean runAt(String row, boolean[] used, int x, int x2) {
        if (x2 >= row.length()) {
            return false;
        }
        for (int i = x; i <= x2; i++) {
            if (row.charAt(i) != '#' || used[i]) {
                return false;
            }
        }
        return true;
    }

    static Obstacle circle(double cx, double cy, double radius) {
        double r = radius / Math.cos(Math.PI / CIRCLE_CORNERS);
        double[] xs = new double[CIRCLE_CORNERS];
        double[] ys = new double[CIRCLE_CORNERS];
        for (int i = 0; i < CIRCLE_CORNERS; i++) {
            double a = 2 * Math.PI * i / CIRCLE_CORNERS;
            xs[i] = cx + r * Math.cos(a);
            ys[i] = cy + r * Math.sin(a);
        }
        return new Obstacle(xs, ys, false);
    }

    /** True if (x, y) is within the blast guess of a mine. */
    public static boolean nearMine(WorldSnapshot w, double x, double y) {
        return w.mines().values().stream().anyMatch(m -> Math.hypot(m.x() - x, m.y() - y) <= MINE_RADIUS + MARGIN);
    }

    /** The zone's shape pushed out by {@link #MARGIN} (pulled in for an inverted zone). */
    static Obstacle polygon(WorldSnapshot.Zone z) {
        List<Integer> c = z.coords();
        double grow = z.inverted() ? -MARGIN : MARGIN;
        if (z.shape() == MapAddPOICommand.SHAPETYPE_CIRCLE) {
            if (c.size() < 3) {
                return null;
            }
            // corners on a circle slightly larger than the inflated one, so its edges stay outside too
            double r = Math.max(1, c.get(2) + grow) / Math.cos(Math.PI / CIRCLE_CORNERS);
            double[] xs = new double[CIRCLE_CORNERS];
            double[] ys = new double[CIRCLE_CORNERS];
            for (int i = 0; i < CIRCLE_CORNERS; i++) {
                double a = 2 * Math.PI * i / CIRCLE_CORNERS;
                xs[i] = c.get(0) + r * Math.cos(a);
                ys[i] = c.get(1) + r * Math.sin(a);
            }
            return new Obstacle(xs, ys, z.inverted());
        }
        if (c.size() < 4) {
            return null;
        }
        if (z.shape() == MapAddPOICommand.SHAPETYPE_RECTANGLE) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < c.size(); i += 2) {
                minX = Math.min(minX, c.get(i));
                maxX = Math.max(maxX, c.get(i));
                minY = Math.min(minY, c.get(i + 1));
                maxY = Math.max(maxY, c.get(i + 1));
            }
            return new Obstacle(new double[] {minX - grow, maxX + grow, maxX + grow, minX - grow},
                    new double[] {minY - grow, minY - grow, maxY + grow, maxY + grow}, z.inverted());
        }
        int n = c.size() / 2;
        if (n < 3) {
            return null;
        }
        double cx = 0;
        double cy = 0;
        for (int i = 0; i < n; i++) {
            cx += c.get(2 * i);
            cy += c.get(2 * i + 1);
        }
        cx /= n;
        cy /= n;
        double[] xs = new double[n];
        double[] ys = new double[n];
        for (int i = 0; i < n; i++) {
            // push each corner away from the centre (enough for the convex-ish zones of the game)
            double dx = c.get(2 * i) - cx;
            double dy = c.get(2 * i + 1) - cy;
            double len = Math.max(1, Math.hypot(dx, dy));
            double push = grow * Math.sqrt(2);
            xs[i] = c.get(2 * i) + dx / len * push;
            ys[i] = c.get(2 * i + 1) + dy / len * push;
        }
        return new Obstacle(xs, ys, z.inverted());
    }

    public boolean free(double x, double y) {
        if (x < 0 || y < 0 || x > width || y > height) {
            return false;
        }
        for (Obstacle o : obstacles) {
            if (o.contains(x, y)) {
                return false;
            }
        }
        return true;
    }

    /** A straight flight from a to b touches no zone (the map is convex, so its border needs no check). */
    public boolean clear(double ax, double ay, double bx, double by) {
        for (Obstacle o : obstacles) {
            if (o.crosses(ax, ay, bx, by)) {
                return false;
            }
        }
        // a line through a corner is no proper crossing: also test points along it
        double len = Math.hypot(bx - ax, by - ay);
        int steps = Math.max(1, (int) Math.ceil(len / SAMPLE_STEP));
        for (int k = 1; k < steps; k++) {
            double x = ax + (bx - ax) * k / steps;
            double y = ay + (by - ay) * k / steps;
            for (Obstacle o : obstacles) {
                if (o.contains(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Waypoints from (but excluding) the start to the goal. A goal inside a zone is replaced by the
     * nearest free point, as the client would stop there; a start inside one leaves it first. Empty if no
     * way exists.
     */
    public List<double[]> path(double sx, double sy, double gx, double gy) {
        if (bases.stream().anyMatch(b -> b.contains(gx, gy))) {
            // the goal is by an enemy base (a portal next to it): there is no way but in
            List<Obstacle> rest = new ArrayList<>(obstacles);
            rest.removeIf(o -> bases.contains(o) && o.contains(gx, gy));
            return new PathFinder(List.copyOf(rest), List.of(), width, height).path(sx, sy, gx, gy);
        }
        if (!free(sx, sy)) {
            // already inside a zone (a mine dropped next to the ship, a zone that appeared): every line from
            // here crosses it, so first the shortest way out, then the usual path
            double[] exit = nearestFree(sx, sy);
            if (exit == null) {
                return List.of();
            }
            List<double[]> out = new ArrayList<>();
            out.add(exit);
            out.addAll(path(exit[0], exit[1], gx, gy));
            return out;
        }
        double[] goal = nearestFree(gx, gy);
        if (goal == null) {
            return List.of();
        }
        if (clear(sx, sy, goal[0], goal[1])) {
            return List.of(goal);
        }
        List<double[]> nodes = new ArrayList<>();
        nodes.add(new double[] {sx, sy});
        nodes.add(goal);
        for (Obstacle o : obstacles) {
            double cx = Arrays.stream(o.xs()).average().orElse(0);
            double cy = Arrays.stream(o.ys()).average().orElse(0);
            for (int i = 0; i < o.xs().length; i++) {
                // a corner sits on the zone's edge: nudge it off (outwards, or inwards for an inverted zone)
                double dx = o.xs()[i] - cx;
                double dy = o.ys()[i] - cy;
                double len = Math.max(1e-6, Math.hypot(dx, dy));
                double nudge = o.inverted() ? -NUDGE : NUDGE;
                double x = o.xs()[i] + dx / len * nudge;
                double y = o.ys()[i] + dy / len * nudge;
                if (free(x, y)) {
                    nodes.add(new double[] {x, y});
                }
            }
        }
        return astar(nodes);
    }

    private List<double[]> astar(List<double[]> nodes) {
        int n = nodes.size();
        double[] g = new double[n];
        int[] from = new int[n];
        boolean[] done = new boolean[n];
        Arrays.fill(g, Double.MAX_VALUE);
        Arrays.fill(from, -1);
        g[0] = 0;
        double[] goal = nodes.get(1);
        PriorityQueue<double[]> open = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        open.add(new double[] {0, dist(nodes.get(0), goal)});
        while (!open.isEmpty()) {
            int u = (int) open.poll()[0];
            if (done[u]) {
                continue;
            }
            if (u == 1) {
                break;
            }
            done[u] = true;
            double[] a = nodes.get(u);
            for (int v = 0; v < n; v++) {
                if (v == u || done[v]) {
                    continue;
                }
                double[] b = nodes.get(v);
                double cost = g[u] + dist(a, b);
                if (cost < g[v] && clear(a[0], a[1], b[0], b[1])) {
                    g[v] = cost;
                    from[v] = u;
                    open.add(new double[] {v, cost + dist(b, goal)});
                }
            }
        }
        if (from[1] < 0) {
            return List.of();
        }
        List<double[]> path = new ArrayList<>();
        for (int v = 1; v != 0; v = from[v]) {
            path.add(0, nodes.get(v));
        }
        return path;
    }

    /** The point itself if free, otherwise the closest free point on rings around it. */
    double[] nearestFree(double x, double y) {
        double cx = Math.max(0, Math.min(width, x));
        double cy = Math.max(0, Math.min(height, y));
        if (free(cx, cy)) {
            return new double[] {cx, cy};
        }
        for (double r = 50; r < Math.max(width, height); r += 50) {
            for (int i = 0; i < 32; i++) {
                double a = 2 * Math.PI * i / 32;
                double px = cx + r * Math.cos(a);
                double py = cy + r * Math.sin(a);
                if (free(px, py)) {
                    return new double[] {px, py};
                }
            }
        }
        return null;
    }

    int obstacleCount() {
        return obstacles.size();
    }

    static double length(double sx, double sy, Collection<double[]> path) {
        double total = 0;
        double px = sx;
        double py = sy;
        for (double[] p : path) {
            total += Math.hypot(p[0] - px, p[1] - py);
            px = p[0];
            py = p[1];
        }
        return total;
    }

    private static double dist(double[] a, double[] b) {
        return Math.hypot(a[0] - b[0], a[1] - b[1]);
    }

    static boolean segmentsIntersect(double ax, double ay, double bx, double by,
                                     double cx, double cy, double dx, double dy) {
        double d1 = cross(cx, cy, dx, dy, ax, ay);
        double d2 = cross(cx, cy, dx, dy, bx, by);
        double d3 = cross(ax, ay, bx, by, cx, cy);
        double d4 = cross(ax, ay, bx, by, dx, dy);
        return ((d1 > 0 && d2 < 0) || (d1 < 0 && d2 > 0)) && ((d3 > 0 && d4 < 0) || (d3 < 0 && d4 > 0));
    }

    private static double cross(double ax, double ay, double bx, double by, double px, double py) {
        return (bx - ax) * (py - ay) - (by - ay) * (px - ax);
    }
}
