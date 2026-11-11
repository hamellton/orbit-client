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
    /** The side column grows up to this. */
    static final int MAX_SIDE = 440;

    private final MainWindow.Tab tab;
    private final MinimapPanel map;
    private final SidePanel side;
    private final JScrollPane sideScroll;
    private final List<Timer> timers = new java.util.ArrayList<>();
    private final List<Runnable> onDispose = new java.util.ArrayList<>();

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

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, body, log(tab.feed(), onDispose));
        split.setResizeWeight(0.82);
        split.setOneTouchExpandable(true);
        split.setBorder(BorderFactory.createEmptyBorder());
        split.setContinuousLayout(true);

        JComponent header = header();
        JComponent logView = split.getBottomComponent() instanceof JComponent l ? l : null;
        add(header, BorderLayout.NORTH);
        add(split, BorderLayout.CENTER);
        Runnable apply = () -> {
            ViewSettings v = ViewSettings.shared();
            header.setVisible(v.shown(ViewSettings.Section.HEADER));
            boolean log = v.shown(ViewSettings.Section.LOG);
            boolean bare = !v.shown(ViewSettings.Section.HEADER) && !log && !v.anySide();
            body.setBorder(bare ? BorderFactory.createEmptyBorder() : BorderFactory.createEmptyBorder(8, 10, 8, 10));
            // the log below the map in its split, or the map alone
            if (log && split.getParent() != this) {
                remove(body);
                split.setTopComponent(body);
                add(split, BorderLayout.CENTER);
            } else if (!log && body.getParent() != this) {
                remove(split);
                split.setTopComponent(null);
                add(body, BorderLayout.CENTER);
            }
            if (logView != null) {
                logView.setVisible(log);
            }
            revalidate();
            repaint();
        };
        apply.run();
        ViewSettings.shared().onChange(() -> SwingUtilities.invokeLater(apply));
        timer(REFRESH_MS, e -> {
            if (isShowing()) {
                map.repaint(); // a hidden tab is not drawn
                side.repaint();
            }
        });
    }

    private void timer(int ms, java.awt.event.ActionListener action) {
        Timer t = new Timer(ms, action);
        timers.add(t);
        t.start();
    }

    /** The tab was closed: no more refreshes, no more log lines. */
    void dispose() {
        timers.forEach(Timer::stop);
        onDispose.forEach(Runnable::run);
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
            int gap = SidePanel.GAP;
            if (!ViewSettings.shared().anySide()) {
                // every block is on the map or hidden: the map takes the whole width
                sideScroll.setBounds(0, 0, 0, 0);
                map.setBounds(x, y, w, h);
                return;
            }
            // the cards beside the map or under it: whichever leaves the bigger map (a low, wide window
            // keeps them beside), with the cards always readable
            int minSide = Math.min(side.contentHeight(w) + 2, Math.max(120, h / 3));
            double besideMap = Math.min(w - SidePanel.MIN_COLUMN - gap, h * MainWindow.Grid.ASPECT);
            double underMap = Math.min(w, (h - minSide - gap) * MainWindow.Grid.ASPECT);
            boolean beside = w >= WIDE || (w - SidePanel.MIN_COLUMN - gap >= 300 && besideMap >= underMap * 0.9);
            if (beside) {
                // the map as big as the height allows, the cards take the rest (within limits), no dead strip
                int mapW = (int) Math.min(w - SidePanel.MIN_COLUMN - gap, h * MainWindow.Grid.ASPECT);
                int sideW = Math.min(MAX_SIDE, w - mapW - gap);
                int left = x + (w - mapW - gap - sideW) / 2;
                map.setBounds(left, y, mapW, h);
                sideScroll.setBounds(left + mapW + gap, y, sideW, h);
            } else {
                // the map as wide as the window (its height follows), the cards right under it
                int mapH = (int) Math.max(150, Math.min(w / MainWindow.Grid.ASPECT, h - minSide - gap));
                map.setBounds(x, y, w, mapH);
                sideScroll.setBounds(x, y + mapH + gap, w, Math.max(0, h - mapH - gap));
            }
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            return new Dimension(1180, 600);
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            return new Dimension(200, 130);
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
    }

    private JComponent header() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 14, 6));
        bar.setBackground(MainWindow.HEADER);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, MainWindow.LINE));

        JLabel title = new JLabel();
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        title.setForeground(Hud.TEXT);
        JLabel status = new JLabel();
        status.setFont(status.getFont().deriveFont(Font.BOLD, 12f));
        JLabel clock = new JLabel();
        clock.setForeground(Hud.DIM);
        // groups wrap as a whole: a label never ends a row while its control starts the next
        JPanel who = group();
        who.add(title);
        who.add(chip(status));
        who.add(clock);
        JLabel proxy = new JLabel();
        proxy.setForeground(Hud.DIM);
        proxy.setToolTipText("Сайт и игра этого аккаунта идут только через этот прокси; IP — по последней проверке");
        who.add(proxy);
        bar.add(who);

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
            timer(1_000, e -> {
                String now = tab.config().get();
                if (now != null && !configs.isPopupVisible() && !now.equals(configs.getSelectedItem())) {
                    filling[0] = true;
                    fill(configs);
                    filling[0] = false;
                }
            });
            JPanel cfg = group();
            cfg.add(cfgLabel);
            cfg.add(configs);
            bar.add(cfg);
            JButton edit = new JButton("Изменить…");
            edit.setToolTipText("Открыть этот конфиг; сохранение применяется сразу");
            edit.addActionListener(e -> orbit.app.gui.config.ConfigWindow.open(orbit.config.ConfigStore.openDefault(),
                    orbit.config.AccountSettings.DEFAULT_FILE, null, tab.config().get(), tab.feed().title()));
            cfg.add(edit);
        }
        JPanel actions = group();
        bar.add(actions);
        JButton view = new JButton("Блоки ▾");
        view.setToolTipText("Каждый блок — сбоку, на карте или скрыть; правый клик по карточке — её меню");
        view.addActionListener(e -> BlockMenu.all().show(view, 0, view.getHeight()));
        actions.add(view);
        JButton all = new JButton("Все конфиги…");
        all.addActionListener(e -> orbit.app.gui.config.ConfigWindow.open(orbit.config.ConfigStore.openDefault(),
                orbit.config.AccountSettings.DEFAULT_FILE, null));
        actions.add(all);
        if (tab.managed() != null) {
            ManagedAccount a = tab.managed();
            JButton toggle = new JButton();
            toggle.addActionListener(e -> {
                if (a.phase() == ManagedAccount.Phase.STOPPED) {
                    String why = a.start(0);
                    if (why != null) {
                        javax.swing.JOptionPane.showMessageDialog(this, why, a.name(), javax.swing.JOptionPane.WARNING_MESSAGE);
                    }
                } else {
                    a.stop();
                }
            });
            Runnable show = () -> {
                ManagedAccount.Phase p = a.phase();
                toggle.setText(switch (p) {
                    case STOPPED -> "Запустить";
                    case RUNNING -> "Остановить";
                    case STOPPING -> "Останавливается…";
                });
                toggle.setEnabled(p != ManagedAccount.Phase.STOPPING);
                toggle.setToolTipText(p == ManagedAccount.Phase.STOPPED ? "Войти в игру и работать по конфигу аккаунта"
                        : "Долетит до портала и выйдет из игры; остальные аккаунты продолжат");
            };
            show.run();
            timer(500, e -> show.run());
            actions.add(toggle);
        } else if (tab.stop() != null) {
            JButton stop = new JButton("Остановить");
            stop.setToolTipText("Долетит до портала и выйдет из игры; остальные аккаунты продолжат");
            stop.addActionListener(e -> {
                stop.setEnabled(false);
                stop.setText("Останавливается…");
                Thread.ofPlatform().name("stop-" + tab.feed().account()).start(tab.stop());
            });
            actions.add(stop);
        }
        Runnable refresh = () -> {
            title.setText(tab.feed().title());
            AccountStatus st = AccountStatus.of(tab.feed());
            status.setText(st.label);
            status.setForeground(st.color);
            clock.setText(tab.feed().sessionStartNanos() == 0 ? ""
                    : "сессия " + Hud.clock(java.time.Duration.ofNanos(tab.feed().sessionNanos())));
            String via = tab.feed().proxyInfo();
            proxy.setText(via.isEmpty() ? "" : "через " + via);
            proxy.setVisible(!via.isEmpty());
        };
        refresh.run();
        timer(500, e -> refresh.run());
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

    private static JPanel group() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        p.setOpaque(false);
        return p;
    }

    private static JComponent log(WorldFeed feed, List<Runnable> onDispose) {
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
        GuiLogAppender.Subscription sub = GuiLogAppender.subscribe(feed.account(),
                line -> SwingUtilities.invokeLater(() -> append.accept(line)));
        sub.backlog().forEach(append);
        onDispose.add(sub.cancel());
        return scroll;
    }
}
