package orbit.app.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JToolBar;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;
import orbit.app.WorldFeed;

/**
 * The bot window: per account the minimap with its overlay (hero, target, attackers, action, state,
 * results) and the account's log below. One account fills the window; several get a tab each, like a
 * browser. Repaints ten times a second from the feeds.
 */
public final class MainWindow {

    private static final int REFRESH_MS = 100;
    private static final int MAX_LOG_CHARS = 200_000;

    /** An account in the window: its feed and how to stop it (logout at a portal). */
    public record Tab(WorldFeed feed, Runnable stop) {
    }

    private MainWindow() {
    }

    /** The minimap as an image, e.g. to compare with a screenshot of the game. */
    public static java.awt.image.BufferedImage renderMinimap(WorldFeed feed, int width, int height) {
        return MinimapPanel.render(feed, width, height);
    }

    /** One account; {@code onClose} runs when the user closes the window. */
    public static void open(WorldFeed feed, Runnable onClose) {
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            JFrame frame = frame("Orbit — " + feed.title(), onClose);
            new Timer(1_000, e -> frame.setTitle("Orbit — " + feed.title())).start();
            JPanel content = new JPanel(new BorderLayout());
            content.add(toolbar(), BorderLayout.NORTH);
            content.add(accountView(feed), BorderLayout.CENTER);
            show(frame, content);
        });
    }

    /**
     * Several accounts in one window, a tab each. A tab's "Остановить" stops that account; closing the
     * window runs {@code onClose} (stop them all).
     */
    public static void open(List<Tab> tabs, Runnable onClose) {
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            JFrame frame = frame("Orbit — аккаунтов: " + tabs.size(), onClose);
            JTabbedPane pane = new JTabbedPane();
            for (Tab t : tabs) {
                JPanel page = new JPanel(new BorderLayout());
                JToolBar bar = new JToolBar();
                bar.setFloatable(false);
                JButton stop = new JButton("Остановить аккаунт");
                stop.setToolTipText("Долетит до портала и выйдет из игры; остальные аккаунты продолжат");
                stop.addActionListener(e -> {
                    stop.setEnabled(false);
                    stop.setText("Останавливается…");
                    Thread.ofPlatform().name("stop-" + t.feed().account()).start(t.stop());
                });
                bar.add(stop);
                page.add(bar, BorderLayout.NORTH);
                page.add(accountView(t.feed()), BorderLayout.CENTER);
                pane.addTab(t.feed().title(), page);
            }
            // names follow the game: the ship's nickname appears once the account is in game
            new Timer(1_000, e -> {
                for (int i = 0; i < tabs.size(); i++) {
                    String title = tabs.get(i).feed().title();
                    if (!title.equals(pane.getTitleAt(i))) {
                        pane.setTitleAt(i, title);
                    }
                }
            }).start();
            JPanel content = new JPanel(new BorderLayout());
            content.add(toolbar(), BorderLayout.NORTH);
            content.add(pane, BorderLayout.CENTER);
            show(frame, content);
        });
    }

    private static JFrame frame(String title, Runnable onClose) {
        JFrame frame = new JFrame(title);
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                onClose.run();
            }
        });
        return frame;
    }

    private static void show(JFrame frame, JComponent content) {
        frame.setContentPane(content);
        frame.pack();
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
    }

    private static JToolBar toolbar() {
        JToolBar bar = new JToolBar();
        bar.setFloatable(false);
        JButton configs = new JButton("Конфиги…");
        configs.addActionListener(e -> orbit.app.gui.config.ConfigWindow.open(orbit.config.ConfigStore.openDefault(),
                orbit.config.AccountSettings.DEFAULT_FILE, null));
        bar.add(configs);
        return bar;
    }

    /** The minimap over the account's log. */
    private static JComponent accountView(WorldFeed feed) {
        MinimapPanel minimap = new MinimapPanel(feed);
        JTextArea log = new JTextArea();
        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(1000, 150));
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, minimap, logScroll);
        split.setResizeWeight(0.85);
        List<String> backlog = GuiLogAppender.subscribe(feed.account(),
                line -> SwingUtilities.invokeLater(() -> append(log, line)));
        backlog.forEach(line -> append(log, line));
        new Timer(REFRESH_MS, e -> {
            if (minimap.isShowing()) {
                minimap.repaint(); // a hidden tab is not drawn
            }
        }).start();
        return split;
    }

    private static void append(JTextArea log, String line) {
        log.append(line);
        int excess = log.getDocument().getLength() - MAX_LOG_CHARS;
        if (excess > 0) {
            log.replaceRange("", 0, excess);
        }
    }
}
