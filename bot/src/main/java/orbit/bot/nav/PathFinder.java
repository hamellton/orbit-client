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
    /** The way out of a zone ends this far beyond its edge. */
    static final double EXIT_EXTRA = 80;
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
    /** The config's avoided zone as drawn, {@code null} = none: searched cell by cell when corners find no way. */
    private orbit.config.BotConfig.ZoneGrid grid;
    /** What the obstacles are, for the «no way» message. */
    private String summary = "";

    PathFinder(List<Obstacle> obstacles, double width, double height) {
        this(obstacles, List.of(), width, height);
    }

    PathFinder(List<Obstacle> obstacles, List<Obstacle> bases, double width, double height) {
        this.obstacles = obstacles;
        this.bases = bases;
        this.width = width;
        this.height = height;
    }

    /** The avoided zone alone, as {@link #of} builds it (tests). */
    static PathFinder avoiding(orbit.config.BotConfig.ZoneGrid g, double width, double height) {
        List<Obstacle> rects = rects(g, width, height);
        PathFinder pf = new PathFinder(rects, rects, width, height);
        pf.grid = g;
        return pf;
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
    /** A mine this close (or on the ship): no way round, straight out. */
    static final double MINE_CLOSE = 60;

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
        return of(w, catalog, throughDanger, avoidMines, baseRadius, avoided, null);
    }

    /**
     * The same with the config's preferred zone: a server's danger zone the user chose to work in is no obstacle
     * (live 2026-11-11: 5-3's palladium field is a damage zone; DarkBot collects inside it, the bot stopped at
     * its edge).
     */
    public static PathFinder of(WorldSnapshot w, MapCatalog catalog, boolean throughDanger, boolean avoidMines,
                                int baseRadius, orbit.config.BotConfig.ZoneGrid avoided,
                                orbit.config.BotConfig.ZoneGrid preferred) {
        return of(w, catalog, throughDanger, avoidMines, baseRadius, avoided, preferred, List.of());
    }

    /**
     * The same, flying round {@code players}: circles {x, y, r} round the players who attacked the ship this session
     * (soft, like a base: given up when the goal lies inside; none round the ship itself — it flies straight out).
     */
    public static PathFinder of(WorldSnapshot w, MapCatalog catalog, boolean throughDanger, boolean avoidMines,
                                int baseRadius, orbit.config.BotConfig.ZoneGrid avoided,
                                orbit.config.BotConfig.ZoneGrid preferred, List<double[]> players) {
        MapCatalog.MapInfo map = catalog.info(w.hero().mapId());
        List<Obstacle> list = new ArrayList<>();
        List<Obstacle> bases = new ArrayList<>();
        for (double[] p : players) {
            if (Math.hypot(p[0] - w.hero().x(), p[1] - w.hero().y()) > p[2]) {
                Obstacle o = circle(p[0], p[1], p[2]);
                list.add(o);
                bases.add(o);
            }
        }
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
                double d = Math.hypot(m.x() - h.x(), m.y() - h.y());
                if (d > MINE_RADIUS + MARGIN) {
                    list.add(circle(m.x(), m.y(), MINE_RADIUS + MARGIN));
                } else if (d > MINE_CLOSE + 40) {
                    // the ship already within the keep-off ring (a mine dropped by it, or the way round brushed the
                    // ring): out freely, but never closer than it is — live 2026-11-12 on ru1 the mine was dropped
                    // from the obstacles once the ship touched the ring, and the next route went straight over it
                    list.add(circle(m.x(), m.y(), d - 40));
                }
            }
        }
        for (WorldSnapshot.Zone z : w.zones().values()) {
            boolean blocking = z.type() == POITypeModule.POITYPE_NO_ACCESS || z.type() == POITypeModule.POITYPE_FACTION_NO_ACCESS;
            // DarkBot (EntityFactory): only «NOA» zones are barriers; «DMG» ones (5-3's palladium field) are a mist
            // where the cloak does not hold, flown through like open space
            boolean danger = z.type() == POITypeModule.POITYPE_RADIATION;
            if (z.active() && (blocking || (danger && !throughDanger))) {
                Obstacle o = polygon(z);
                if (o != null && !(danger && overlaps(o, preferred, map.width(), map.height()))) {
                    list.add(o);
                }
            }
        }
        PathFinder pf = new PathFinder(List.copyOf(list), List.copyOf(bases), map.width(), map.height());
        if (avoided != null && !avoided.isEmpty()) {
            pf.grid = avoided;
        }
        long serverZones = w.zones().values().stream().filter(WorldSnapshot.Zone::active).count();
        int enemyBases = bases.size() - (avoided == null || avoided.isEmpty() ? 0 : rects(avoided, map.width(), map.height()).size());
        pf.summary = "зоны сервера " + serverZones + " (" + w.zones().values().stream().filter(WorldSnapshot.Zone::active)
                .map(z -> z.type() + (z.inverted() ? "i" : "")).distinct().toList() + "), чужие базы " + enemyBases
                + ", мины " + (avoidMines ? w.mines().size() : 0) + ", запретная зона конфига " + (pf.grid != null ? "есть" : "нет");
        return pf;
    }

    /** A preferred cell's centre lies inside the zone. */
    static boolean overlaps(Obstacle o, orbit.config.BotConfig.ZoneGrid g, double width, double height) {
        if (g == null || g.isEmpty()) {
            return false;
        }
        int n = g.resolution();
        for (int r = 0; r < g.rows().size(); r++) {
            String row = g.rows().get(r);
            for (int c = 0; c < row.length(); c++) {
                if (row.charAt(c) == '#' && o.contains((c + 0.5) * width / n, (r + 0.5) * height / n)) {
                    return true;
                }
            }
        }
        return false;
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

    /** Why there may be no way: the start's and the goal's freedom and the obstacles. */
    public String explain(double sx, double sy, double gx, double gy) {
        return "старт " + (int) sx / 100 + "/" + (int) sy / 100 + (free(sx, sy) ? " свободен" : " в препятствии")
                + ", цель " + (free(gx, gy) ? "свободна" : (nearestFree(gx, gy) == null ? "в препятствии, рядом свободного нет"
                : "в препятствии")) + "; " + summary;
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
            // a little farther out the same way: an exit on the very edge put the ship back inside the next moment,
            // and the way out was planned again and again (live 2026-11-13 on 3-BL: 10 s at a wall)
            double ex = exit[0] - sx;
            double ey = exit[1] - sy;
            double len = Math.hypot(ex, ey);
            if (len > 1) {
                double[] farther = {exit[0] + ex / len * EXIT_EXTRA, exit[1] + ey / len * EXIT_EXTRA};
                if (free(farther[0], farther[1])) {
                    exit = farther;
                }
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
        List<double[]> found = astar(nodes);
        if (found.isEmpty() && grid != null) {
            // lanes one cell wide between avoided cells (paths to the portals, live 2026-11-11 on 5-3): a corner
            // of one rectangle nudged out lands in the next, so the corners see no way — walk the cells instead
            return gridPath(sx, sy, goal);
        }
        return found;
    }

    /**
     * A* over the avoided grid's cells (8 ways, no cutting a corner), then straightened: from each point straight
     * on to the farthest point of the way still clear.
     */
    private List<double[]> gridPath(double sx, double sy, double[] goal) {
        int n = grid.resolution();
        double cw = width / n;
        double ch = height / n;
        int sc = cell(sx, cw, n);
        int sr = cell(sy, ch, n);
        int gc = cell(goal[0], cw, n);
        int gr = cell(goal[1], ch, n);
        boolean[] open = new boolean[n * n];
        for (int r = 0; r < n; r++) {
            for (int c = 0; c < n; c++) {
                String row = r < grid.rows().size() ? grid.rows().get(r) : "";
                boolean avoidedCell = c < row.length() && row.charAt(c) == '#';
                open[r * n + c] = !avoidedCell && free((c + 0.5) * cw, (r + 0.5) * ch);
            }
        }
        open[sr * n + sc] = true;
        open[gr * n + gc] = true;
        double[] g = new double[n * n];
        int[] from = new int[n * n];
        Arrays.fill(g, Double.MAX_VALUE);
        Arrays.fill(from, -1);
        int start = sr * n + sc;
        int target = gr * n + gc;
        g[start] = 0;
        PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> Double.compare(a[1], b[1]));
        queue.add(new double[] {start, 0});
        boolean[] done = new boolean[n * n];
        while (!queue.isEmpty()) {
            int u = (int) queue.poll()[0];
            if (done[u]) {
                continue;
            }
            done[u] = true;
            if (u == target) {
                break;
            }
            int ur = u / n;
            int uc = u % n;
            for (int dr = -1; dr <= 1; dr++) {
                for (int dc = -1; dc <= 1; dc++) {
                    int vr = ur + dr;
                    int vc = uc + dc;
                    if ((dr == 0 && dc == 0) || vr < 0 || vc < 0 || vr >= n || vc >= n || !open[vr * n + vc]) {
                        continue;
                    }
                    if (dr != 0 && dc != 0 && (!open[ur * n + vc] || !open[vr * n + uc])) {
                        continue; // no squeezing past a corner
                    }
                    int v = vr * n + vc;
                    double cost = g[u] + Math.hypot(dc * cw, dr * ch);
                    if (cost < g[v]) {
                        g[v] = cost;
                        from[v] = u;
                        queue.add(new double[] {v, cost + Math.hypot((gc - vc) * cw, (gr - vr) * ch)});
                    }
                }
            }
        }
        if (from[target] < 0 && target != start) {
            return List.of();
        }
        List<double[]> cells = new ArrayList<>();
        for (int v = target; v != start; v = from[v]) {
            cells.add(0, new double[] {(v % n + 0.5) * cw, (v / n + 0.5) * ch});
        }
        if (!cells.isEmpty()) {
            cells.set(cells.size() - 1, goal);
        } else {
            cells.add(goal);
        }
        List<double[]> out = new ArrayList<>();
        double[] at = {sx, sy};
        int i = 0;
        while (i < cells.size()) {
            int far = i;
            for (int j = cells.size() - 1; j > i; j--) {
                if (clear(at[0], at[1], cells.get(j)[0], cells.get(j)[1])) {
                    far = j;
                    break;
                }
            }
            at = cells.get(far);
            out.add(at);
            i = far + 1;
        }
        return out;
    }

    private static int cell(double v, double size, int n) {
        return Math.max(0, Math.min(n - 1, (int) (v / size)));
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
        // fine rings near the point: the first free one is straight off the edge, not along it (a 50 step found a
        // point beside the ship on a wall's edge and led it along the wall)
        for (double r = 10; r < Math.max(width, height); r += r < 200 ? 10 : 50) {
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
