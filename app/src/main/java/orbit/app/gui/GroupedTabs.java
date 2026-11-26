package orbit.app.gui;

import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.JTabbedPane;

/**
 * The accounts' tabs by groups (docs/PLAN.md «Менеджер: группы»), the same in the account manager and in the bot's window:
 * after the window's own leading tabs — «Все» (every account), a tab per group with accounts («Starters · 5»: their maps,
 * a click opens the account), then the accounts with a tab of their own: pinned, without a group, or opened from a card.
 * A tab is titled «account · server», its tooltip tells the pilot, group, config, goal or what it does, and the state.
 */
final class GroupedTabs {

    final JTabbedPane pane;
    /** The window's own tabs before the accounts' (the manager: «Аккаунты»). */
    final int lead;
    final ManagerGroups groups;
    /** The × also on accounts without a group (the manager: it stops them, as before). */
    final boolean closableUngrouped;
    final List<MainWindow.Tab> tabs = new ArrayList<>();
    final Map<String, AccountView> views = new LinkedHashMap<>();
    final Set<String> open = new LinkedHashSet<>();

    GroupedTabs(JTabbedPane pane, int lead, ManagerGroups groups, boolean closableUngrouped) {
        this.pane = pane;
        this.lead = lead;
        this.groups = groups;
        this.closableUngrouped = closableUngrouped;
        ManagerGroups.onChange(() -> javax.swing.SwingUtilities.invokeLater(() -> {
            groups.reload();
            reopen();
            rebuild();
        }));
    }

    /** The account's name: the manager's, else the feed's (the bot's window), else its title. */
    static String nameOf(MainWindow.Tab t) {
        if (t.managed() != null) {
            return t.managed().name();
        }
        return t.feed().account() != null ? t.feed().account() : t.feed().title();
    }

    /** «st2 · int1»: the account and its server (the pilot's nick is in the tooltip and the header). */
    static String title(MainWindow.Tab t) {
        String server = t.feed().server();
        String name = t.feed().account();
        if (name == null) {
            return t.feed().title();
        }
        return server == null || server.isEmpty() ? name : name + " · " + server;
    }

    /** What the account is: pilot, group, config, goal or what it does now, state. */
    static String tooltip(MainWindow.Tab t, ManagerGroups groups) {
        String name = nameOf(t);
        String pilot = t.feed().snapshot().hero().name();
        StringBuilder sb = new StringBuilder("<html><b>").append(escape(name)).append("</b>");
        if (pilot != null && !pilot.isBlank()) {
            sb.append(" — ").append(escape(pilot));
        }
        if (!t.feed().server().isEmpty()) {
            sb.append(" · ").append(escape(t.feed().server()));
        }
        String g = groups.group(name);
        sb.append("<br>Группа: ").append(g == null ? "—" : escape(g));
        String config = t.config() != null ? t.config().get() : null;
        sb.append("<br>Конфиг: ").append(config == null ? "—" : escape(config));
        String goal = orbit.app.LevelGoals.of(name);
        if (goal == null && t.feed().runner() != null) {
            goal = t.feed().runner().status();
        }
        if (goal != null && !goal.isBlank()) {
            sb.append("<br>Сейчас: ").append(escape(goal));
        }
        sb.append("<br>Состояние: ").append(escape(AccountStatus.of(t.feed()).label));
        return sb.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** An account's tab made (once): with a tab of its own when pinned or without a group. */
    void add(MainWindow.Tab tab) {
        String name = nameOf(tab);
        if (views.containsKey(name)) {
            return;
        }
        tabs.add(tab);
        AccountView view = new AccountView(tab);
        views.put(name, view);
        if (groups.pinned.contains(name) || groups.group(name) == null) {
            open.add(name);
        }
        rebuild();
    }

    /** The account gone: its view and tab. */
    void remove(String name) {
        AccountView v = views.remove(name);
        open.remove(name);
        tabs.removeIf(t -> nameOf(t).equals(name));
        if (v != null) {
            int i = pane.indexOfComponent(v);
            if (i >= 0) {
                pane.removeTabAt(i);
            }
            v.dispose();
        }
        rebuild();
    }

    /** The account's own tab opened and selected. */
    void openAccount(String name) {
        if (!views.containsKey(name)) {
            return;
        }
        if (open.add(name)) {
            rebuild();
        }
        pane.setSelectedComponent(views.get(name));
    }

    /** The account of a tab ({@code null}: an overview or the window's own). */
    String nameAt(int index) {
        if (index < 0 || index >= pane.getTabCount()) {
            return null;
        }
        Component c = pane.getComponentAt(index);
        for (var e : views.entrySet()) {
            if (e.getValue() == c) {
                return e.getKey();
            }
        }
        return null;
    }

    /** A grouped account's tab hidden (the account goes on); false when the account has no group. */
    boolean hide(String name) {
        if (name == null || groups.group(name) == null) {
            return false;
        }
        open.remove(name);
        groups.setPinned(name, false);
        rebuild();
        return true;
    }

    /** After the groups changed: the own tabs follow (pinned or without a group open, a newly grouped one closes). */
    void reopen() {
        for (String n : views.keySet()) {
            if (groups.pinned.contains(n) || groups.group(n) == null) {
                open.add(n);
            } else if (!groups.pinned.contains(n) && open.contains(n) && groups.group(n) != null) {
                open.remove(n);
            }
        }
    }

    /** The tabs after the leading ones built again; the selected one stays selected. */
    void rebuild() {
        Component was = pane.getSelectedComponent();
        String wasTitle = pane.getSelectedIndex() >= 0 ? pane.getTitleAt(pane.getSelectedIndex()) : null;
        for (int i = pane.getTabCount() - 1; i >= lead; i--) {
            Component c = pane.getComponentAt(i);
            pane.removeTabAt(i);
            if (!(c instanceof AccountView)) {
                MainWindow.disposeOverview(c);
            }
        }
        if (tabs.isEmpty()) {
            return;
        }
        List<MainWindow.Tab> all = List.copyOf(tabs);
        pane.addTab("Все", AccountStatus.dot(() -> together(all)),
                MainWindow.overview(all, i -> openAccount(nameOf(all.get(i)))));
        for (String g : groups.groups()) {
            List<MainWindow.Tab> in = tabs.stream().filter(t -> g.equals(groups.group(nameOf(t)))).toList();
            if (!in.isEmpty()) {
                pane.addTab(g + " · " + in.size(), AccountStatus.dot(() -> together(in)),
                        MainWindow.overview(in, i -> openAccount(nameOf(in.get(i)))));
                pane.setToolTipTextAt(pane.getTabCount() - 1, "Группа «" + g + "»: " + String.join(", ",
                        in.stream().map(GroupedTabs::nameOf).toList()));
            }
        }
        for (MainWindow.Tab t : tabs) {
            String n = nameOf(t);
            if (open.contains(n)) {
                AccountView v = views.get(n);
                boolean grouped = groups.group(n) != null;
                v.putClientProperty("JTabbedPane.tabClosable", grouped || closableUngrouped);
                v.putClientProperty("JTabbedPane.tabCloseToolTipText", grouped
                        ? "Скрыть вкладку (аккаунт работает дальше, он на вкладке группы)" : "Закрыть (аккаунт остановится)");
                pane.addTab(title(t), AccountStatus.dot(() -> statusOf(t)), v);
                pane.setToolTipTextAt(pane.getTabCount() - 1, tooltip(t, groups));
            }
        }
        int back = was == null ? -1 : pane.indexOfComponent(was);
        if (back < 0 && wasTitle != null) {
            back = pane.indexOfTab(wasTitle);
        }
        if (back >= 0) {
            pane.setSelectedIndex(back);
        }
    }

    /** One account's state: a stopped one of the manager is not in game. */
    static AccountStatus statusOf(MainWindow.Tab t) {
        return t.managed() != null && t.managed().phase() == ManagedAccount.Phase.STOPPED ? AccountStatus.OFFLINE
                : AccountStatus.of(t.feed());
    }

    /**
     * A group's light (the user's wish 2026-11-27): yellow when one of its accounts has lost the link, else green when at
     * least one is in the game, else grey.
     */
    static AccountStatus together(List<MainWindow.Tab> in) {
        boolean playing = false;
        for (MainWindow.Tab t : in) {
            AccountStatus s = statusOf(t);
            if (s == AccountStatus.LINK_LOST) {
                return AccountStatus.LINK_LOST;
            }
            playing |= s != AccountStatus.OFFLINE;
        }
        return playing ? AccountStatus.ONLINE : AccountStatus.OFFLINE;
    }

    /** Each second: the titles and tooltips of the own tabs follow the game. */
    void refresh() {
        for (MainWindow.Tab t : tabs) {
            AccountView v = views.get(nameOf(t));
            int i = v == null ? -1 : pane.indexOfComponent(v);
            if (i < 0) {
                continue;
            }
            String title = title(t);
            if (!title.equals(pane.getTitleAt(i))) {
                pane.setTitleAt(i, title);
            }
            pane.setToolTipTextAt(i, tooltip(t, groups));
        }
    }
}
