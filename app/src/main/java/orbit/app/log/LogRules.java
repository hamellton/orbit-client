package orbit.app.log;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Sorts log lines: their {@link LogCategory} (by the class that logged them, a few by their words) and whether they
 * belong in the journal — what a person wants to read after 20 hours: starts and stops, deaths, quests, sales,
 * purchases, map changes, problems. The rest (every box, every target, every repair trip) stays in the full log and
 * is counted in the hourly summary.
 */
public final class LogRules {

    private LogRules() {
    }

    /** The result for one line. */
    public record Kind(LogCategory category, boolean important) {
    }

    private static final Map<String, LogCategory> BY_LOGGER = Map.ofEntries(
            Map.entry("Attacker", LogCategory.BATTLE),
            Map.entry("Hunter", LogCategory.BATTLE),
            Map.entry("CubikonModule", LogCategory.BATTLE),
            Map.entry("SpawnMemory", LogCategory.BATTLE),
            Map.entry("CubeMaps", LogCategory.BATTLE),
            Map.entry("BoxPicker", LogCategory.LOOT),
            Map.entry("LootLog", LogCategory.LOOT),
            Map.entry("PalladiumModule", LogCategory.LOOT),
            Map.entry("CargoCare", LogCategory.TRADE),
            Map.entry("BuyWatch", LogCategory.TRADE),
            Map.entry("Safety", LogCategory.SAFETY),
            Map.entry("Revive", LogCategory.SAFETY),
            Map.entry("Flee", LogCategory.SAFETY),
            Map.entry("Offenders", LogCategory.SAFETY),
            Map.entry("BoxCheckSolver", LogCategory.SAFETY),
            Map.entry("Navigator", LogCategory.MOVE),
            Map.entry("Traveler", LogCategory.MOVE),
            Map.entry("CpuJumper", LogCategory.MOVE),
            Map.entry("QuestModule", LogCategory.QUESTS),
            Map.entry("QuestGiverVisit", LogCategory.QUESTS),
            Map.entry("QuestBook", LogCategory.QUESTS),
            Map.entry("PassCare", LogCategory.QUESTS),
            Map.entry("PetCare", LogCategory.PET),
            Map.entry("GroupWatch", LogCategory.GROUP),
            Map.entry("ShipSetup", LogCategory.SHIP),
            Map.entry("World", LogCategory.SHIP),
            Map.entry("BotRunner", LogCategory.WORK),
            Map.entry("ConfigWatch", LogCategory.WORK),
            Map.entry("AccountRunner", LogCategory.WORK),
            Map.entry("OrbitMain", LogCategory.WORK),
            Map.entry("HourlyReport", LogCategory.SUMMARY),
            Map.entry("GameConnection", LogCategory.CONNECTION),
            Map.entry("AuthClient", LogCategory.CONNECTION),
            Map.entry("AccountLogin", LogCategory.CONNECTION),
            Map.entry("HttpSession", LogCategory.CONNECTION),
            Map.entry("ProxyRoute", LogCategory.CONNECTION),
            Map.entry("ProxyConnector", LogCategory.CONNECTION),
            Map.entry("LocalProxy", LogCategory.CONNECTION),
            Map.entry("IpHistory", LogCategory.CONNECTION),
            Map.entry("WorldFeed", LogCategory.CONNECTION));

    /** Words that decide the category whoever logged them. */
    private static final List<Map.Entry<Pattern, LogCategory>> BY_TEXT = List.of(
            Map.entry(p("^(Сводка |Итог сессии)"), LogCategory.SUMMARY),
            Map.entry(p("Корабль уничтожен"), LogCategory.SAFETY),
            Map.entry(p("^(Прыжок на карту|Переход на карту)"), LogCategory.MOVE),
            Map.entry(p("^Квестодатель"), LogCategory.QUESTS),
            Map.entry(p("^(Состояние:|Ангары:|Вход )"), LogCategory.CONNECTION),
            Map.entry(p("^Корабль сменён"), LogCategory.SHIP),
            Map.entry(p("^Добыча:"), LogCategory.LOOT),
            Map.entry(p("^Скорость корабля"), LogCategory.SHIP));

    /** Plain lines that still go to the journal. */
    private static final Pattern JOURNAL = p(String.join("|",
            "^Квест .* (взят|выполнен|отменён|провален)", "^Квест .* отменяю", "^Квестодатель: взято",
            "^(Season Pass|Battle Pass|Galaxy Trek|Убывающий квест)",
            "^Снова в игре", "^Ремонт: ", "^Убит .* раз подряд", "Корабль уничтожен",
            "^Бот завершил работу", "^Итог сессии", "^Сводка ", "^Остановка:", "^Остановлен", "^Аккаунт «",
            "^Модуль:", "^Конфиг «", "^Меняю корабль", "^Корабль сменён", "^Бот работает до", "^Пауза",
            "^Связь восстановлена", "^Выхожу из игры", "^Лечу на рабочую карту",
            "^Трюм полон", "^Продал", "^Обменял", "^Покупаю", "^Потрачено", "^Торговец не покупает",
            "^Выполняю проверку", "^Ящики проверки собраны",
            "^Прыжок на карту"));

    /**
     * Never in the journal: warnings of the everyday kind (they would bury the real ones) and the echoes of one
     * event told by several parts (a death: the connection state, the kill screen's choices, the wait for it —
     * the journal keeps «Корабль уничтожен (кем) — смертей N»).
     */
    private static final Pattern NOT_JOURNAL = p(String.join("|",
            "бросаю цель$", "^Нет пути к", "сервер не ответил за", "^Диагностика", "Мина на пути",
            "^Состояние:", "жду выбора ремонта", "^Корабль уничтожен$", "\\. Варианты: "));

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    /** @param logger the full logger name; @param warn WARN or ERROR */
    public static Kind of(String logger, boolean warn, String message) {
        String simple = simpleName(logger);
        String m = message == null ? "" : message;
        LogCategory c = null;
        for (Map.Entry<Pattern, LogCategory> e : BY_TEXT) {
            if (e.getKey().matcher(m).find()) {
                c = e.getValue();
                break;
            }
        }
        if (c == null) {
            c = BY_LOGGER.getOrDefault(simple, LogCategory.OTHER);
        }
        boolean important = !NOT_JOURNAL.matcher(m).find()
                && (warn || c == LogCategory.GROUP || JOURNAL.matcher(m).find());
        return new Kind(c, important);
    }

    static String simpleName(String logger) {
        if (logger == null) {
            return "";
        }
        int dot = logger.lastIndexOf('.');
        return dot < 0 ? logger : logger.substring(dot + 1);
    }
}
