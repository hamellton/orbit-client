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
 * Skylab and Enrichment in windows of their own, opened by a button like the hangar: what the cards show, in full,
 * with when it was read, how often, and a refresh button. One window per account.
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
                        : Hud.oreName(e.ore()) + " · " + money(e.left()) + " " + Hud.LAB_UNITS.get(i) + " (≈ "
                        + money(e.left() / WorldSnapshot.Lab.PER_ORE)
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

    /**
     * The Totals in full, in a details window like the hangar (the card's "picked" did not say of what): what was
     * picked by its kind (the behemoth's, the invokes' and other boxes, the ore), the loot by item, the income and
     * the deeds — the session, per hour and today over all runs, exact numbers.
     */
    static void totals(WorldFeed feed) {
        String[] cols = {"", "За сессию", "В час", "Сегодня"};
        HangarWindow.Rows picked = new HangarWindow.Rows(name("Что подобрано", cols));
        HangarWindow.Rows loot = new HangarWindow.Rows(name("Добыча", cols));
        HangarWindow.Rows deeds = new HangarWindow.Rows(name("Доход и дела", cols));
        HangarWindow.Rows spent = new HangarWindow.Rows("Патроны и ракеты", "За сессию", "В час", "На куб", "Сегодня");
        JTabbedPane tabs = new JTabbedPane();
        tabs.addTab("Подобрано", HangarWindow.table(picked));
        tabs.addTab("Добыча", HangarWindow.table(loot));
        tabs.addTab("Доход и дела", HangarWindow.table(deeds));
        tabs.addTab("Расход", HangarWindow.table(spent));
        JLabel note = new JLabel();
        open(feed, "Итоги", tabs, note, false, () -> {
            WorldSnapshot.Rewards r = feed.sessionRewards();
            orbit.bot.run.Stats st = feed.sessionStats();
            double hours = feed.sessionHours();
            orbit.app.HourlyReport.Totals day = orbit.app.DailyTotals.of(feed.account()).today();
            Map<String, Integer> pickedNow = st == null ? Map.of() : st.pickedSoFar();
            java.util.Set<String> kinds = new java.util.LinkedHashSet<>(pickedNow.keySet());
            kinds.addAll(day.picked().keySet());
            java.util.List<String[]> p = new java.util.ArrayList<>();
            int sum = 0;
            int sumDay = 0;
            for (String k : kinds.stream().sorted(java.util.Comparator.<String>comparingInt(
                    k -> -pickedNow.getOrDefault(k, 0)).thenComparingInt(k -> -day.picked().getOrDefault(k, 0))).toList()) {
                int n = pickedNow.getOrDefault(k, 0);
                int d = day.picked().getOrDefault(k, 0);
                sum += n;
                sumDay += d;
                p.add(row(cap(k), n, d, hours, true));
            }
            if (kinds.size() > 1) {
                p.add(row("Всего", sum, sumDay, hours, true));
            }
            picked.setRows(p);
            java.util.Set<String> ids = new java.util.LinkedHashSet<>(r.items().keySet());
            ids.addAll(day.items().keySet());
            java.util.List<String[]> l = new java.util.ArrayList<>();
            for (String id : ids.stream().sorted(java.util.Comparator.<String>comparingDouble(
                    id -> -r.items().getOrDefault(id, 0.0)).thenComparingDouble(id -> -day.items().getOrDefault(id, 0.0)))
                    .toList()) {
                l.add(row(orbit.app.SessionSummary.displayName(id), r.items().getOrDefault(id, 0.0),
                        day.items().getOrDefault(id, 0.0), hours, true));
            }
            loot.setRows(l);
            java.util.List<String[]> d = new java.util.ArrayList<>();
            d.add(row("Уридий", r.uridium(), day.uridium(), hours, true));
            d.add(row("Кредиты", r.credits(), day.credits(), hours, true));
            d.add(row("Опыт", r.experience(), day.experience(), hours, true));
            d.add(row("Честь", r.honor(), day.honor(), hours, true));
            d.add(row("Убито NPC", st == null ? 0 : st.kills.get(), day.kills(), hours, true));
            if (st != null && st.cubesFought() > 0 || day.cubes() > 0) {
                d.add(row("Кубиконов добито", st == null ? 0 : st.cubesKilled.get(), day.cubes(), hours, true));
            }
            d.add(row("Подобрано всего", sum, sumDay, hours, true));
            d.add(row("Ремонтов", st == null ? 0 : st.repairs.get(), day.repairs(), hours, false));
            d.add(row("Смертей", st == null ? 0 : st.deaths.get(), day.deaths(), hours, false));
            d.add(row("Потрачено уридия", r.spentUridium(), day.spentUridium(), hours, false));
            d.add(row("Потрачено кредитов", r.spentCredits(), day.spentCredits(), hours, false));
            deeds.setRows(d);
            // the ammunition spent (the item bar's counts going down): the session, an hour, a Cubikon killed, today
            Map<String, Double> used = st == null ? Map.of() : st.ammoSpent();
            int cubesNow = st == null ? 0 : st.cubesKilled.get();
            java.util.Set<String> ammoIds = new java.util.LinkedHashSet<>(used.keySet());
            ammoIds.addAll(day.ammo().keySet());
            java.util.List<String[]> a = new java.util.ArrayList<>();
            for (String id : ammoIds.stream().sorted(java.util.Comparator.<String>comparingDouble(
                    id -> -used.getOrDefault(id, 0.0)).thenComparingDouble(id -> -day.ammo().getOrDefault(id, 0.0)))
                    .toList()) {
                double n = used.getOrDefault(id, 0.0);
                String[] r0 = row(orbit.config.ItemCatalog.label(id), n, day.ammo().getOrDefault(id, 0.0), hours, true);
                a.add(new String[] {r0[0], r0[1], r0[2], cubesNow > 0 && n > 0 ? exact(n / cubesNow) : "—", r0[3]});
            }
            spent.setRows(a);
            note.setText(feed.sessionStartNanos() == 0 ? "сессия ещё не началась"
                    : "Сессия " + Hud.clock(java.time.Duration.ofNanos(feed.sessionNanos()))
                    + " · «сегодня» — за все запуски с полуночи · раз в секунду");
        });
    }

    private static String[] name(String first, String[] cols) {
        String[] c = cols.clone();
        c[0] = first;
        return c;
    }

    private static String[] row(String label, double session, double today, double hours, boolean perHour) {
        String h = perHour && session != 0 && hours >= 1.0 / 60 ? exact(session / hours) : "—";
        return new String[] {label, exact(session), h, exact(today)};
    }

    private static String exact(double v) {
        return Math.abs(v) < 0.5 ? "—" : orbit.app.SessionSummary.number(v);
    }

    private static void open(WorldFeed feed, String what, JTabbedPane tabs, JLabel note, Runnable refresh) {
        open(feed, what, tabs, note, true, refresh);
    }

    private static void open(WorldFeed feed, String what, JTabbedPane tabs, JLabel note, boolean refreshButton,
                             Runnable refresh) {
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
        if (refreshButton) {
            top.add(now);
        }
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
