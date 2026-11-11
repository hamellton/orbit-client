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

    static final int RESOLUTION = 30;
    private static final Color PREFERRED = new Color(70, 190, 90, 120);
    private static final Color AVOIDED = new Color(220, 70, 60, 120);

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
        c.zones().forEach((id, z) -> {
            boolean[][][] g = new boolean[2][RESOLUTION][RESOLUTION];
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
    private static void fill(boolean[][] target, BotConfig.ZoneGrid g) {
        if (g == null || g.resolution() <= 0) {
            return;
        }
        for (int y = 0; y < RESOLUTION; y++) {
            for (int x = 0; x < RESOLUTION; x++) {
                int sy = y * g.resolution() / RESOLUTION;
                int sx = x * g.resolution() / RESOLUTION;
                String row = sy < g.rows().size() ? g.rows().get(sy) : "";
                target[y][x] = sx < row.length() && row.charAt(sx) == '#';
            }
        }
    }

    private static BotConfig.ZoneGrid grid(boolean[][] cells) {
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
        return any ? new BotConfig.ZoneGrid(RESOLUTION, rows) : null;
    }

    private boolean[][] layer(int mapId) {
        return grids.computeIfAbsent(mapId, k -> new boolean[2][RESOLUTION][RESOLUTION])[preferred.isSelected() ? 0 : 1];
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
            int x = (e.getX() - a.x) * RESOLUTION / Math.max(1, a.width);
            int y = (e.getY() - a.y) * RESOLUTION / Math.max(1, a.height);
            return x >= 0 && y >= 0 && x < RESOLUTION && y < RESOLUTION ? new int[] {x, y} : null;
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
            int sx = a.x + x0 * a.width / RESOLUTION;
            int sy = a.y + y0 * a.height / RESOLUTION;
            return new java.awt.Rectangle(sx, sy, a.x + (x1 + 1) * a.width / RESOLUTION - sx,
                    a.y + (y1 + 1) * a.height / RESOLUTION - sy);
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
            boolean[][][] layers = grids.get(map.mapId());
            if (layers != null) {
                for (int l = 0; l < 2; l++) {
                    g.setColor(l == 0 ? PREFERRED : AVOIDED);
                    for (int y = 0; y < RESOLUTION; y++) {
                        for (int x = 0; x < RESOLUTION; x++) {
                            if (layers[l][y][x]) {
                                int x0 = a.x + x * a.width / RESOLUTION;
                                int y0 = a.y + y * a.height / RESOLUTION;
                                g.fillRect(x0, y0, a.x + (x + 1) * a.width / RESOLUTION - x0,
                                        a.y + (y + 1) * a.height / RESOLUTION - y0);
                            }
                        }
                    }
                }
            }
            g.setColor(new Color(255, 255, 255, 18));
            for (int i = 1; i < RESOLUTION; i++) {
                g.drawLine(a.x + i * a.width / RESOLUTION, a.y, a.x + i * a.width / RESOLUTION, a.y + a.height);
                g.drawLine(a.x, a.y + i * a.height / RESOLUTION, a.x + a.width, a.y + i * a.height / RESOLUTION);
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
            for (MapCatalog.Portal p : catalog.portals(info.id())) {
                int px = a.x + (int) ((double) p.x() / info.width() * a.width);
                int py = a.y + (int) ((double) p.y() / info.height() * a.height);
                g.setColor(new Color(240, 240, 240));
                g.drawOval(px - 6, py - 6, 12, 12);
                if (a.width >= SMALL) {
                    g.drawString(p.toName(), px + 8, py + 4);
                }
            }
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
