package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics2D;
import java.awt.LayoutManager;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import orbit.app.WorldFeed;

/**
 * The bot window. One account fills it; several get a tab each, like a browser, plus «Все» with every
 * chosen account's map side by side. Everything follows the window's size: the map keeps its shape,
 * the cards go beside or under it, the overview picks the number of columns that fits.
 */
public final class MainWindow {

    static final Color SURFACE = Theme.BACKGROUND;
    static final Color HEADER = Theme.SURFACE;
    static final Color LINE = Theme.BORDER;

    /**
     * An account in the window.
     *
     * @param stop         logout at a portal ({@code null}: nothing to stop, e.g. observer mode)
     * @param config       the config it runs now ({@code null}: none)
     * @param switchConfig run another config from now on ({@code null}: not offered)
     * @param managed      an account of the manager window: a start/stop button instead of a one-way stop
     */
    public record Tab(WorldFeed feed, Runnable stop, Supplier<String> config, Consumer<String> switchConfig,
                      ManagedAccount managed) {

        public Tab(WorldFeed feed, Runnable stop, Supplier<String> config, Consumer<String> switchConfig) {
            this(feed, stop, config, switchConfig, null);
        }

        /** The tab of a manager account. */
        static Tab of(ManagedAccount a) {
            return new Tab(a.feed(), a::stop, a::config, a::switchConfig, a);
        }
    }

    private MainWindow() {
    }

    /** The minimap as an image, e.g. to compare with a screenshot of the game. */
    public static BufferedImage renderMinimap(WorldFeed feed, int width, int height) {
        return MinimapPanel.render(feed, width, height);
    }

    /** One feed without bot controls (observer, replay); {@code onClose} runs when the window closes. */
    public static void open(WorldFeed feed, Runnable onClose) {
        open(List.of(new Tab(feed, null, null, null)), onClose);
    }

    /** The accounts; closing the window runs {@code onClose} (stop them all). */
    public static void open(List<Tab> tabs, Runnable onClose) {
        SwingUtilities.invokeLater(() -> {
            setupLook();
            JFrame frame = new JFrame(title(tabs));
            frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowClosed(java.awt.event.WindowEvent e) {
                    onClose.run();
                }
            });
            JComponent content = content(tabs);
            frame.setContentPane(content);
            fitToMap(frame, content);
            new Timer(1_000, e -> frame.setTitle(title(tabs))).start();
            MinimapBackgrounds.onLoaded(() -> SwingUtilities.invokeLater(frame::repaint));
            Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
            frame.setMinimumSize(new Dimension(320, 220)); // only the map: as small as a corner of the screen
            frame.setSize(Math.min(1440, (int) (screen.width * 0.85)), Math.min(920, (int) (screen.height * 0.85)));
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    /**
     * The window's content drawn into an image without showing it (to check the layout at any size).
     *
     * @param select the tab to show: 0 = «Все» when there are several accounts
     */
    public static BufferedImage snapshot(List<Tab> tabs, int select, int width, int height) {
        setupLook();
        JComponent c = content(tabs);
        if (c instanceof JTabbedPane pane) {
            pane.setSelectedIndex(Math.min(select, pane.getTabCount() - 1));
        }
        c.setSize(width, height);
        layout(c);
        layout(c); // the second pass sees the sizes of the first (wrapping header, cards under the map)
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            c.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    /**
     * Only the map shown on an account's tab: the window takes the map's shape, its height following the
     * width (after the user lets go of the edge), so no empty bands are left. The overview is left alone.
     */
    private static void fitToMap(JFrame frame, JComponent content) {
        Timer settle = new Timer(120, null);
        settle.setRepeats(false);
        Runnable fit = () -> {
            if (!ViewSettings.shared().mapOnlyNow() || frame.getExtendedState() != JFrame.NORMAL) {
                return;
            }
            Component view = content instanceof JTabbedPane pane ? pane.getSelectedComponent() : content;
            if (!(view instanceof AccountView) || view.getWidth() <= 0) {
                return; // «Все» keeps its grid
            }
            int extra = frame.getHeight() - view.getHeight(); // title bar, tabs, borders
            int want = extra + (int) Math.round(view.getWidth() / Grid.ASPECT);
            java.awt.Rectangle screen = frame.getGraphicsConfiguration().getBounds();
            if (want > screen.height) {
                // too tall for the screen: keep the height, narrow the window to the map's shape instead
                int height = screen.height - 40;
                int width = (int) Math.round((height - extra) * Grid.ASPECT) + (frame.getWidth() - view.getWidth());
                frame.setSize(Math.max(frame.getMinimumSize().width, width), height);
            } else if (Math.abs(frame.getHeight() - want) > 3) {
                frame.setSize(frame.getWidth(), Math.max(frame.getMinimumSize().height, want));
            }
        };
        settle.addActionListener(e -> fit.run());
        frame.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                settle.restart();
            }
        });
        if (content instanceof JTabbedPane pane) {
            pane.addChangeListener(e -> settle.restart());
        }
        ViewSettings.shared().onChange(() -> SwingUtilities.invokeLater(settle::restart));
    }

    private static void layout(Component c) {
        c.doLayout();
        if (c instanceof Container k) {
            for (Component child : k.getComponents()) {
                if (child.isVisible()) {
                    layout(child);
                }
            }
        }
    }

    private static void setupLook() {
        Theme.setup();
    }

    private static String title(List<Tab> tabs) {
        return tabs.size() == 1 ? "Orbit — " + tabs.getFirst().feed().title() : "Orbit — аккаунтов: " + tabs.size();
    }

    private static JComponent content(List<Tab> tabs) {
        if (tabs.size() == 1) {
            return new AccountView(tabs.getFirst());
        }
        JTabbedPane pane = new JTabbedPane();
        pane.putClientProperty("JTabbedPane.tabType", "card");
        pane.putClientProperty("JTabbedPane.scrollButtonsPolicy", "asNeeded");
        pane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        pane.addTab("Все", overview(tabs, pane, 1));
        for (Tab t : tabs) {
            pane.addTab(t.feed().title(), AccountStatus.dot(() -> AccountStatus.of(t.feed())), new AccountView(t));
        }
        // names follow the game: the ship's nickname appears once the account is in game
        new Timer(1_000, e -> {
            for (int i = 0; i < tabs.size(); i++) {
                String title = tabs.get(i).feed().title();
                if (!title.equals(pane.getTitleAt(i + 1))) {
                    pane.setTitleAt(i + 1, title); // tab 0 is «Все»
                }
            }
            pane.repaint();
        }).start();
        return pane;
    }

    private static final String OVERVIEW_TIMERS = "orbit.overviewTimers";
    private static final String OVERVIEW_LOG = "orbit.overviewLog";

    /** An overview that is replaced: its refreshes stop. */
    static void disposeOverview(Component overview) {
        if (overview instanceof JComponent c && c.getClientProperty(OVERVIEW_TIMERS) instanceof List<?> timers) {
            timers.forEach(t -> ((Timer) t).stop());
        }
        if (overview instanceof JComponent c && c.getClientProperty(OVERVIEW_LOG) instanceof LogPanel log) {
            log.dispose();
        }
    }

    /**
     * «Все»: the chosen accounts' maps in a grid sized to the window; a click opens the account.
     *
     * @param firstTab the index of the first account's tab in {@code pane}
     */
    static JComponent overview(List<Tab> tabs, JTabbedPane pane, int firstTab) {
        JPanel grid = new JPanel(new Grid());
        grid.setBackground(SURFACE);
        grid.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        JPanel choose = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 6));
        choose.setBackground(HEADER);
        choose.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, LINE));
        JLabel show = new JLabel("Показывать:");
        show.setForeground(Hud.DIM);
        choose.add(show);
        List<JCheckBox> boxes = new ArrayList<>();
        List<MinimapPanel> maps = new ArrayList<>();
        List<Timer> timers = new ArrayList<>();
        for (int i = 0; i < tabs.size(); i++) {
            Tab t = tabs.get(i);
            MinimapPanel map = new MinimapPanel(t.feed(), true, t.config() != null ? t.config() : () -> null);
            int tabIndex = i + firstTab;
            map.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    pane.setSelectedIndex(tabIndex);
                }
            });
            map.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            maps.add(map);
            JCheckBox box = new JCheckBox(t.feed().title(), true);
            box.setOpaque(false);
            timers.add(new Timer(1_000, e -> box.setText(t.feed().title())));
            boxes.add(box);
            choose.add(box);
        }
        Runnable relayout = () -> {
            grid.removeAll();
            for (int i = 0; i < maps.size(); i++) {
                if (boxes.get(i).isSelected()) {
                    grid.add(maps.get(i));
                }
            }
            grid.revalidate();
            grid.repaint();
        };
        boxes.forEach(b -> b.addActionListener(e -> relayout.run()));
        relayout.run();
        timers.add(new Timer(100, e -> {
            if (grid.isShowing()) {
                grid.repaint();
            }
        }));
        timers.forEach(Timer::start);
        JPanel page = new JPanel(new BorderLayout());
        page.putClientProperty(OVERVIEW_TIMERS, timers);
        page.add(choose, BorderLayout.NORTH);
        // every account's log under the maps, with a choice of account
        LogPanel log = new LogPanel(orbit.app.log.LogStore.shared(), null, false);
        page.putClientProperty(OVERVIEW_LOG, log);
        javax.swing.JSplitPane split = new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, grid, log);
        split.setResizeWeight(0.62);
        split.setOneTouchExpandable(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);
        page.add(split, BorderLayout.CENTER);
        return page;
    }

    /** Cells of the map's shape (21:13), as many columns as give the biggest cells, centred. */
    static final class Grid implements LayoutManager {
        static final int GAP = 10;
        static final double ASPECT = 21000.0 / 13100.0;

        @Override
        public void layoutContainer(Container parent) {
            Component[] cells = parent.getComponents();
            int n = cells.length;
            if (n == 0) {
                return;
            }
            java.awt.Insets in = parent.getInsets();
            int w = parent.getWidth() - in.left - in.right;
            int h = parent.getHeight() - in.top - in.bottom;
            int bestCols = 1;
            double bestW = 0;
            for (int cols = 1; cols <= n; cols++) {
                int rows = (n + cols - 1) / cols;
                double cw = (w - (cols - 1) * GAP) / (double) cols;
                double ch = (h - (rows - 1) * GAP) / (double) rows;
                double cellW = Math.min(cw, ch * ASPECT);
                if (cellW > bestW) {
                    bestW = cellW;
                    bestCols = cols;
                }
            }
            int cols = bestCols;
            int rows = (n + cols - 1) / cols;
            int cellW = (int) bestW;
            int cellH = (int) (bestW / ASPECT);
            int totalW = cols * cellW + (cols - 1) * GAP;
            int totalH = rows * cellH + (rows - 1) * GAP;
            int x0 = in.left + (w - totalW) / 2;
            int y0 = in.top + Math.max(0, (h - totalH) / 2);
            for (int i = 0; i < n; i++) {
                int r = i / cols;
                int c = i % cols;
                int inRow = Math.min(cols, n - r * cols);
                int rowX = x0 + (cols - inRow) * (cellW + GAP) / 2; // a short last row is centred too
                cells[i].setBounds(new Rectangle(rowX + c * (cellW + GAP), y0 + r * (cellH + GAP), cellW, cellH));
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(900, 560);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(260, 160);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }
}
