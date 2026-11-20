package orbit.app.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class FriendlyTest {

    private static final LogEntry.Level I = LogEntry.Level.INFO;
    private static final LogEntry.Level W = LogEntry.Level.WARN;

    private final LogStore store = new LogStore(new Friendly((a, id) -> id == 801119 ? "Citadel Elite" : id == 801130
            ? "Solace" : null));
    private long t = 1_000_000;

    private void line(LogEntry.Level lv, String logger, String m) {
        store.add("3", lv, logger, m, "", t);
        t += 1_000;
    }

    private List<String> journal() {
        return store.rows().stream().filter(LogEntry::journal).map(LogEntry::text).toList();
    }

    @Test
    void aSessionInPlainWords() {
        line(I, "orbit.app.AccountRunner", "Аккаунт «3» запущен");
        line(I, "orbit.app.OrbitMain", "Конфиг «Cubikon GB2»: модуль CUBIKON, карта 3-6");
        line(I, "orbit.bot.run.BotRunner", "Модуль: CUBIKON");
        line(I, "orbit.bot.mod.CubikonModule", "Cubikon у 75/40 уничтожен — лечу на следующий спаун");
        line(I, "orbit.app.LootLog", "Добыча: +614 400 опыта  ·  +5 112 чести  ·  +1 638 400 кредитов  ·  +1 024 уридия");
        line(I, "orbit.app.LootLog", "Добыча: +16 уридия");
        line(W, "orbit.bot.run.Offenders", "Запомнил обидчика Pro100_4el=): стрелял в корабль");
        line(W, "orbit.bot.run.Revive", "Корабль уничтожен (Pro100_4el=)) — смертей 3");
        line(I, "orbit.bot.run.Revive", "Ремонт: у портала — 200 уридия");
        line(I, "orbit.bot.run.Revive", "Снова в игре");
        line(W, "orbit.bot.run.Revive", "Корабль уничтожен (-=[ Kristallin ]=-) — смертей 4");
        line(W, "orbit.bot.run.BotRunner", "Связь с игрой потеряна — ничего не делаю, пока не переподключусь");
        line(W, "orbit.game.GameConnection", "Обрыв связи: gameserver-502-01-public.prod.darkorbit.com. Повтор через 15 с");
        t += 120_000;
        line(I, "orbit.bot.run.BotRunner", "Связь восстановлена — продолжаю");
        line(I, "orbit.app.OrbitMain", "Плановый перевход (раз в ~40 мин), момент спокойный — ждал его 18 с");
        line(W, "orbit.bot.run.BotRunner", "Связь с игрой потеряна — ничего не делаю, пока не переподключусь");
        line(I, "orbit.bot.run.BotRunner", "Связь восстановлена — продолжаю");
        line(I, "orbit.app.OrbitMain", "Конфиг «Cubikon GB2»: модуль CUBIKON, карта 3-6");
        line(I, "orbit.bot.run.ServiceVisit", "Обслуживание: обогащению не хватает руды — пересаживаюсь на корабль для"
                + " Скайлаба (ангар 801119), потом обратно на 801130");
        line(I, "orbit.bot.run.BotRunner", "Меняю корабль: ангар 801130 → 801119 (обслуживание: Скайлаб и обогащение)");
        line(I, "orbit.bot.run.ServiceVisit", "Обслуживание: рейс 1 — со Скайлаба в трюм {promerium=7997} (быстро)");
        line(I, "orbit.bot.run.ServiceVisit", "Обслуживание: итог — за визит: рейсов 2, promerium 14 056; лазеры"
                + " 49 440 → 190 000");
        line(I, "orbit.bot.run.ServiceVisit", "Обслуживание: снова на своём корабле (ангар 801130)");
        line(I, "orbit.world.QuestBook", "Квест 700013 выполнен");
        line(I, "orbit.app.HourlyReport", "Сводка 11:34–12:00 · в игре все 26 мин · уридий +6 523 · кредиты +10 034 504"
                + " · опыт +3 212 578 · честь +26 082 · NPC 27 · кубов 13 · ремонтов 0 · смертей 0");
        line(I, "orbit.bot.run.BotRunner", "Остановка: по кнопке");
        line(I, "orbit.app.OrbitMain", "Бот завершил работу: Вышел: по кнопке");
        line(I, "orbit.app.OrbitMain", "Итог сессии: Уридий: 10 084  (12 071/ч); Кредиты: 15 536 829  (18 597 919/ч);"
                + " Опыт: 4 970 451  (5 949 737/ч); Честь: 40 380  (48 336/ч); Кубы: бои 26, добито 24  (29/ч);"
                + " Убито NPC 37  (12/ч); Ремонтов 0   смертей 4");
        line(I, "orbit.app.AccountRunner", "Аккаунт «3» остановлен");
        assertEquals(List.of(
                "▶ Запущен: Cubikon GB2 — кубы на 3-6",
                "Куб убит: +1 024 уридия, +1,6 млн кредитов, +614 тыс. опыта, +5 112 чести",
                "Добыча: +16 уридия",
                "✖ Убит игроком Pro100_4el=) (смерть 3) — ремонт у портала за 200 уридия",
                "✖ Убит NPC Kristallin (смерть 4)",
                "⚠ Нет связи — переподключаюсь",
                "✔ Связь восстановлена (без связи 2 мин)",
                "Скайлаб: пересаживаюсь на Citadel Elite за рудой",
                "Скайлаб: привёз 14 056 Promerium за 2 рейса, лазеры 49 440 → 190 000",
                "Скайлаб: вернулся на Solace",
                "✔ Квест выполнен",
                "Час 11:34–12:00: 6 523 уридия · 10 млн кредитов · 13 кубов · смертей 0",
                "■ Остановлен (по кнопке). За 2 мин: 10 084 уридия, 15,5 млн кредитов, 24 куба, смертей 4"), journal());
    }

    @Test
    void windowsCommasInTheHourAndSessionNumbersAreThousands() {
        // live 2026-11-17 on the VPS: the hour line showed 948 uridium and 5 credits — the report wrote 5,495,923
        assertEquals("Час 08:31–09:00: 6 922 уридия · 5,5 млн кредитов · NPC 3 · смертей 7 · потрачено 1,709 уридия",
                Friendly.hour("Сводка 08:31–09:00 · в игре все 28 мин · уридий +6,922 · кредиты +5,495,923 · опыт"
                        + " +1,127,000 · честь +2,457 · потрачено 1,709 уридия · NPC 3 · ремонтов 0 · смертей 7"));
    }

    @Test
    void windowsCommasInLootAreThousands() {
        // live 2026-11-17 on the VPS: two behemoth boxes read +11 uridium, +139 honour; the log had 11,430 and 138,504
        assertEquals("+11 430 уридия, +40,1 млн кредитов, +22,7 млн опыта, +139 тыс. чести, +230 Rinusk,"
                        + " +255 Blacklight-trace, +66 Mindfire-cerebrum, +504 Eternal-fragment",
                Friendly.loot("+230 Rinusk  ·  +255 Blacklight-trace  ·  +66 Mindfire-cerebrum  ·  +504 Eternal-fragment"
                        + "  ·  +22,708,398 опыта  ·  +138,504 чести  ·  +40,080,776 кредитов  ·  +11,430 уридия"));
    }

    @Test
    void eachLootIsItsOwnRowAndOtherLinesFold() {
        line(I, "orbit.app.LootLog", "Добыча: +16 уридия");
        line(I, "orbit.app.LootLog", "Добыча: +16 уридия");
        line(I, "orbit.bot.nav.Traveler", "Прыжок на карту GG Alpha");
        line(I, "orbit.bot.nav.Traveler", "Прыжок на карту GG Alpha");
        assertEquals(List.of("Добыча: +16 уридия", "Добыча: +16 уридия", "→ GG Alpha"), journal());
        assertEquals(2, store.rows().stream().filter(e -> e.journal() && e.text().startsWith("→")).findFirst()
                .orElseThrow().count());
    }

    @Test
    void numbersAndRoutine() {
        assertEquals("1 024", Friendly.number(1_024));
        assertEquals("614 тыс.", Friendly.number(614_400));
        assertEquals("1,6 млн", Friendly.number(1_638_400));
        assertEquals("10 млн", Friendly.number(10_034_504));
        Friendly f = new Friendly(null);
        assertNull(f.of("3", true, "ShipSetup", "Формация Ring (F-3D-RG): сервер не ответил (на корабле №15, ждём №14)"
                + " — повторяю всё реже", 0).add());
        assertNull(f.of("3", false, "World", "Сервер поправил позицию корабля: было 124/34, стало 137/84", 0).add());
    }
    @Test
    void boxesAndSpendingAddUpGateNamesArePlain() {
        line(I, "orbit.bot.mod.Attacker", "Цель: -=[ Sibelonit ]=- α29 в 3501");
        line(I, "orbit.bot.mod.Attacker", "Цель уничтожена (всего 4)");
        line(I, "orbit.app.LootLog", "Добыча: +400 опыта  ·  +2 чести  ·  +800 кредитов  ·  +1 уридия");
        line(I, "orbit.app.LootLog", "Добыча: +240 MCB-25  ·  +4 ACM-01");
        line(I, "orbit.app.LootLog", "Добыча: +12 Ore xenomit  ·  +200 MCB-25");
        line(I, "orbit.app.LootLog", "Добыча: −100 уридия");
        line(I, "orbit.app.LootLog", "Добыча: −100 уридия");
        assertEquals(List.of("Sibelonit убит: +1 уридия, +800 кредитов, +400 опыта, +2 чести",
                "Ящик: +240 MCB-25, +4 ACM-01", "Ящик: +12 Ore xenomit, +200 MCB-25", "Потрачено: 200 уридия"), journal());
        assertEquals("Boss Kristallin", Friendly.bare("..::{ Boss Kristallin }::.."));
    }
    @Test
    void aGateInAFewRowsWithItsCountdownAndTheLogoutCounting() {
        line(I, "orbit.bot.mod.GateModule", "Врата α Alpha: спины по 1, частей 28 из 34");
        line(I, "orbit.bot.mod.GateModule", "Врата α Alpha: спины по 1, частей 29 из 34");
        line(I, "orbit.bot.mod.GateModule", "Врата α Alpha: все части есть (34) — ставлю");
        line(I, "orbit.bot.mod.GateModule", "Врата α Alpha: ставлю");
        line(I, "orbit.bot.mod.GateModule", "Во вратах α Alpha (GG Alpha), жизней 5");
        for (int n = 15; n >= 0; n--) {
            line(I, "orbit.world.World", "Сервер: «-=- " + n + " -=-»");
        }
        line(I, "orbit.world.World", "Сервер: «Wave 4»");
        line(I, "orbit.world.World", "Сервер: «Wave 8»");
        line(I, "orbit.world.World", "Сервер: «-=- 15 -=-»");
        line(I, "orbit.world.World", "Сервер: «4 lives left»");
        line(I, "orbit.bot.mod.GateModule", "Врата α Alpha пройдены за 16 мин");
        line(I, "orbit.bot.mod.GateModule", "Из врат α Alpha вышел — пройдены");
        line(I, "orbit.bot.run.BotRunner", "Выход из игры");
        LogEntry out = store.rows().getLast();
        assertEquals(true, out.liveSince() > 0, "counting");
        t += 18_000;
        line(I, "orbit.app.WorldFeed", "Сервер завершил сессию (выход)");
        assertEquals(List.of("Врата α: части 29 из 34", "Врата α: собраны и поставлены", "Вошёл во Врата α (жизней 5)",
                "⏱ Врата α: волна пошла", "Врата α: волна 8 из 40", "⏱ Врата α: волна через 15 с",
                "Врата α: осталось жизней 4", "✔ Врата α пройдены за 16 мин", "Вышел из игры за 19 с"), journal());
        assertEquals(0, out.liveSince(), "done counting");
    }
}
