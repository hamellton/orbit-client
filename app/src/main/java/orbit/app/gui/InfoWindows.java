package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import javax.swing.Timer;
import orbit.app.WorldFeed;
import orbit.world.WorldSnapshot;

/**
 * «Скайлаб» and «Обогащение» in windows of their own, as «Ангар» (the user, 2026-11-15: «скайлаб и обогащение кнопкой,
 * как ангар»): what the cards show, in full, with when it was read, how often, and «Обновить». One window per account.
 */
final class InfoWindows {

    private static final Map<String, JFrame> OPEN = new ConcurrentHashMap<>();

    private InfoWindows() {
    }

    static void skylab(WorldFeed feed) {
        HangarWindow.Rows ores = new HangarWindow.Rows("Руда", "На складе / ёмкость");
        HangarWindow.Rows modules = new HangarWindow.Rows("Модуль", "Уровень");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Склад", HangarWindow.table(ores));
        tabs.addTab("Модули", HangarWindow.table(modules));
        JLabel note = new JLabel();
        open(feed, "Скайлаб", tabs, note, () -> {
            orbit.auth.Skylab.Info s = feed.skylab();
            if (s == null) {
                ores.set(Map.of());
                modules.set(Map.of());
                note.setText("ещё не прочитан — через 20–60 с после входа в игру или по «Обновить»");
                return;
            }
            Map<String, String> o = new LinkedHashMap<>();
            for (orbit.auth.Skylab.Ore ore : s.ores()) {
                String pct = ore.capacity() > 0 ? String.format("  (%d %%)", Math.round(100.0 * ore.amount() / ore.capacity())) : "";
                o.put(cap(ore.name()), money(ore.amount()) + (ore.capacity() > 0 ? " / " + money(ore.capacity()) : "") + pct);
            }
            ores.set(o);
            Map<String, String> m = new LinkedHashMap<>();
            for (orbit.auth.Skylab.Module mod : s.modules()) {
                m.put(Hud.SKYLAB_MODULES.getOrDefault(mod.name(), mod.name()),
                        mod.level() + (mod.active() ? "" : ", выключен"));
            }
            modules.set(m);
            int robots = s.robots().values().stream().mapToInt(Integer::intValue).sum();
            note.setText("Транспорт: " + (s.transporting() ? "в пути" : "свободен")
                    + (robots > 0 ? " · роботов ждут: " + robots : "") + " · прочитан " + time(s.readMillis())
                    + " · раз в 20 мин");
        });
    }

    static void lab(WorldFeed feed) {
        HangarWindow.Rows items = new HangarWindow.Rows("Предмет", "Чем обогащён · осталось");
        HangarWindow.Rows hold = new HangarWindow.Rows("Руда в трюме", "Количество");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Обогащение", HangarWindow.table(items));
        tabs.addTab("Руда в трюме", HangarWindow.table(hold));
        JLabel note = new JLabel();
        open(feed, "Обогащение", tabs, note, () -> {
            WorldSnapshot w = feed.snapshot();
            WorldSnapshot.Lab lab = w.inventory().lab();
            Map<String, String> it = new LinkedHashMap<>();
            for (int i = 0; i < Hud.LAB_ITEMS.size(); i++) {
                WorldSnapshot.Enrichment e = lab.items().get(i);
                it.put(Hud.LAB_ITEMS.get(i), e == null || e.left() <= 0 ? "нет"
                        : Hud.oreName(e.ore()) + " · " + money(e.left()) + " (≈ " + money(e.left() / WorldSnapshot.Lab.PER_ORE)
                        + " руды)");
            }
            items.set(it);
            Map<String, String> h = new LinkedHashMap<>();
            for (int ore : new int[] {6, 7, 4, 5}) {
                h.put(Hud.oreName(ore), money(w.inventory().ore(ore)));
            }
            hold.set(h);
            if (lab.nanos() == 0 && lab.items().isEmpty()) {
                note.setText(w.hero().id() == 0 ? "не в игре" : "игра ещё не ответила — «Обновить»");
            } else {
                long millis = System.currentTimeMillis() - Math.max(0, feed.now() - lab.nanos()) / 1_000_000;
                note.setText("Ответ игры " + time(millis) + " · раз в 5 мин · 1 руда = 10");
            }
        });
    }

    private static void open(WorldFeed feed, String what, JTabbedPane tabs, JLabel note, Runnable refresh) {
        String key = what + "|" + (feed.account() == null ? feed.title() : feed.account());
        JFrame shown = OPEN.get(key);
        if (shown != null) {
            shown.toFront();
            return;
        }
        JFrame f = new JFrame(what + " — " + feed.title());
        note.setForeground(javax.swing.UIManager.getColor("Label.disabledForeground"));
        JButton now = new JButton("Обновить");
        now.setToolTipText("Обогащение (из игры) и Скайлаб (с сайта) — сейчас");
        now.addActionListener(e -> {
            Runnable r = feed.refreshInfo();
            if (r != null) {
                r.run();
            }
        });
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        top.add(now);
        top.add(note);
        Timer timer = new Timer(1_000, e -> refresh.run());
        refresh.run();
        timer.start();
        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(tabs, BorderLayout.CENTER);
        f.setContentPane(root);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
                OPEN.remove(key);
            }
        });
        f.setSize(new Dimension(560, 460));
        f.setLocationByPlatform(true);
        OPEN.put(key, f);
        f.setVisible(true);
    }

    private static String cap(String s) {
        return s.isEmpty() ? s : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String money(long v) {
        return v < 0 ? "—" : String.format("%,d", v);
    }

    private static String time(long millis) {
        return java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis), java.time.ZoneId.systemDefault())
                .withNano(0).toString();
    }
}
