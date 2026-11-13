package orbit.app.gui.config;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.SwingUtilities;
import orbit.config.BotConfig;
import orbit.world.maps.MapCatalog;

/**
 * «Зоны»: preferred and avoided areas drawn on the map, cell by cell (a 30 × 30 grid over the map,
 * the way DarkBot stores them), edited the way DarkBot's zone editor does it: drag a frame over the cells (a
 * click is a frame of one); on release, a frame whose cells are all like the first one flips them, a mixed one
 * is filled by the left button and cleared by the right; the middle button flips every cell on its own.
 */
final class ZonesSection implements Section {

    /** The grid's usual size (DarkBot's «zone resolution»); finer ones for small zones (AstraBot's are ~300 units). */
    static final int RESOLUTION = 30;
    static final Integer[] RESOLUTIONS = {30, 70, 100};
    /** The cells a side now: the finest grid of the config, else the usual. */
    private int res = RESOLUTION;
    private final javax.swing.JComboBox<Integer> resBox = new javax.swing.JComboBox<>(RESOLUTIONS);
    private static final Color PREFERRED = new Color(70, 190, 90, 120);
    private static final Color AVOIDED = new Color(220, 70, 60, 120);
    // DarkBot's colour scheme (ColorScheme: BARRIER, BARRIER_BORDER, NO_CLOACK, BASES, BASE_SPOTS, PORTALS)
    private static final Color BARRIER = new Color(255, 255, 255, 0x30);
    private static final Color BARRIER_BORDER = new Color(255, 255, 255, 0x80);
    private static final Color MIST = new Color(0x18, 0xa0, 0xff, 0x30);
    private static final Color BASE = new Color(0x00, 0xff, 0x80);
    private static final Color BASE_SPOT = new Color(0x00, 0xff, 0x80, 0x24);
    private static final Color PORTAL = new Color(0xae, 0xae, 0xae);
    private static final Color ENEMY = new Color(0xd0, 0x40, 0x40);
    private static final Color ALLY = new Color(0x40, 0xb0, 0x60);

    private final MapCatalog catalog;
    private final MapBox map;
    private final JRadioButton preferred = new JRadioButton("Предпочтительная зона", true);
    private final JRadioButton avoided = new JRadioButton("Запретная зона");
    private final Canvas canvas = new Canvas();
    private final Map<Integer, boolean[][][]> grids = new TreeMap<>(); // map → [layer][y][x]
    private Map<Integer, List<BotConfig.SafetySpot>> spots = Map.of();
    private final JComponent view;

    ZonesSection(MapCatalog catalog) {
        this.catalog = catalog;
        this.map = new MapBox(catalog);
        map.addActionListener(e -> canvas.repaint());
        orbit.app.gui.MinimapBackgrounds.onLoaded(() -> javax.swing.SwingUtilities.invokeLater(canvas::repaint));
        ButtonGroup g = new ButtonGroup();
        g.add(preferred);
        g.add(avoided);
        JButton clear = new JButton("Очистить");
        clear.addActionListener(e -> {
            boolean[][] layer = layer(map.mapId());
            for (boolean[] row : layer) {
                java.util.Arrays.fill(row, false);
            }
            canvas.repaint();
        });
        // never scrolls: the controls in one line (wrapping when narrow), the whole map in the rest of the tab
        javax.swing.JLabel help = new javax.swing.JLabel("ⓘ");
        help.setForeground(Color.GRAY);
        help.setToolTipText("<html>Как в DarkBot: выделите рамкой (или кликните клетку).<br>Одинаковые клетки — "
                + "переключатся; разные — ЛКМ закрасит, ПКМ сотрёт;<br>колесо — каждую наоборот. Зоны свои для каждой карты.</html>");
        canvas.setToolTipText(null);
        preferred.setText("Предпочтительная");
        avoided.setText("Запретная");
        JPanel controls = new JPanel(new orbit.app.gui.WrapLayout(java.awt.FlowLayout.LEFT, 8, 4));
        controls.add(new javax.swing.JLabel("Карта"));
        controls.add(map);
        controls.add(new javax.swing.JLabel("   Зона"));
        controls.add(preferred);
        controls.add(avoided);
        controls.add(clear);
        controls.add(new javax.swing.JLabel("   Сетка"));
        controls.add(resBox);
        resBox.setToolTipText("Клеток по стороне карты: 30 — как в DarkBot, 70 и 100 — для маленьких зон (как у AstraBot)");
        resBox.setEditable(true);
        resBox.addActionListener(e -> {
            Object v = resBox.getSelectedItem();
            int n;
            try {
                n = v instanceof Integer i ? i : Integer.parseInt(String.valueOf(v).strip());
            } catch (NumberFormatException ex) {
                return;
            }
            n = Math.max(10, Math.min(200, n));
            if (n != res) {
                regrid(n);
            }
        });
        controls.add(help);
        JPanel page = new JPanel(new java.awt.BorderLayout(0, 4));
        page.setBorder(javax.swing.BorderFactory.createEmptyBorder(6, 6, 8, 8));
        page.add(controls, java.awt.BorderLayout.NORTH);
        page.add(canvas, java.awt.BorderLayout.CENTER);
        view = page;
    }

    @Override
    public String title() {
        return "Зоны";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        grids.clear();
        // the finest grid of the file, so nothing is lost when it is shown and saved again
        int finest = RESOLUTION;
        for (BotConfig.Zones z : c.zones().values()) {
            for (BotConfig.ZoneGrid zg : new BotConfig.ZoneGrid[] {z.preferred(), z.avoided()}) {
                if (zg != null) {
                    finest = Math.max(finest, zg.resolution());
                }
            }
        }
        res = finest;
        resBox.setSelectedItem(res);
        c.zones().forEach((id, z) -> {
            boolean[][][] g = new boolean[2][res][res];
            fill(g[0], z.preferred());
            fill(g[1], z.avoided());
            grids.put(id, g);
        });
        spots = c.safetySpots();
        map.setMap(c.general().workingMap());
        canvas.repaint();
    }

    @Override
    public BotConfig apply(BotConfig c) {
        Map<Integer, BotConfig.Zones> out = new TreeMap<>();
        grids.forEach((id, g) -> {
            BotConfig.ZoneGrid p = grid(g[0]);
            BotConfig.ZoneGrid a = grid(g[1]);
            if (p != null || a != null) {
                out.put(id, new BotConfig.Zones(p, a));
            }
        });
        return c.withZones(out);
    }

    /** Rescales a stored grid of another resolution onto ours. */
    private void fill(boolean[][] target, BotConfig.ZoneGrid g) {
        if (g == null || g.resolution() <= 0) {
            return;
        }
        for (int y = 0; y < res; y++) {
            for (int x = 0; x < res; x++) {
                int sy = y * g.resolution() / res;
                int sx = x * g.resolution() / res;
                String row = sy < g.rows().size() ? g.rows().get(sy) : "";
                target[y][x] = sx < row.length() && row.charAt(sx) == '#';
            }
        }
    }

    private BotConfig.ZoneGrid grid(boolean[][] cells) {
        List<String> rows = new ArrayList<>();
        boolean any = false;
        for (boolean[] r : cells) {
            StringBuilder b = new StringBuilder();
            for (boolean on : r) {
                b.append(on ? '#' : '.');
                any |= on;
            }
            rows.add(b.toString());
        }
        return any ? new BotConfig.ZoneGrid(cells.length, rows) : null;
    }

    /** The same zones on a grid of {@code n} cells a side. */
    private void regrid(int n) {
        Map<Integer, boolean[][][]> before = new TreeMap<>(grids);
        int old = res;
        res = n;
        grids.clear();
        before.forEach((id, g) -> {
            boolean[][][] ng = new boolean[2][n][n];
            for (int l = 0; l < 2; l++) {
                for (int y = 0; y < n; y++) {
                    for (int x = 0; x < n; x++) {
                        ng[l][y][x] = g[l][y * old / n][x * old / n];
                    }
                }
            }
            grids.put(id, ng);
        });
        canvas.repaint();
    }

    private boolean[][] layer(int mapId) {
        return grids.computeIfAbsent(mapId, k -> new boolean[2][res][res])[preferred.isSelected() ? 0 : 1];
    }

    /** Below this width the map is drawn without names. */
    private static final int SMALL = 240;

    /** The map with its grid. */
    private final class Canvas extends JPanel {

        /** The frame being dragged: where it started and where the mouse is (cells), or {@code null}. */
        private int[] from;
        private int[] to;
        /** The cell under the mouse, for the highlight. */
        private int[] hover;

        Canvas() {
            setPreferredSize(new Dimension(640, 420));
            setMinimumSize(new Dimension(60, 40)); // the map scales down to fit whatever room is left
            setBackground(new Color(18, 22, 30));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    from = cell(e);
                    to = from;
                    repaint();
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    int[] c = cell(e);
                    hover = c;
                    if (from != null && c != null) {
                        to = c;
                    }
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (from != null && to != null) {
                        apply(SwingUtilities.isMiddleMouseButton(e), SwingUtilities.isLeftMouseButton(e));
                    }
                    from = null;
                    to = null;
                    repaint();
                }

                @Override
                public void mouseMoved(MouseEvent e) {
                    hover = cell(e);
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = null;
                    repaint();
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private java.awt.Rectangle area() {
            MapCatalog.MapInfo info = catalog.info(map.mapId());
            double aspect = (double) info.width() / info.height();
            int w = getWidth() - 4;
            int h = getHeight() - 4;
            if (w / aspect > h) {
                w = (int) (h * aspect);
            } else {
                h = (int) (w / aspect);
            }
            return new java.awt.Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        /** The grid cell under the mouse, or {@code null} off the map. */
        private int[] cell(MouseEvent e) {
            java.awt.Rectangle a = area();
            int x = (e.getX() - a.x) * res / Math.max(1, a.width);
            int y = (e.getY() - a.y) * res / Math.max(1, a.height);
            return x >= 0 && y >= 0 && x < res && y < res ? new int[] {x, y} : null;
        }

        /**
         * DarkBot's rule for the released frame: the middle button flips each cell; otherwise, all cells like
         * the first one — flip them all; mixed — the left button fills, the right one clears.
         */
        private void apply(boolean each, boolean left) {
            frame(layer(map.mapId()), from[0], from[1], to[0], to[1], each, left);
        }

        /** A cell's rectangle on the screen. */
        private java.awt.Rectangle cellRect(java.awt.Rectangle a, int x0, int y0, int x1, int y1) {
            int sx = a.x + x0 * a.width / res;
            int sy = a.y + y0 * a.height / res;
            return new java.awt.Rectangle(sx, sy, a.x + (x1 + 1) * a.width / res - sx,
                    a.y + (y1 + 1) * a.height / res - sy);
        }

        private double sx(java.awt.Rectangle a, MapCatalog.MapInfo info, double x) {
            return a.x + x / info.width() * a.width;
        }

        private double sy(java.awt.Rectangle a, MapCatalog.MapInfo info, double y) {
            return a.y + y / info.height() * a.height;
        }

        /**
         * The zones the bot saw here, drawn as DarkBot draws them (the user, 2026-11-13): no-access zones as barriers
         * (white, a light fill and a border), damage, radiation, mines and the like as a blue mist, a base's no-fight
         * ring as its green spot.
         */
        private void features(Graphics2D g, java.awt.Rectangle a, MapCatalog.MapInfo info) {
            for (orbit.app.MapFeatures.Obstacle o : orbit.app.MapFeatures.shared().obstacles(info.id())) {
                java.awt.Shape sh = shape(a, info, o);
                if (sh == null) {
                    continue;
                }
                if (o.inverted()) {
                    java.awt.geom.Path2D.Double outside = new java.awt.geom.Path2D.Double(java.awt.geom.Path2D.WIND_EVEN_ODD);
                    outside.append(a, false);
                    outside.append(sh, false);
                    sh = outside;
                }
                if (o.type() == orbit.app.MapFeatures.BASE_ZONE) {
                    g.setColor(BASE_SPOT);
                    g.fill(sh);
                } else if (o.type() == 5 || o.type() == 6) { // NO_ACCESS, FACTION_NO_ACCESS: DarkBot's barriers
                    g.setColor(BARRIER);
                    g.fill(sh);
                    g.setColor(BARRIER_BORDER);
                    g.setStroke(new BasicStroke(1f));
                    g.draw(sh);
                } else { // damage, radiation, mine fields, cages, asteroids: DarkBot's mist
                    g.setColor(MIST);
                    g.fill(sh);
                }
            }
        }

        /** The portals of the map (DarkBot: a grey ring with the map it leads to). */
        private void portals(Graphics2D g, java.awt.Rectangle a, MapCatalog.MapInfo info) {
            g.setColor(PORTAL);
            g.setStroke(new BasicStroke(1.2f));
            for (MapCatalog.Portal pt : catalog.portals(info.id())) {
                double x = sx(a, info, pt.x());
                double y = sy(a, info, pt.y());
                g.draw(new java.awt.geom.Ellipse2D.Double(x - 6, y - 6, 12, 12));
                if (a.width >= SMALL && pt.toName() != null) {
                    java.awt.FontMetrics fm = g.getFontMetrics();
                    g.drawString(pt.toName(), (int) x - fm.stringWidth(pt.toName()) / 2, (int) y - 9);
                }
            }
        }

        private java.awt.Shape shape(java.awt.Rectangle a, MapCatalog.MapInfo info, orbit.app.MapFeatures.Obstacle o) {
            List<Integer> c = o.coords();
            if (o.shape() == 0) { // circle
                if (c.size() < 3) {
                    return null;
                }
                double rx = (double) c.get(2) / info.width() * a.width;
                double ry = (double) c.get(2) / info.height() * a.height;
                return new java.awt.geom.Ellipse2D.Double(sx(a, info, c.get(0)) - rx, sy(a, info, c.get(1)) - ry, 2 * rx, 2 * ry);
            }
            if (c.size() < 4) {
                return null;
            }
            if (o.shape() == 2) { // rectangle: its corners
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
                return new java.awt.geom.Rectangle2D.Double(sx(a, info, minX), sy(a, info, minY),
                        sx(a, info, maxX) - sx(a, info, minX), sy(a, info, maxY) - sy(a, info, minY));
            }
            java.awt.geom.Path2D.Double poly = new java.awt.geom.Path2D.Double();
            poly.moveTo(sx(a, info, c.get(0)), sy(a, info, c.get(1)));
            for (int i = 2; i + 1 < c.size(); i += 2) {
                poly.lineTo(sx(a, info, c.get(i)), sy(a, info, c.get(i + 1)));
            }
            poly.closePath();
            return poly;
        }

        /**
         * Stations as DarkBot draws them: a base's spots as green circles (HQ 3 500 across, the outpost HQ 3 000, the
         * other stations 1 000), a clan battle station as an oval — red another company's or a hostile clan's, green
         * the own or an allied one — with its clan tag; the base's name beside its HQ.
         */
        private void stations(Graphics2D g, java.awt.Rectangle a, MapCatalog.MapInfo info) {
            String[] company = {"", "MMO", "EIC", "VRU"};
            for (orbit.app.MapFeatures.Station st : orbit.app.MapFeatures.shared().stations(info.id())) {
                double x = sx(a, info, st.x());
                double y = sy(a, info, st.y());
                if (st.type() == 36) {
                    g.setColor(st.faction() != 0 && st.faction() == ownCompany(info) ? ALLY : ENEMY);
                    g.fill(new java.awt.geom.Ellipse2D.Double(x - 5.5, y - 4.5, 11, 9));
                    if (a.width >= SMALL) {
                        String name = st.clanTag().isEmpty() ? st.name() : "[" + st.clanTag() + "] " + st.name();
                        java.awt.FontMetrics fm = g.getFontMetrics();
                        g.drawString(name, (int) x - fm.stringWidth(name) / 2, (int) y - 8);
                    }
                    continue;
                }
                double across = st.type() == 46 ? 3_500 : st.type() == 47 ? 3_000 : 1_000;
                double rx = across / 2 / info.width() * a.width;
                double ry = across / 2 / info.height() * a.height;
                g.setColor(BASE_SPOT);
                g.fill(new java.awt.geom.Ellipse2D.Double(x - rx, y - ry, 2 * rx, 2 * ry));
                g.setColor(BASE);
                g.fill(new java.awt.geom.Ellipse2D.Double(x - 2, y - 2, 4, 4));
                if ((st.type() == 46 || st.type() == 47) && a.width >= SMALL) {
                    String name = st.faction() == 0 ? "общая база" : "база " + (st.faction() < company.length
                            ? company[st.faction()] : String.valueOf(st.faction()));
                    java.awt.FontMetrics fm = g.getFontMetrics();
                    g.drawString(name, (int) x - fm.stringWidth(name) / 2, (int) (y + ry) + 12);
                }
            }
        }

        /** The company whose home the map is (its battle stations are friends), 0 for none. */
        private int ownCompany(MapCatalog.MapInfo info) {
            return info.homeFaction();
        }

        @Override
        protected void paintComponent(Graphics g0) {
            super.paintComponent(g0);
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            java.awt.Rectangle a = area();
            MapCatalog.MapInfo info = catalog.info(map.mapId());
            g.setColor(new Color(28, 34, 46));
            g.fillRect(a.x, a.y, a.width, a.height);
            // the game's own picture of the map, dimmed, then what the bot saw on it: obstacles and stations
            java.awt.image.BufferedImage bg = orbit.app.gui.MinimapBackgrounds.get(map.mapId());
            if (bg != null) {
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(bg, a.x, a.y, a.width, a.height, null);
                g.setColor(new Color(8, 10, 16, 120));
                g.fillRect(a.x, a.y, a.width, a.height);
            }
            features(g, a, info);
            boolean[][][] layers = grids.get(map.mapId());
            if (layers != null) {
                for (int l = 0; l < 2; l++) {
                    g.setColor(l == 0 ? PREFERRED : AVOIDED);
                    for (int y = 0; y < res; y++) {
                        for (int x = 0; x < res; x++) {
                            if (layers[l][y][x]) {
                                int x0 = a.x + x * a.width / res;
                                int y0 = a.y + y * a.height / res;
                                g.fillRect(x0, y0, a.x + (x + 1) * a.width / res - x0,
                                        a.y + (y + 1) * a.height / res - y0);
                            }
                        }
                    }
                }
            }
            // a fine grid's lines fainter, not to hide the map
            g.setColor(new Color(255, 255, 255, res > 40 ? 8 : 18));
            for (int i = 1; i < res; i++) {
                g.drawLine(a.x + i * a.width / res, a.y, a.x + i * a.width / res, a.y + a.height);
                g.drawLine(a.x, a.y + i * a.height / res, a.x + a.width, a.y + i * a.height / res);
            }
            // what the mouse would change: the frame being dragged, or the cell under it
            Color mark = preferred.isSelected() ? PREFERRED : AVOIDED;
            if (from != null && to != null) {
                java.awt.Rectangle r = cellRect(a, Math.min(from[0], to[0]), Math.min(from[1], to[1]),
                        Math.max(from[0], to[0]), Math.max(from[1], to[1]));
                g.setColor(new Color(mark.getRed(), mark.getGreen(), mark.getBlue(), 60));
                g.fill(r);
                g.setColor(new Color(mark.getRed(), mark.getGreen(), mark.getBlue(), 220));
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10f, new float[] {5f, 4f}, 0f));
                g.draw(r);
            } else if (hover != null) {
                g.setColor(new Color(mark.getRed(), mark.getGreen(), mark.getBlue(), 200));
                g.setStroke(new BasicStroke(1.5f));
                g.draw(cellRect(a, hover[0], hover[1], hover[0], hover[1]));
            }
            g.setColor(new Color(120, 140, 170));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(a.x, a.y, a.width, a.height);
            g.setFont(g.getFont().deriveFont(11f));
            portals(g, a, info);
            stations(g, a, info);
            for (BotConfig.SafetySpot s : spots.getOrDefault(info.id(), List.of())) {
                int px = a.x + (int) ((double) s.x() / info.width() * a.width);
                int py = a.y + (int) ((double) s.y() / info.height() * a.height);
                g.setColor(new Color(90, 170, 255));
                g.fillOval(px - 3, py - 3, 6, 6);
            }
            if (a.width >= SMALL) { // a tiny map keeps only its marks: the names would cover it
                g.setColor(Color.LIGHT_GRAY);
                g.drawString(info.name() + "   " + info.width() / 100 + "×" + info.height() / 100, a.x + 6, a.y + 16);
            }
        }
    }

    /**
     * DarkBot's zone editor rule for a frame from the cell (fx, fy) to (tx, ty): {@code each} (middle button) flips
     * every cell; otherwise, all cells like the first one — all flipped; mixed — {@code left} fills, else clears.
     */
    static void frame(boolean[][] layer, int fx, int fy, int tx, int ty, boolean each, boolean left) {
        int x0 = Math.min(fx, tx);
        int x1 = Math.max(fx, tx);
        int y0 = Math.min(fy, ty);
        int y1 = Math.max(fy, ty);
        boolean first = layer[fy][fx];
        boolean mixed = false;
        for (int y = y0; y <= y1 && !mixed; y++) {
            for (int x = x0; x <= x1; x++) {
                if (layer[y][x] != first) {
                    mixed = true;
                    break;
                }
            }
        }
        for (int y = y0; y <= y1; y++) {
            for (int x = x0; x <= x1; x++) {
                layer[y][x] = each ? !layer[y][x] : mixed ? left : !first;
            }
        }
    }
}
