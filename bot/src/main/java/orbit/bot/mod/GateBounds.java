package orbit.bot.mod;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The real playing field of a gate's map, learned from the radiation the ship ran into: the game's bounds DarkBot's
 * canMove keeps inside (live 2026-11-15 on gb2, GG β: the ship went for a Sibelonit in a corner, lost 31 % HP to the
 * radiation with nobody shooting, and never came within reach).
 */
public final class GateBounds {

    /** How far inside the radiation's edge the ship keeps. */
    static final double KEEP = 150;

    /** minX, minY, maxX, maxY per map. */
    private static final Map<Integer, double[]> BOUNDS = new ConcurrentHashMap<>();

    private GateBounds() {
    }

    /** The ship is in radiation at (x, y) on a map {@code w} × {@code h}: the nearer edges move in to there. */
    public static void radiation(int map, double x, double y, double w, double h) {
        double[] b = BOUNDS.computeIfAbsent(map, k -> new double[] {0, 0, w, h});
        synchronized (b) {
            double dx = Math.min(x, w - x);
            double dy = Math.min(y, h - y);
            // the edge (or both, in a corner) the ship is nearest to — scaled, the field being wider than high
            if (dx / w <= dy / h * 1.5) {
                if (x < w / 2) {
                    b[0] = Math.max(b[0], x + KEEP);
                } else {
                    b[2] = Math.min(b[2], x - KEEP);
                }
            }
            if (dy / h <= dx / w * 1.5) {
                if (y < h / 2) {
                    b[1] = Math.max(b[1], y + KEEP);
                } else {
                    b[3] = Math.min(b[3], y - KEEP);
                }
            }
        }
    }

    /** Inside the field learned so far (all of the map when nothing is known). */
    public static boolean inside(int map, double x, double y) {
        double[] b = BOUNDS.get(map);
        return b == null || x >= b[0] && y >= b[1] && x <= b[2] && y <= b[3];
    }

    /** How far (x, y) lies outside the field (0 inside). */
    public static double outside(int map, double x, double y) {
        double[] b = BOUNDS.get(map);
        if (b == null) {
            return 0;
        }
        double ox = Math.max(0, Math.max(b[0] - x, x - b[2]));
        double oy = Math.max(0, Math.max(b[1] - y, y - b[3]));
        return Math.hypot(ox, oy);
    }

    /** The nearest point inside the field. */
    public static double[] clamp(int map, double x, double y) {
        double[] b = BOUNDS.get(map);
        if (b == null) {
            return new double[] {x, y};
        }
        return new double[] {Math.max(b[0], Math.min(b[2], x)), Math.max(b[1], Math.min(b[3], y))};
    }

    static void clear() {
        BOUNDS.clear();
    }
}
