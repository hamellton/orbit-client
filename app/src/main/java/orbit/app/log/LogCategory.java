package orbit.app.log;

import java.awt.Color;

/** What a log line is about, for the log's filters and its coloured tags. */
public enum LogCategory {
    WORK("Работа", 0x60a5fa),
    SUMMARY("Итоги", 0xa78bfa),
    BATTLE("Бой", 0xf87171),
    /** What the game paid: experience, honor, credits, uridium, items (per kill and summed up). */
    REWARDS("Добыча", 0xeab308),
    /** Boxes and ores picked up. */
    LOOT("Сбор", 0xfbbf24),
    TRADE("Торговля", 0x34d399),
    SAFETY("Безопасность", 0xfb923c),
    MOVE("Полёты", 0x38bdf8),
    QUESTS("Квесты", 0xe879f9),
    PET("Питомец", 0xf472b6),
    GROUP("Группа", 0x2dd4bf),
    SHIP("Корабль", 0xa3e635),
    CONNECTION("Связь", 0x94a3b8),
    OTHER("Прочее", 0x9ca3af);

    private final String label;
    private final Color color;

    LogCategory(String label, int rgb) {
        this.label = label;
        this.color = new Color(rgb);
    }

    public String label() {
        return label;
    }

    public Color color() {
        return color;
    }
}
