package orbit.app.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogStoreTest {

    private static final LogEntry.Level I = LogEntry.Level.INFO;
    private static final LogEntry.Level W = LogEntry.Level.WARN;

    @Test
    void repeatsWithOtherNumbersFoldIntoOneRow() {
        LogStore s = new LogStore();
        long t = 1_000_000;
        LogEntry first = s.add("4", I, "orbit.bot.mod.BoxPicker", "Подобрал палладий (за сессию: 1)", "", t);
        // a repair between the pickups does not break the fold (live: pickups and repairs take turns)
        s.add("4", I, "orbit.bot.run.Safety", "HP 20% — ухожу на ремонт", "", t + 500);
        for (int i = 2; i <= 40; i++) {
            assertSame(first, s.add("4", I, "orbit.bot.mod.BoxPicker", "Подобрал палладий (за сессию: " + i + ")", "", t + i * 1_000));
        }
        assertEquals(2, s.rows().size());
        assertEquals(40, first.count());
        assertEquals("Подобрал палладий (за сессию: 40)", first.text());
        assertEquals("Подобрал палладий (за сессию: 1)", first.firstText());
        assertEquals(t + 40_000, first.last());
    }

    @Test
    void accountsAndLongPausesKeepTheirOwnRows() {
        LogStore s = new LogStore();
        LogEntry a = s.add("4", I, "BoxPicker", "Подобрал палладий (за сессию: 1)", "", 0);
        assertNotSame(a, s.add("5", I, "BoxPicker", "Подобрал палладий (за сессию: 1)", "", 1));
        assertNotSame(a, s.add("4", I, "BoxPicker", "Подобрал палладий (за сессию: 2)", "", LogStore.MERGE_MS + 1_000));
        // a row spans an hour at most
        LogStore h = new LogStore();
        LogEntry r = h.add("4", I, "BoxPicker", "Подобрал палладий (за сессию: 1)", "", 0);
        long t = 0;
        while (t <= LogStore.SPAN_MS) {
            t += 60_000;
            h.add("4", I, "BoxPicker", "Подобрал палладий (за сессию: 1)", "", t);
        }
        assertEquals(2, h.rows().size());
        assertTrue(r.last() - r.first() <= LogStore.SPAN_MS);
    }

    @Test
    void journalLinesFoldOnlyWhenWordedTheSame() {
        LogStore s = new LogStore();
        LogEntry a = s.add("1", I, "orbit.app.WorldFeed", "Прыжок на карту 5-2", "", 0);
        assertNotSame(a, s.add("1", I, "orbit.app.WorldFeed", "Прыжок на карту 5-3", "", 1_000));
        LogEntry pet = s.add("1", W, "orbit.bot.run.PetCare", "У питомца нет режима «Сбор ресурсов» — оставляю", "", 2_000);
        assertSame(pet, s.add("1", W, "orbit.bot.run.PetCare", "У питомца нет режима «Сбор ресурсов» — оставляю", "", 3_000));
        // summaries and stack traces never fold
        LogEntry sum = s.add("1", I, "orbit.app.HourlyReport", "Сводка 14:00–15:00 · не в игре", "", 4_000);
        assertNotSame(sum, s.add("1", I, "orbit.app.HourlyReport", "Сводка 14:00–15:00 · не в игре", "", 5_000));
        LogEntry err = s.add("1", LogEntry.Level.ERROR, "orbit.app.OrbitMain", "Ошибка в цикле бота", "trace", 6_000);
        assertNotSame(err, s.add("1", LogEntry.Level.ERROR, "orbit.app.OrbitMain", "Ошибка в цикле бота", "trace", 7_000));
    }

    @Test
    void listenersHearNewAndRepeatedRows() {
        LogStore s = new LogStore();
        List<LogEntry> heard = new ArrayList<>();
        Runnable stop = s.listen(heard::add);
        LogEntry a = s.add("1", I, "BoxPicker", "Подобрал палладий (за сессию: 1)", "", 0);
        s.add("1", I, "BoxPicker", "Подобрал палладий (за сессию: 2)", "", 10);
        stop.run();
        s.add("1", I, "BoxPicker", "Подобрал палладий (за сессию: 3)", "", 20);
        assertEquals(List.of(a, a), heard);
        assertEquals(List.of("1"), s.accounts());
    }

    @Test
    void theRulesSortWhatMatters() {
        assertFalse(LogRules.of("orbit.bot.mod.BoxPicker", false, "Подобрал палладий (за сессию: 5)").important());
        assertEquals(LogCategory.LOOT, LogRules.of("orbit.bot.mod.BoxPicker", false, "Подобрал палладий").category());
        assertTrue(LogRules.of("orbit.world.QuestBook", false, "Квест 300014 «Охота» выполнен").important());
        assertEquals(LogCategory.QUESTS, LogRules.of("orbit.world.QuestBook", false, "Квест 1 взят: x").category());
        // one journal line a death: the bot's own, not its echoes
        LogRules.Kind death = LogRules.of("orbit.bot.run.Revive", true, "Корабль уничтожен (-=[ Barracuda ]=-) — смертей 1");
        assertEquals(LogCategory.SAFETY, death.category());
        assertTrue(death.important());
        assertFalse(LogRules.of("orbit.app.WorldFeed", true, "Корабль уничтожен").important());
        assertFalse(LogRules.of("orbit.app.WorldFeed", true, "Корабль уничтожен (x). Варианты: [btn]").important());
        assertFalse(LogRules.of("orbit.app.OrbitMain", false, "Состояние: DEAD — Корабль уничтожен: x").important());
        assertTrue(LogRules.of("orbit.bot.run.CargoCare", false, "Трюм полон (свободно 0) — лечу продавать").important());
        assertEquals(LogCategory.MOVE, LogRules.of("orbit.app.WorldFeed", false, "Прыжок на карту 5-3").category());
        assertEquals(LogCategory.SUMMARY, LogRules.of("orbit.app.OrbitMain", false, "Итог сессии: Уридий: 5").category());
        assertEquals(LogCategory.CONNECTION, LogRules.of("orbit.app.OrbitMain", false, "Состояние: ONLINE — В игре").category());
        // everyday warnings stay out of the journal, the others are in
        assertFalse(LogRules.of("orbit.bot.mod.Attacker", true, "Streuner не теряет HP 30 с (LCB-10 ×1) — бросаю цель").important());
        assertFalse(LogRules.of("orbit.bot.nav.Navigator", true, "Нет пути к 12/40 — мины").important());
        assertTrue(LogRules.of("orbit.bot.run.BuyWatch", true, "Бустер X: не хватает уридия").important());
        assertFalse(LogRules.of("orbit.bot.run.Safety", false, "HP 20% — ухожу на ремонт").important());
    }

    @Test
    void lootAddsUpInItsRow() {
        LogStore s = new LogStore();
        LogEntry a = s.add("1", I, "orbit.app.LootLog", "Добыча: +4 569 опыта  ·  +32 чести  ·  +14 103 кредитов  ·  +12 уридия", "", 0);
        assertTrue(a.important(), "loot is in the journal");
        s.add("1", I, "orbit.bot.mod.Attacker", "Цель уничтожена (всего 2)", "", 1);
        assertSame(a, s.add("1", I, "orbit.app.LootLog", "Добыча: +26 730 опыта  ·  +99 чести  ·  +5 EMP-01", "", 2));
        assertEquals("Добыча всего: +31 299 опыта  ·  +131 чести  ·  +14 103 кредитов  ·  +12 уридия  ·  +5 EMP-01", a.text());
        assertEquals(2, a.count());
    }
}
