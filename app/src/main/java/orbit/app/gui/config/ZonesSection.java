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
 * the way DarkBot stores them). «Рисовать / Стирать» says what the left button does (the right one does the
 * opposite); «Кисть» paints cell by cell while dragging, «Прямоугольник» fills the dragged frame on release, as
 * DarkBot's editor does.
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
    private final javax.swing.JRadioButton draw = new javax.swing.JRadioButton("Рисовать");
    private final javax.swing.JRadioButton erase = new javax.swing.JRadioButton("Стирать");
    private final javax.swing.JComboBox<String> shape = new javax.swing.JComboBox<>(new String[] {"Кисть", "Прямоугольник"});

    ZonesSection(MapCatalog catalog) {
        this.catalog = catalog;
        this.map = new MapBox(catalog);
        map.addActionListener(e -> canvas.repaint());
        ButtonGroup g = new ButtonGroup();
        g.add(preferred);
        g.add(avoided);
        ButtonGroup act = new ButtonGroup();
        act.add(draw);
        act.add(erase);
        draw.setSelected(true);
        shape.setToolTipText("Кисть — по клеткам, пока ведёте; прямоугольник — вся рамка при отпускании");
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
        help.setToolTipText("ЛКМ — как выбрано (рисовать или стирать), ПКМ — наоборот. Зоны свои для каждой карты.");
        canvas.setToolTipText(null);
        preferred.setText("Предпочтительная");
        avoided.setText("Запретная");
        JPanel controls = new JPanel(new orbit.app.gui.WrapLayout(java.awt.FlowLayout.LEFT, 8, 4));
        controls.add(new javax.swing.JLabel("Карта"));
        controls.add(map);
        controls.add(new javax.swing.JLabel("   Зона"));
        controls.add(preferred);
        controls.add(avoided);
        controls.add(new javax.swing.JLabel("   "));
        controls.add(draw);
        controls.add(erase);
        controls.add(shape);
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

        private Boolean paintValue;
        /** The rectangle tool: where the drag started and where the mouse is (cells), or {@code null}. */
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
                    boolean left = !SwingUtilities.isRightMouseButton(e);
                    paintValue = left == draw.isSelected(); // the right button does the opposite
                    if (rectangle()) {
                        from = cell(e);
                        to = from;
                        repaint();
                    } else {
                        paintAt(e);
                    }
                }

                @Override
                public void mouseDragged(MouseEvent e) {
                    hover = cell(e);
                    if (rectangle()) {
                        if (from != null) {
                            int[] c = cell(e);
                            to = c != null ? c : to;
                            repaint();
                        }
                    } else {
                        paintAt(e);
                    }
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    if (rectangle() && from != null && to != null && paintValue != null) {
                        boolean[][] layer = layer(map.mapId());
                        for (int y = Math.min(from[1], to[1]); y <= Math.max(from[1], to[1]); y++) {
                            for (int x = Math.min(from[0], to[0]); x <= Math.max(from[0], to[0]); x++) {
                                layer[y][x] = paintValue;
                            }
                        }
                    }
                    from = null;
                    to = null;
                    paintValue = null;
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

        private boolean rectangle() {
            return shape.getSelectedIndex() == 1;
        }

        /** The grid cell under the mouse, or {@code null} off the map. */
        private int[] cell(MouseEvent e) {
            java.awt.Rectangle a = area();
            int x = (e.getX() - a.x) * RESOLUTION / Math.max(1, a.width);
            int y = (e.getY() - a.y) * RESOLUTION / Math.max(1, a.height);
            return x >= 0 && y >= 0 && x < RESOLUTION && y < RESOLUTION ? new int[] {x, y} : null;
        }

        private void paintAt(MouseEvent e) {
            if (paintValue == null) {
                return;
            }
            int[] c = cell(e);
            if (c != null) {
                layer(map.mapId())[c[1]][c[0]] = paintValue;
                repaint();
            }
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
            boolean erasing = paintValue != null ? !paintValue : erase.isSelected();
            Color mark = erasing ? new Color(255, 255, 255, 150) : (preferred.isSelected() ? PREFERRED : AVOIDED);
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
}
