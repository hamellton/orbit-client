package orbit.app.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.WindowConstants;
import javax.swing.text.DefaultCaret;
import orbit.app.WorldFeed;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;

/** The bot window: minimap, status line and log. Repaints ten times a second from the feed. */
public final class MainWindow {

    private static final int REFRESH_MS = 100;
    private static final int MAX_LOG_CHARS = 200_000;

    private final WorldFeed feed;
    private final JFrame frame;
    private final MinimapPanel minimap;
    private final JLabel state = new JLabel();
    private final JLabel place = new JLabel();
    private final JLabel cargo = new JLabel();
    private final JLabel ammo = new JLabel();
    private final JLabel counts = new JLabel();
    private final JProgressBar hp = bar(new Color(0x43a047));
    private final JProgressBar shield = bar(new Color(0x1e88e5));
    private final JTextArea log = new JTextArea();

    private MainWindow(WorldFeed feed, Runnable onClose) {
        this.feed = feed;
        this.minimap = new MinimapPanel(feed);
        frame = new JFrame("Orbit — " + feed.mode());
        frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        frame.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                onClose.run();
            }
        });

        JPanel status = new JPanel(new GridLayout(2, 4, 12, 2));
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        status.add(state);
        status.add(place);
        status.add(labeled("HP", hp));
        status.add(labeled("Щит", shield));
        status.add(cargo);
        status.add(ammo);
        status.add(counts);
        status.add(new JLabel());

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(840, 180));

        JPanel top = new JPanel(new BorderLayout());
        top.add(status, BorderLayout.NORTH);
        top.add(minimap, BorderLayout.CENTER);
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, logScroll);
        split.setResizeWeight(0.8);
        frame.setContentPane(split);
        frame.pack();
        frame.setLocationByPlatform(true);

        List<String> backlog = GuiLogAppender.subscribe(line -> SwingUtilities.invokeLater(() -> append(line)));
        backlog.forEach(this::append);
        new Timer(REFRESH_MS, e -> refresh()).start();
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

    private void refresh() {
        WorldSnapshot w = feed.snapshot();
        WorldSnapshot.Hero h = w.hero();
        state.setText(feed.state());
        MapCatalog.MapInfo map = MapCatalog.bundled().info(h.mapId());
        place.setText(h.id() == 0 ? "—" : String.format("%s  %d, %d%s", map.name(), h.x(), h.y(),
                h.beacon().inStation() ? "  станция" : h.beacon().inRadiationZone() ? "  РАДИАЦИЯ" : ""));
        setBar(hp, h.health().hp(), h.health().hpMax());
        setBar(shield, h.health().shield(), h.health().shieldMax());
        cargo.setText(h.cargoMax() > 0 ? String.format("Трюм: свободно %d из %d", h.cargoFree(), h.cargoMax()) : "Трюм: —");
        WorldSnapshot.Item laser = w.selected("lasers");
        WorldSnapshot.Item rocket = w.selected("rockets");
        ammo.setText("Лазер " + item(laser) + "   Ракеты " + item(rocket));
        counts.setText(String.format("NPC %d  Игроки %d  Ящики %d  Пакетов %d", w.nearest(WorldSnapshot.ShipKind.NPC).size(),
                w.nearest(WorldSnapshot.ShipKind.PLAYER).size(), w.collectables().size(), feed.packets()));
        minimap.repaint();
    }

    private void append(String line) {
        log.append(line);
        int excess = log.getDocument().getLength() - MAX_LOG_CHARS;
        if (excess > 0) {
            log.replaceRange("", 0, excess);
        }
    }

    private static String item(WorldSnapshot.Item i) {
        return i == null ? "—" : String.format("%s ×%,.0f", i.id().replaceFirst("^ammunition_[a-z]+_", "").toUpperCase(), i.counter());
    }

    private static void setBar(JProgressBar bar, double now, double max) {
        if (max <= 0 || now < 0) {
            bar.setValue(0);
            bar.setString("—");
            return;
        }
        bar.setValue((int) Math.round(Math.min(1, now / max) * 1000));
        bar.setString(String.format("%,.0f / %,.0f", now, max));
    }

    private static JProgressBar bar(Color color) {
        JProgressBar b = new JProgressBar(0, 1000);
        b.setStringPainted(true);
        b.setForeground(color);
        return b;
    }

    private static JPanel labeled(String text, JProgressBar bar) {
        JPanel p = new JPanel(new BorderLayout(6, 0));
        p.add(new JLabel(text), BorderLayout.WEST);
        p.add(bar, BorderLayout.CENTER);
        return p;
    }
}
