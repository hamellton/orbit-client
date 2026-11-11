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
 * the way DarkBot stores them). Drag with the left button to paint, with the right one to erase.
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
        view = new Form()
                .section("Зоны")
                .note("ЛКМ — закрасить, ПКМ — стереть. Зоны свои для каждой карты.")
                .row("Карта", map)
                .row("Слой", Form.inline(preferred, avoided, clear))
                .wide(canvas, true)
                .build(false);
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

    /** The map with its grid. */
    private final class Canvas extends JPanel {

        private Boolean paintValue;

        Canvas() {
            setPreferredSize(new Dimension(640, 420));
            setBackground(new Color(18, 22, 30));
            MouseAdapter m = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    paintValue = !SwingUtilities.isRightMouseButton(e);
                    paintAt(e);
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    paintAt(e);
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    paintValue = null;
                }
            };
            addMouseListener(m);
            addMouseMotionListener(m);
        }

        private java.awt.Rectangle area() {
            MapCatalog.MapInfo info = catalog.info(map.mapId());
            double aspect = (double) info.width() / info.height();
            int w = getWidth() - 20;
            int h = getHeight() - 20;
            if (w / aspect > h) {
                w = (int) (h * aspect);
            } else {
                h = (int) (w / aspect);
            }
            return new java.awt.Rectangle((getWidth() - w) / 2, (getHeight() - h) / 2, w, h);
        }

        private void paintAt(MouseEvent e) {
            if (paintValue == null) {
                return;
            }
            java.awt.Rectangle a = area();
            int x = (e.getX() - a.x) * RESOLUTION / Math.max(1, a.width);
            int y = (e.getY() - a.y) * RESOLUTION / Math.max(1, a.height);
            if (x >= 0 && y >= 0 && x < RESOLUTION && y < RESOLUTION) {
                layer(map.mapId())[y][x] = paintValue;
                repaint();
            }
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
            g.setColor(new Color(120, 140, 170));
            g.setStroke(new BasicStroke(1.5f));
            g.drawRect(a.x, a.y, a.width, a.height);
            g.setFont(g.getFont().deriveFont(11f));
            for (MapCatalog.Portal p : catalog.portals(info.id())) {
                int px = a.x + (int) ((double) p.x() / info.width() * a.width);
                int py = a.y + (int) ((double) p.y() / info.height() * a.height);
                g.setColor(new Color(240, 240, 240));
                g.drawOval(px - 6, py - 6, 12, 12);
                g.drawString(p.toName(), px + 8, py + 4);
            }
            for (BotConfig.SafetySpot s : spots.getOrDefault(info.id(), List.of())) {
                int px = a.x + (int) ((double) s.x() / info.width() * a.width);
                int py = a.y + (int) ((double) s.y() / info.height() * a.height);
                g.setColor(new Color(90, 170, 255));
                g.fillOval(px - 3, py - 3, 6, 6);
            }
            g.setColor(Color.LIGHT_GRAY);
            g.drawString(info.name() + "   " + info.width() / 100 + "×" + info.height() / 100, a.x + 6, a.y + 16);
        }
    }
}
