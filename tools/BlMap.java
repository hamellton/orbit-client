import java.awt.*;
import java.awt.geom.*;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.*;
import java.util.List;
import javax.imageio.ImageIO;

/**
 * Draws the BL maps (1-, 2-, 3-BL) for docs/BLACKLIGHT.md «Точки у бегемота»: the walls the server sends
 * (reference/maps/bl-walls.json), the behemoth's spawn, the trains' routes portal → spawn found round the walls (A* on a
 * 100 grid, walls kept 300 off, then straightened), the wait point now and the side / retreat points the user chose
 * (2026-11-20), with their distance from the routes printed.
 *
 * <p>Run from the repo root after {@code ./gradlew :app:installDist}, with the game's minimaps in {@code <dir>/img}
 * ({@code <cdn>spacemap/graphics/minimaps/minimap-<id>-700.jpg}):
 * {@code javac -cp "app/build/install/orbit/lib/*" -d /tmp/blmap tools/BlMap.java &&
 * java -Djava.awt.headless=true -cp "app/build/install/orbit/lib/*:/tmp/blmap" BlMap <dir>} → {@code <dir>/bl-maps.png}.
 */
public class BlMap {
    static final int W = 21000, H = 13100;
    static final double K = 1400.0 / W; // px per unit
    static List<double[]> walls = new ArrayList<>(); // rect x1,y1,x2,y2 or circle cx,cy,r,-1

    public static void main(String[] a) throws Exception {
        String dir = a[0];
        var json = new com.fasterxml.jackson.databind.ObjectMapper().readTree(new File("reference/maps/bl-walls.json"));
        int[][] spawn = {{19400, 5800}, {6900, 5000}, {4700, 3900}};
        int[][] waitCfg = {{18900, 6550}, {5150, 5000}, {2650, 2350}};
        int[][][] portals = {
            {{786, 11458}, {7589, 1456}, {20072, 11732}},
            {{9893, 862}, {593, 5884}, {20377, 7996}},
            {{1545, 12210}, {19400, 11854}, {14027, 3181}}};
        String[][] pnames = {{"1-8", "2BL", "3BL"}, {"1BL", "2-8", "3BL"}, {"1BL", "2BL", "3-8"}};
        String[] names = {"1-BL", "2-BL", "3-BL"};
        int[] ids = {306, 307, 308};
        int ph = (int) (H * K);
        BufferedImage out = new BufferedImage(1400, 3 * (ph + 40) + 150, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g.setColor(new Color(0x0f141c));
        g.fillRect(0, 0, out.getWidth(), out.getHeight());
        for (int m = 0; m < 3; m++) {
            int oy = m * (ph + 40) + 34;
            walls.clear();
            for (var o : json.path("maps").path(String.valueOf(ids[m]))) {
                var c = o.path("coords");
                if (o.path("shape").asInt() != 0) { // rectangle or polygon: its bounding box
                    double x1 = 1e9, y1 = 1e9, x2 = -1e9, y2 = -1e9;
                    for (int i = 0; i < c.size(); i += 2) {
                        x1 = Math.min(x1, c.get(i).asDouble()); x2 = Math.max(x2, c.get(i).asDouble());
                        y1 = Math.min(y1, c.get(i + 1).asDouble()); y2 = Math.max(y2, c.get(i + 1).asDouble());
                    }
                    walls.add(new double[] {x1, y1, x2, y2});
                } else {
                    walls.add(new double[] {c.get(0).asDouble(), c.get(1).asDouble(), c.get(2).asDouble(), -1});
                }
            }
            g.setClip(0, oy, 1400, ph);
            BufferedImage bg = ImageIO.read(new File(dir + "/img/minimap-" + ids[m] + ".jpg"));
            g.drawImage(bg, 0, oy, 1400, ph, null);
            g.setColor(new Color(0, 0, 0, 110));
            g.fillRect(0, oy, 1400, ph);
            // radiation edge
            g.setColor(new Color(120, 255, 120, 70));
            g.setStroke(new BasicStroke(10));
            g.drawRect(0, oy, 1399, ph - 1);
            // walls
            g.setColor(new Color(150, 150, 165, 200));
            for (double[] w : walls) {
                if (w[3] < 0) {
                    g.fill(new Ellipse2D.Double((w[0] - w[2]) * K, oy + (w[1] - w[2]) * K, 2 * w[2] * K, 2 * w[2] * K));
                } else {
                    g.fill(new Rectangle2D.Double(w[0] * K, oy + w[1] * K, (w[2] - w[0]) * K, (w[3] - w[1]) * K));
                }
            }
            int[] s = spawn[m];
            // train routes portal -> spawn round the walls (1 000 each side)
            List<List<double[]>> routes = new ArrayList<>();
            for (int p = 0; p < 3; p++) {
                int[] q = portals[m][p];
                List<double[]> r = route(q[0], q[1], s[0], s[1]);
                routes.add(r);
                Path2D path = new Path2D.Double();
                for (int i = 0; i < r.size(); i++) {
                    if (i == 0) path.moveTo(r.get(i)[0] * K, oy + r.get(i)[1] * K); else path.lineTo(r.get(i)[0] * K, oy + r.get(i)[1] * K);
                }
                g.setColor(new Color(255, 70, 70, 55));
                g.setStroke(new BasicStroke((float) (2000 * K), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(path);
                g.setColor(new Color(255, 90, 90, 230));
                g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {8, 6}, 0));
                g.draw(path);
                dot(g, q[0], q[1], oy, new Color(0xff5aa0), 9);
                label(g, q[0], q[1], oy, "П " + pnames[m][p], new Color(0xff8cc0));
                double len = 0;
                for (int i = 1; i < r.size(); i++) len += Math.hypot(r.get(i)[0] - r.get(i - 1)[0], r.get(i)[1] - r.get(i - 1)[1]);
                System.out.printf("%s маршрут от %s: %d углов, длина %d%n", names[m], pnames[m][p], r.size() - 2, (int) len);
            }
            // spawn and its 1 500 circle
            g.setColor(new Color(255, 170, 40, 160));
            g.setStroke(new BasicStroke(2, BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND, 1, new float[] {5, 5}, 0));
            g.draw(new Ellipse2D.Double((s[0] - 1500) * K, oy + (s[1] - 1500) * K, 3000 * K, 3000 * K));
            dot(g, s[0], s[1], oy, new Color(0xffaa28), 14);
            label(g, s[0], s[1], oy, "S бегемот", new Color(0xffc060));
            // current wait point
            int[] wc = waitCfg[m];
            g.setColor(new Color(0x5aa0ff));
            g.fill(new Rectangle2D.Double(wc[0] * K - 7, oy + wc[1] * K - 7, 14, 14));
            label(g, wc[0], wc[1], oy, "Ж сейчас", new Color(0x8cc0ff));
            // the points the user approved (2026-11-20), fixed; their distance from the real routes
            double[][][] fixedPts = {
                {{20200, 4300}, {20400, 7100}, {19300, 1900}, {20400, 4600}},
                {{6000, 3500}, {5300, 5700}, {6700, 8900}, {6900, 600}},
                {{3100, 3100}, {5700, 2500}, {2300, 700}, {4700, 600}}};
            String[] fl = {"A1 сбоку", "A2 сбоку", "B у стены", "C край (радиация)"};
            Color[] fc = {new Color(0x3ce07a), new Color(0x3ce07a), new Color(0xf0e040), new Color(0xb070ff)};
            Color[] lc = {new Color(0x7af0a8), new Color(0x7af0a8), new Color(0xf6ec80), new Color(0xc9a0ff)};
            for (int i = 0; i < 4; i++) {
                double[] pt = fixedPts[m][i];
                dot(g, pt[0], pt[1], oy, fc[i], 12);
                label(g, pt[0], pt[1], oy, fl[i], lc[i]);
                double dmin = 1e9;
                for (List<double[]> r : routes) for (int k = 1; k < r.size(); k++)
                    dmin = Math.min(dmin, lineDist(new double[] {r.get(k - 1)[0], r.get(k - 1)[1], r.get(k)[0], r.get(k)[1]}, pt[0], pt[1]));
                System.out.printf("%s %s %d/%d — до маршрута поезда %d%n", names[m], fl[i].split(" ")[0], (int) pt[0] / 100, (int) pt[1] / 100, (int) dmin);
            }
            // nearest portal
            int best = 0;
            for (int p = 1; p < 3; p++) {
                if (Math.hypot(portals[m][p][0] - s[0], portals[m][p][1] - s[1])
                        < Math.hypot(portals[m][best][0] - s[0], portals[m][best][1] - s[1])) best = p;
            }
            g.setColor(Color.WHITE);
            g.setStroke(new BasicStroke(3));
            int[] bp = portals[m][best];
            g.draw(new Ellipse2D.Double(bp[0] * K - 16, oy + bp[1] * K - 16, 32, 32));
            label(g, bp[0], bp[1] + 900, oy, "D ближайший портал", Color.WHITE);
            g.setClip(null);
            g.setFont(new Font("SansSerif", Font.BOLD, 20));
            g.setColor(Color.WHITE);
            g.drawString(names[m] + "  (" + ids[m] + ")", 10, oy - 8);
            System.out.printf("%s D %s%n", names[m], pnames[m][best]);
        }
        int ly = 3 * (ph + 40) + 30;
        g.setFont(new Font("SansSerif", Font.PLAIN, 15));
        String[] leg = {"S — место появления бегемота, пунктир — круг 1 500 вокруг него;  П — порталы;  красные полосы — маршруты поездов «портал → бегемот» в обход стен (±1 000)",
            "Ж — точка ожидания сейчас (конфиг);  A1/A2 — стоять сбоку от бегемота, на 1 700 от него, в стороне от маршрутов поездов",
            "B — отход к стене (за стену от маршрутов поездов), C — отход к краю карты (у радиации), D — ближайший портал (прыжок, крайний случай)",
            "серое — стены сервера (туда нельзя), зелёная рамка — край карты (дальше радиация)"};
        g.setColor(new Color(0xdfe6ee));
        for (int i = 0; i < leg.length; i++) g.drawString(leg[i], 10, ly + i * 24);
        ImageIO.write(out, "png", new File(dir + "/bl-maps.png"));
        System.exit(0);
    }

    static final int CELL = 100;

    /** Free for a ship: inside the map, 300+ off every wall. */
    static boolean freeCell(int cx, int cy) {
        double x = cx * CELL + CELL / 2.0, y = cy * CELL + CELL / 2.0;
        if (x < 150 || y < 150 || x > W - 150 || y > H - 150) return false;
        for (double[] w : walls) {
            if (w[3] < 0 ? Math.hypot(x - w[0], y - w[1]) < w[2] + 300
                    : x > w[0] - 300 && x < w[2] + 300 && y > w[1] - 300 && y < w[3] + 300) return false;
        }
        return true;
    }

    /** The shortest way round the walls (A* on a 100 grid, 8 directions), then straightened where the line is clear. */
    static List<double[]> route(double sx, double sy, double tx, double ty) {
        int gw = W / CELL, gh = H / CELL;
        boolean[][] free = new boolean[gw][gh];
        for (int x = 0; x < gw; x++) for (int y = 0; y < gh; y++) free[x][y] = freeCell(x, y);
        int[] st = nearestFree(free, (int) (sx / CELL), (int) (sy / CELL)), go = nearestFree(free, (int) (tx / CELL), (int) (ty / CELL));
        double[][] dist = new double[gw][gh];
        for (double[] row : dist) Arrays.fill(row, 1e18);
        int[][] from = new int[gw * gh][];
        PriorityQueue<double[]> q = new PriorityQueue<>(Comparator.comparingDouble(e -> e[0]));
        dist[st[0]][st[1]] = 0;
        q.add(new double[] {0, st[0], st[1]});
        int[][] dirs = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};
        while (!q.isEmpty()) {
            double[] e = q.poll();
            int x = (int) e[1], y = (int) e[2];
            if (x == go[0] && y == go[1]) break;
            for (int[] d : dirs) {
                int nx = x + d[0], ny = y + d[1];
                if (nx < 0 || ny < 0 || nx >= gw || ny >= gh || !free[nx][ny]) continue;
                if (d[0] != 0 && d[1] != 0 && (!free[x + d[0]][y] || !free[x][y + d[1]])) continue;
                double nd = dist[x][y] + Math.hypot(d[0], d[1]);
                if (nd < dist[nx][ny]) {
                    dist[nx][ny] = nd;
                    from[nx * gh + ny] = new int[] {x, y};
                    q.add(new double[] {nd + Math.hypot(go[0] - nx, go[1] - ny), nx, ny});
                }
            }
        }
        List<int[]> cells = new ArrayList<>();
        int[] c = go;
        while (c != null && !(c[0] == st[0] && c[1] == st[1])) { cells.add(c); c = from[c[0] * gh + c[1]]; }
        cells.add(st);
        Collections.reverse(cells);
        // string pulling
        List<double[]> pts = new ArrayList<>();
        pts.add(new double[] {sx, sy});
        int i = 0;
        while (i < cells.size() - 1) {
            int j = cells.size() - 1;
            while (j > i + 1 && !clear(free, cells.get(i), cells.get(j))) j--;
            pts.add(new double[] {cells.get(j)[0] * CELL + CELL / 2.0, cells.get(j)[1] * CELL + CELL / 2.0});
            i = j;
        }
        pts.add(new double[] {tx, ty});
        return pts;
    }

    static int[] nearestFree(boolean[][] free, int x, int y) {
        for (int r = 0; r < 60; r++)
            for (int dx = -r; dx <= r; dx++) for (int dy = -r; dy <= r; dy++) {
                int nx = x + dx, ny = y + dy;
                if (nx >= 0 && ny >= 0 && nx < free.length && ny < free[0].length && free[nx][ny]) return new int[] {nx, ny};
            }
        return new int[] {x, y};
    }

    static boolean clear(boolean[][] free, int[] a, int[] b) {
        int n = (int) Math.ceil(Math.hypot(b[0] - a[0], b[1] - a[1]) * 2);
        for (int k = 0; k <= n; k++) {
            double t = n == 0 ? 0 : (double) k / n;
            int x = (int) Math.round(a[0] + t * (b[0] - a[0])), y = (int) Math.round(a[1] + t * (b[1] - a[1]));
            if (!free[x][y]) return false;
        }
        return true;
    }

    static boolean blocked(double x, double y) {
        if (x < 500 || y < 500 || x > W - 500 || y > H - 500) return true;
        for (double[] w : walls) {
            if (w[3] < 0 ? Math.hypot(x - w[0], y - w[1]) < w[2] + 300
                    : x > w[0] - 300 && x < w[2] + 300 && y > w[1] - 300 && y < w[3] + 300) return true;
        }
        return false;
    }

    static double lineDist(double[] l, double x, double y) {
        double dx = l[2] - l[0], dy = l[3] - l[1];
        double t = Math.max(0, Math.min(1, ((x - l[0]) * dx + (y - l[1]) * dy) / (dx * dx + dy * dy)));
        return Math.hypot(x - (l[0] + t * dx), y - (l[1] + t * dy));
    }

    static double offLines(List<double[]> lines, double x, double y) {
        double m = 1e9;
        for (double[] l : lines) m = Math.min(m, lineDist(l, x, y));
        return m;
    }

    /** Two points on a 1 700 ring round the spawn, farthest from the trains' lines, 60°+ apart. */
    static List<double[]> sides(int[] s, List<double[]> lines, int[][] portals) {
        List<double[]> c = new ArrayList<>();
        for (int a = 0; a < 72; a++) {
            double ang = a * Math.PI / 36;
            double x = s[0] + 1700 * Math.cos(ang), y = s[1] + 1700 * Math.sin(ang);
            if (blocked(x, y)) continue;
            c.add(new double[] {x, y, offLines(lines, x, y), ang});
        }
        c.sort((p, q) -> Double.compare(q[2], p[2]));
        List<double[]> out = new ArrayList<>();
        for (double[] p : c) {
            boolean far = out.stream().allMatch(o -> Math.abs(Math.atan2(Math.sin(o[3] - p[3]), Math.cos(o[3] - p[3]))) > Math.PI / 3);
            if (far) out.add(p);
            if (out.size() == 2) break;
        }
        return out;
    }

    /** A free point next to a wall (within 700 of it), 1 500–4 000 from the spawn, farthest from the lines. */
    static double[] byWall(int[] s, List<double[]> lines) {
        double[] best = null;
        for (int x = 500; x < W; x += 200) {
            for (int y = 500; y < H; y += 200) {
                double d = Math.hypot(x - s[0], y - s[1]);
                if (d < 1500 || d > 4000 || blocked(x, y) || !nearWall(x, y)) continue;
                double off = offLines(lines, x, y);
                if (best == null || off > best[2]) best = new double[] {x, y, off};
            }
        }
        return best;
    }

    static boolean nearWall(double x, double y) {
        for (double[] w : walls) {
            if (w[3] < 0 ? Math.hypot(x - w[0], y - w[1]) < w[2] + 700
                    : x > w[0] - 700 && x < w[2] + 700 && y > w[1] - 700 && y < w[3] + 700) return true;
        }
        return false;
    }

    /** A free point 600 from the map's edge, nearest the spawn among those 1 500+ off the lines. */
    static double[] byEdge(int[] s, List<double[]> lines) {
        double[] best = null;
        double bestD = 1e9;
        for (int t = 0; t < 4; t++) {
            for (int v = 600; v < (t < 2 ? W : H) - 600; v += 100) {
                double x = t == 0 ? v : t == 1 ? v : t == 2 ? 600 : W - 600;
                double y = t == 0 ? 600 : t == 1 ? H - 600 : v;
                if (blocked(x, y) || offLines(lines, x, y) < 1500) continue;
                double d = Math.hypot(x - s[0], y - s[1]);
                if (d < bestD) { bestD = d; best = new double[] {x, y}; }
            }
        }
        return best;
    }

    static void dot(Graphics2D g, double x, double y, int oy, Color c, int r) {
        g.setColor(Color.BLACK);
        g.fill(new Ellipse2D.Double(x * K - r / 2.0 - 2, oy + y * K - r / 2.0 - 2, r + 4, r + 4));
        g.setColor(c);
        g.fill(new Ellipse2D.Double(x * K - r / 2.0, oy + y * K - r / 2.0, r, r));
    }

    static void label(Graphics2D g, double x, double y, int oy, String t, Color c) {
        g.setFont(new Font("SansSerif", Font.BOLD, 14));
        int tx = (int) (x * K) + 10, ty = (int) (oy + y * K) - 8;
        if (tx + g.getFontMetrics().stringWidth(t) > 1395) tx = (int) (x * K) - 10 - g.getFontMetrics().stringWidth(t);
        g.setColor(Color.BLACK);
        g.drawString(t, tx + 1, ty + 1);
        g.setColor(c);
        g.drawString(t, tx, ty);
    }
}
