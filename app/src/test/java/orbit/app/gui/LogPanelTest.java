package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import orbit.app.log.LogCategory;
import orbit.app.log.LogEntry;
import orbit.app.log.LogStore;
import org.junit.jupiter.api.Test;

class LogPanelTest {

    private static final LogEntry.Level I = LogEntry.Level.INFO;

    private static LogStore sample() {
        LogStore s = new LogStore();
        long t = System.currentTimeMillis() - 60_000;
        s.add("4", I, "orbit.bot.mod.BoxPicker", "Подобрал палладий (за сессию: 1)", "", t);
        s.add("4", I, "orbit.bot.mod.BoxPicker", "Подобрал палладий (за сессию: 2)", "", t + 1);
        s.add("4", I, "orbit.bot.run.CargoCare", "Трюм полон (свободно 0) — лечу продавать на 5-2", "", t + 2);
        s.add("5", LogEntry.Level.WARN, "orbit.bot.run.BuyWatch", "Бустер X: не хватает уридия", "", t + 3);
        s.add(null, I, "orbit.app.OrbitMain", "Программа запущена", "", t + 4);
        return s;
    }

    private static List<String> texts(LogPanel p) {
        return p.shown().stream().map(LogEntry::text).toList();
    }

    @Test
    void modesScopeAndSearch() {
        Theme.setup();
        LogStore s = sample();
        LogPanel all = new LogPanel(s, null, false);
        all.setMode(LogPanel.Mode.ALL);
        assertEquals(4, all.shown().size(), "the pickups are one row");
        assertEquals(2, all.shown().getFirst().count());
        all.setMode(LogPanel.Mode.JOURNAL);
        assertEquals(List.of("Трюм полон (свободно 0) — лечу продавать на 5-2", "Бустер X: не хватает уридия"), texts(all));
        all.setMode(LogPanel.Mode.PROBLEMS);
        assertEquals(List.of("Бустер X: не хватает уридия"), texts(all));
        all.setMode(LogPanel.Mode.ALL);
        all.setQuery("палладий");
        assertEquals(List.of("Подобрал палладий (за сессию: 2)"), texts(all));
        all.setQuery("торговля");
        assertEquals(2, all.shown().size(), "a subject's name finds its lines (the hold and the booster)");

        // the manager's tab: the account's own lines only; the bot window's: and those of no account
        LogPanel mine = new LogPanel(s, "4", true);
        mine.setMode(LogPanel.Mode.ALL);
        assertEquals(2, mine.shown().size());
        LogPanel withShared = new LogPanel(s, "4", false);
        withShared.setMode(LogPanel.Mode.ALL);
        assertEquals(3, withShared.shown().size());
        assertEquals(1, (int) mine.counts().get(LogCategory.LOOT));
        all.dispose();
        mine.dispose();
        withShared.dispose();
    }

    @Test
    void newLinesArriveAndRepeatsGrowTheirRow() {
        Theme.setup();
        LogStore s = sample();
        LogPanel p = new LogPanel(s, "4", true);
        p.setMode(LogPanel.Mode.ALL);
        long t = System.currentTimeMillis();
        s.add("4", I, "orbit.bot.mod.BoxPicker", "Подобрал палладий (за сессию: 3)", "", t);
        s.add("4", I, "orbit.bot.run.Safety", "HP 20% — ухожу на ремонт", "", t + 1);
        p.drainNow();
        assertEquals(3, p.shown().size());
        assertEquals(3, p.shown().getFirst().count());
        assertEquals("HP 20% — ухожу на ремонт", p.shown().getLast().text());
        p.dispose();
    }
}
