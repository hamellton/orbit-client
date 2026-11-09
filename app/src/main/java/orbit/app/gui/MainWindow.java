package orbit.app.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.Dimension;
import java.awt.Font;
import java.util.List;
import javax.swing.JFrame;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;
import orbit.app.WorldFeed;

/**
 * The bot window: the minimap with its overlay (hero, target, attackers, action, state) and the
 * log below. Repaints ten times a second from the feed.
 */
public final class MainWindow {

    private static final int REFRESH_MS = 100;
    private static final int MAX_LOG_CHARS = 200_000;

    private final JFrame frame;
    private final MinimapPanel minimap;
    private final JTextArea log = new JTextArea();

    private MainWindow(WorldFeed feed, Runnable onClose) {
        this.minimap = new MinimapPanel(feed);
        frame = new JFrame("Orbit — " + feed.mode());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                onClose.run();
            }
        });

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(1000, 150));

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, minimap, logScroll);
        split.setResizeWeight(0.85);
        frame.setContentPane(split);
        frame.pack();
        frame.setLocationByPlatform(true);

        List<String> backlog = GuiLogAppender.subscribe(line -> SwingUtilities.invokeLater(() -> append(line)));
        backlog.forEach(this::append);
        new Timer(REFRESH_MS, e -> minimap.repaint()).start();
    }

    /** The minimap as an image, e.g. to compare with a screenshot of the game. */
    public static java.awt.image.BufferedImage renderMinimap(WorldFeed feed, int width, int height) {
        return MinimapPanel.render(feed, width, height);
    }

    /** Opens the window on the Swing thread; {@code onClose} runs when the user closes it. */
    public static void open(WorldFeed feed, Runnable onClose) {
        SwingUtilities.invokeLater(() -> {
            FlatDarkLaf.setup();
            new MainWindow(feed, onClose).frame.setVisible(true);
        });
    }

    private void append(String line) {
        log.append(line);
        int excess = log.getDocument().getLength() - MAX_LOG_CHARS;
        if (excess > 0) {
            log.replaceRange("", 0, excess);
        }
    }
}
