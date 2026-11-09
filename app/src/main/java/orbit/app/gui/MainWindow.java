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
    private final JLabel action = new JLabel();
    private final JLabel target = new JLabel();
    private final JLabel attackers = new JLabel();
    private final JLabel group = new JLabel();
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

        JPanel status = new JPanel(new GridLayout(5, 2, 12, 2));
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        status.add(state);
        status.add(place);
        status.add(action);
        status.add(target);
        status.add(labeled("HP", hp));
        status.add(labeled("Щит", shield));
        status.add(ammo);
        status.add(cargo);
        status.add(attackers);
        status.add(group);
        JPanel south = new JPanel(new BorderLayout());
        south.setBorder(BorderFactory.createEmptyBorder(0, 10, 4, 10));
        south.add(counts, BorderLayout.WEST);

        log.setEditable(false);
        log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ((DefaultCaret) log.getCaret()).setUpdatePolicy(DefaultCaret.ALWAYS_UPDATE);
        JScrollPane logScroll = new JScrollPane(log);
        logScroll.setPreferredSize(new Dimension(900, 180));

        JPanel top = new JPanel(new BorderLayout());
        top.add(status, BorderLayout.NORTH);
        top.add(minimap, BorderLayout.CENTER);
        top.add(south, BorderLayout.SOUTH);
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
        // the game shows coordinates in hundreds ("74/76")
        place.setText(h.id() == 0 ? "—" : String.format("%s  %d/%d  (%d, %d)%s", map.name(), h.x() / 100, h.y() / 100, h.x(), h.y(),
                h.beacon().inStation() ? "  станция" : h.beacon().inRadiationZone() ? "  РАДИАЦИЯ" : ""));
        setBar(hp, h.health().hp(), h.health().hpMax());
        setBar(shield, h.health().shield(), h.health().shieldMax());
        cargo.setText(h.cargoMax() > 0 ? String.format("Трюм: свободно %d из %d", h.cargoFree(), h.cargoMax()) : "Трюм: —");
        WorldSnapshot.Item laser = w.selected("lasers");
        WorldSnapshot.Item rocket = w.selected("rockets");
        ammo.setText("<html>Лазер <b>" + item(laser) + "</b> &nbsp; Ракеты <b>" + item(rocket) + "</b></html>");
        counts.setText(String.format("NPC %d   Игроки %d   Ящики %d   Пакетов %,d", w.nearest(WorldSnapshot.ShipKind.NPC).size(),
                w.nearest(WorldSnapshot.ShipKind.PLAYER).size(), w.collectables().size(), feed.packets()));
        action.setText("Действие: " + feed.action());
        target.setText("Цель: " + targetText(w));
        attackers.setText("Атакуют меня: " + attackersText(w));
        group.setText("Группа: " + groupText(w));
        minimap.repaint();
    }

    private static String targetText(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int id = h.attackTargetId() != 0 ? h.attackTargetId() : h.selectedId();
        if (id == 0) {
            return "—";
        }
        WorldSnapshot.Ship s = w.ships().get(id);
        String name = s != null ? s.name() : "#" + id;
        String state = h.attackTargetId() != 0 ? "атакую" : "выбрана";
        String hp = s != null && s.health().known() ? String.format(", HP %.0f%%", s.health().hpPercent() * 100) : "";
        String shield = s != null && s.health().shieldPercent() >= 0
                ? String.format(", щит %.0f%%", s.health().shieldPercent() * 100) : "";
        return name + " (" + state + hp + shield + ")";
    }

    private static String attackersText(WorldSnapshot w) {
        List<Integer> ids = w.hero().attackers();
        if (ids.isEmpty()) {
            return "никто";
        }
        List<String> names = ids.stream().limit(3)
                .map(id -> w.ships().containsKey(id) ? w.ships().get(id).name() : "#" + id).toList();
        return ids.size() + " — " + String.join(", ", names) + (ids.size() > 3 ? "…" : "");
    }

    private static String groupText(WorldSnapshot w) {
        WorldSnapshot.Group g = w.group();
        if (!g.exists()) {
            return "нет";
        }
        List<String> names = g.members().stream().map(m -> m.name() + (m.id() == g.leaderId() ? "★" : "")).toList();
        return g.members().size() + " — " + String.join(", ", names);
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
