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
        HangarWindow.Rows spent = new HangarWindow.Rows(name("Патроны и ракеты", cols));
        JTabbedPane tabs = new JTabbedPane();
        // the income first: what the user looks for first
        tabs.addTab("Доход и дела", HangarWindow.table(deeds));
        tabs.addTab("Подобрано", HangarWindow.table(picked));
        tabs.addTab("Добыча", HangarWindow.table(loot));
        tabs.addTab("Расход", HangarWindow.table(spent));
        HangarWindow.Rows npcs = new HangarWindow.Rows("NPC", "За сессию", "В час", "Бой, ср.", "Сегодня");
        tabs.addTab("NPC", HangarWindow.table(npcs));
        HangarWindow.Rows gateRows = new HangarWindow.Rows("Врата", "Поставлено", "Пройдено", "В час", "Проход, ср.",
                "Смертей", "Жизней куплено", "Энергии на спины", "Уридия потрачено", "Пройдено сегодня");
        tabs.addTab("Врата", HangarWindow.table(gateRows));
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
                l.add(row(orbit.app.SessionSummary.clientName(id), r.items().getOrDefault(id, 0.0),
                        day.items().getOrDefault(id, 0.0), hours, true));
            }
            loot.setRows(l);
            java.util.List<String[]> d = new java.util.ArrayList<>();
            d.add(row("Уридий", r.uridium(), day.uridium(), hours, true));
            if (r.spentUridium() > 0 || day.spentUridium() > 0) {
                // the gain less the spending (ammunition, boosters, lives): what the account really earned
                d.add(row("Уридий чистыми", r.uridium() - r.spentUridium(), day.uridium() - day.spentUridium(),
                        hours, true));
            }
            d.add(row("Кредиты", r.credits(), day.credits(), hours, true));
            if (r.spentCredits() > 0 || day.spentCredits() > 0) {
                d.add(row("Кредиты чистыми", r.credits() - r.spentCredits(), day.credits() - day.spentCredits(),
                        hours, true));
            }
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
            // the ammunition spent (the item bar's counts going down): the session, an hour, today
            Map<String, Double> used = st == null ? Map.of() : st.ammoSpent();
            java.util.Set<String> ammoIds = new java.util.LinkedHashSet<>(used.keySet());
            ammoIds.addAll(day.ammo().keySet());
            java.util.List<String[]> a = new java.util.ArrayList<>();
            for (String id : ammoIds.stream().sorted(java.util.Comparator.<String>comparingDouble(
                    id -> -used.getOrDefault(id, 0.0)).thenComparingDouble(id -> -day.ammo().getOrDefault(id, 0.0)))
                    .toList()) {
                a.add(row(orbit.app.SessionSummary.clientName(id), used.getOrDefault(id, 0.0),
                        day.ammo().getOrDefault(id, 0.0), hours, true));
            }
            spent.setRows(a);
            // the NPCs killed by kind: the session, an hour, the average fight (from the pick to the kill), today
            Map<String, Integer> killedNow = st == null ? Map.of() : st.killedBy();
            Map<String, Double> fights = st == null ? Map.of() : st.fightSeconds();
            java.util.Set<String> names = new java.util.LinkedHashSet<>(killedNow.keySet());
            names.addAll(day.killed().keySet());
            java.util.List<String[]> k = new java.util.ArrayList<>();
            int killedSum = 0;
            int killedDay = 0;
            for (String n : names.stream().sorted(java.util.Comparator.<String>comparingInt(
                    n -> -killedNow.getOrDefault(n, 0)).thenComparingInt(n -> -day.killed().getOrDefault(n, 0))).toList()) {
                int s0 = killedNow.getOrDefault(n, 0);
                int d0 = day.killed().getOrDefault(n, 0);
                killedSum += s0;
                killedDay += d0;
                String[] r0 = row(npcName(n), s0, d0, hours, true);
                Double f = fights.get(n);
                k.add(new String[] {r0[0], r0[1], r0[2], f == null ? "—" : Math.round(f) + " с", r0[3]});
            }
            if (names.size() > 1) {
                String[] r0 = row("Всего", killedSum, killedDay, hours, true);
                k.add(new String[] {r0[0], r0[1], r0[2], "", r0[3]});
            }
            npcs.setRows(k);
            gateRows.setRows(gateRows(st == null ? Map.of() : st.gates(), day.gates(), hours));
            note.setText(feed.sessionStartNanos() == 0 ? "сессия ещё не началась"
                    : "Сессия " + Hud.clock(java.time.Duration.ofNanos(feed.sessionNanos()))
                    + " · «сегодня» — за все запуски с полуночи · раз в секунду");
        });
    }

    /**
     * The galaxy gates: placed, done (the session, an hour), the average run (from the entry to the gate done, the last
     * life's), deaths, lives bought, the extra energy and the uridium spent (spins and lives) in the session, done today.
     * The spins of α, β and γ are one line of their own ({@code GateModule.ABG_SPINS}): one builder serves the three, so
     * a gate's own line keeps only its lives' uridium.
     */
    static java.util.List<String[]> gateRows(Map<String, Integer> now, Map<String, Integer> day, double hours) {
        java.util.Set<String> names = new java.util.TreeSet<>();
        for (String k : now.keySet()) {
            names.add(k.substring(0, k.lastIndexOf('|')));
        }
        for (String k : day.keySet()) {
            names.add(k.substring(0, k.lastIndexOf('|')));
        }
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String g : names) {
            int done = now.getOrDefault(g + "|done", 0);
            int sec = now.getOrDefault(g + "|seconds", 0);
            String[] r = row(g, done, day.getOrDefault(g + "|done", 0), hours, true);
            out.add(new String[] {g, exact(now.getOrDefault(g + "|placed", 0)), r[1], r[2],
                    done > 0 ? minutes(sec / done) : "—", exact(now.getOrDefault(g + "|deaths", 0)),
                    exact(now.getOrDefault(g + "|lives", 0)), exact(now.getOrDefault(g + "|energy", 0)),
                    exact(now.getOrDefault(g + "|uridium", 0)), r[3]});
        }
        return out;
    }

    /** «12 мин 05 с», «45 с». */
    static String minutes(int seconds) {
        return seconds >= 60 ? seconds / 60 + " мин " + String.format("%02d", seconds % 60) + " с" : seconds + " с";
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

    /** An NPC's name without the game's frame: «\\ Invoke XVI //» → «Invoke XVI», «-=[ Cubikon ]=-» → «Cubikon». */
    static String npcName(String raw) {
        String s = raw.replaceAll("^[^\\p{L}\\p{N}]+|[^\\p{L}\\p{N}]+$", "").trim();
        return s.isEmpty() ? raw : s;
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
