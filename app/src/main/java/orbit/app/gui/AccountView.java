package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.LayoutManager;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.text.DefaultCaret;
import orbit.app.WorldFeed;

/**
 * One account: a header (name, status, session time, the config it runs with a switch, buttons), the
 * map with its cards — beside it in a wide window, under it in a narrow one — and the log below.
 */
final class AccountView extends JPanel {

    private static final int REFRESH_MS = 100;
    private static final int MAX_LOG_CHARS = 200_000;
    /** From this width the cards stand in a column beside the map. */
    static final int WIDE = 980;

    private final MainWindow.Tab tab;
    private final MinimapPanel map;
    private final SidePanel side;
    private final JScrollPane sideScroll;

    AccountView(MainWindow.Tab tab) {
        super(new BorderLayout());
        this.tab = tab;
        setBackground(MainWindow.SURFACE);
        Supplier<String> config = tab.config() != null ? tab.config() : () -> null;
        map = new MinimapPanel(tab.feed(), false, config);
        side = new SidePanel(tab.feed(), config);
        sideScroll = new JScrollPane(side, JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        sideScroll.setBorder(BorderFactory.createEmptyBorder());
        sideScroll.getViewport().setBackground(MainWindow.SURFACE);
        sideScroll.getVerticalScrollBar().setUnitIncrement(24);

        JPanel body = new JPanel(new Responsive());
        body.setBackground(MainWindow.SURFACE);
        body.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));
        body.add(map);
        body.add(sideScroll);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, body, log(tab.feed()));
        split.setResizeWeight(0.82);
        split.setOneTouchExpandable(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);

        add(header(), BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        new Timer(REFRESH_MS, e -> {
            if (isShowing()) {
                map.repaint(); // a hidden tab is not drawn
                side.repaint();
            }
        }).start();
    }

    /** Lays out the map and the cards: side by side when wide, the cards under the map otherwise. */
    private final class Responsive implements LayoutManager {
        @Override
        public void layoutContainer(Container parent) {
            java.awt.Insets in = parent.getInsets();
            int x = in.left;
            int y = in.top;
            int w = parent.getWidth() - in.left - in.right;
            int h = parent.getHeight() - in.top - in.bottom;
            if (w >= WIDE) {
                int sideW = Math.max(SidePanel.MIN_COLUMN, Math.min(360, (int) (w * 0.27)));
                map.setBounds(x, y, w - sideW - SidePanel.GAP, h);
                sideScroll.setBounds(x + w - sideW, y, sideW, h);
            } else {
                int want = side.contentHeight(w) + 2;
                int sideH = Math.min(want, Math.max(120, (int) (h * 0.45)));
                int mapH = Math.max(150, h - sideH - SidePanel.GAP);
                map.setBounds(x, y, w, mapH);
                sideScroll.setBounds(x, y + mapH + SidePanel.GAP, w, Math.max(0, h - mapH - SidePanel.GAP));
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(1180, 600);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(300, 260);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }

    private JComponent header() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 8, 6));
        bar.setBackground(MainWindow.HEADER);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, MainWindow.LINE));

        JLabel title = new JLabel();
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(Hud.TEXT);
        JLabel status = new JLabel();
        status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));
        JLabel clock = new JLabel();
        clock.setForeground(Hud.DIM);
        bar.add(title);
        bar.add(chip(status));
        bar.add(clock);
        bar.add(gap(12));

        if (tab.switchConfig() != null) {
            JLabel cfgLabel = new JLabel("Конфиг");
            cfgLabel.setForeground(Hud.DIM);
            JComboBox<String> configs = new JComboBox<>();
            configs.setPrototypeDisplayValue("CUBIKON_TEST_LONG");
            configs.setToolTipText("Конфиг этого аккаунта; выбор применяется сразу и запоминается");
            fill(configs);
            final boolean[] filling = {false};
            configs.addPopupMenuListener(new PopupMenuListener() {
                @Override
                public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
                    filling[0] = true;
                    fill(configs);
                    filling[0] = false;
                }

                @Override
                public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                }

                @Override
                public void popupMenuCanceled(PopupMenuEvent e) {
                }
            });
            configs.addActionListener(e -> {
                String chosen = (String) configs.getSelectedItem();
                if (!filling[0] && chosen != null && !chosen.equals(tab.config().get())) {
                    tab.switchConfig().accept(chosen);
                }
            });
            new Timer(1_000, e -> {
                String now = tab.config().get();
                if (now != null && !configs.isPopupVisible() && !now.equals(configs.getSelectedItem())) {
                    filling[0] = true;
                    fill(configs);
                    filling[0] = false;
                }
            }).start();
            bar.add(cfgLabel);
            bar.add(configs);
            JButton edit = new JButton("Изменить…");
            edit.setToolTipText("Открыть этот конфиг; сохранение применяется сразу");
            edit.addActionListener(e -> orbit.app.gui.config.ConfigWindow.open(orbit.config.ConfigStore.openDefault(),
                    orbit.config.AccountSettings.DEFAULT_FILE, null, tab.config().get(), tab.feed().title()));
            bar.add(edit);
        }
        JButton all = new JButton("Все конфиги…");
        all.addActionListener(e -> orbit.app.gui.config.ConfigWindow.open(orbit.config.ConfigStore.openDefault(),
                orbit.config.AccountSettings.DEFAULT_FILE, null));
        bar.add(all);
        if (tab.stop() != null) {
            JButton stop = new JButton("Остановить");
            stop.setToolTipText("Долетит до портала и выйдет из игры; остальные аккаунты продолжат");
            stop.addActionListener(e -> {
                stop.setEnabled(false);
                stop.setText("Останавливается…");
                Thread.ofPlatform().name("stop-" + tab.feed().account()).start(tab.stop());
            });
            bar.add(stop);
        }
        Runnable refresh = () -> {
            title.setText(tab.feed().title());
            AccountStatus st = AccountStatus.of(tab.feed());
            status.setText(st.label);
            status.setForeground(st.color);
            long since = tab.feed().sessionStartNanos();
            clock.setText(since == 0 ? "" : "сессия " + Hud.clock(java.time.Duration.ofNanos(tab.feed().now() - since)));
        };
        refresh.run();
        new Timer(500, e -> refresh.run()).start();
        return bar;
    }

    private void fill(JComboBox<String> box) {
        String current = tab.config().get();
        box.removeAllItems();
        try {
            List<String> names = orbit.config.ConfigStore.openDefault().list();
            names.forEach(box::addItem);
            if (current != null && !names.contains(current)) {
                box.addItem(current);
            }
        } catch (java.io.IOException e) {
            if (current != null) {
                box.addItem(current);
            }
        }
        box.setSelectedItem(current);
    }

    private static JComponent chip(JLabel label) {
        label.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(MainWindow.LINE, 1, true),
                BorderFactory.createEmptyBorder(2, 8, 2, 8)));
        return label;
    }

    private static JComponent gap(int w) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setPreferredSize(new Dimension(w, 1));
        return p;
    }

    private static JComponent log(WorldFeed feed) {
        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        log.setBackground(new Color(0x0f1218));
        log.setForeground(new Color(0xc9d1dc));
        log.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane scroll = new JScrollPane(log);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, MainWindow.LINE));
        scroll.setPreferredSize(new Dimension(1000, 150));
        scroll.setMinimumSize(new Dimension(100, 0));
        Consumer<String> append = line -> {
            log.append(line);
            int excess = log.getDocument().getLength() - MAX_LOG_CHARS;
            if (excess > 0) {
                log.replaceRange("", 0, excess);
            }
        };
        List<String> backlog = GuiLogAppender.subscribe(feed.account(), line -> SwingUtilities.invokeLater(() -> append.accept(line)));
        backlog.forEach(append);
        return scroll;
    }
}
