package orbit.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import orbit.world.maps.MapCatalog;
import orbit.world.maps.NpcCatalog;

/** Russian words for quest conditions and rewards (the meaning of {@code matches} — client {@code questfm/conditions}). */
public final class QuestTexts {

    private QuestTexts() {
    }

    /** The client's old ore numbers ({@code InGameCatalog.as}). */
    static final Map<Integer, String> ORES = Map.ofEntries(Map.entry(1, "прометий"), Map.entry(2, "эндуриум"),
            Map.entry(3, "тербий"), Map.entry(4, "ксеномит"), Map.entry(11, "прометид"), Map.entry(12, "дюраниум"),
            Map.entry(13, "промериум"), Map.entry(14, "сепром"), Map.entry(15, "палладий"), Map.entry(42, "осмий"));

    public static String ore(int id) {
        return id == 0 ? "любой руды" : ORES.getOrDefault(id, "руды №" + id);
    }

    public static String npc(int id) {
        String n = NpcCatalog.bundled().name(id);
        return n == null ? "NPC №" + id : n;
    }

    public static String map(int id) {
        return MapCatalog.bundled().info(id).name();
    }

    /** Timer-like conditions: target and value are milliseconds. */
    public static boolean isTime(Quests.Condition c) {
        return c.type() == Quests.TIMER || c.type() == Quests.HASTE || c.type() == Quests.COUNTDOWN;
    }

    /** {@code убить Protegit}, {@code собрать прометий}, {@code побывать на 1-5}… */
    public static String condition(Quests.Condition c) {
        return switch (c.type()) {
            case Quests.KILL_NPC -> "убить " + npc(c.match(0));
            case Quests.KILL_NPCS -> "убить " + String.join(" или ", npcs(c));
            case Quests.DAMAGE_NPCS -> "урон по " + String.join(" или ", npcs(c));
            case Quests.COLLECT -> "собрать " + ore(c.match(0));
            case Quests.SELL_ORE -> "продать " + ore(c.match(0));
            case Quests.COLLECT_LOOT -> "собрать " + (c.matches().isEmpty() ? "предметы" : c.matches().get(0));
            case Quests.REFINE_ORE -> "переработать " + (c.matches().isEmpty() ? "руду" : c.matches().get(0));
            case Quests.COLLECT_BONUS_BOX -> "собрать бонусные ящики";
            case Quests.VISIT_MAP -> "побывать на " + map(c.match(0));
            case Quests.MAP, Quests.ON_MAP -> "на карте " + String.join(" или ", maps(c));
            case Quests.COORDINATES -> "долететь до " + c.match(0) / 100 + "/" + c.match(1) / 100 + " ("
                    + map(c.match(2)) + ")";
            case Quests.TRAVEL -> "пролететь";
            case Quests.KILL_PLAYERS, Quests.KILL_PLAYER -> "убить игроков";
            case Quests.DAMAGE_PLAYERS -> "урон по игрокам";
            case Quests.AVOID_DEATH -> c.target() > 0 ? "умереть не больше " + (long) c.target() + " раз" : "не умереть";
            case Quests.TIMER, Quests.HASTE, Quests.COUNTDOWN -> c.target() > 0
                    ? "за " + seconds(c.target()) + " (прошло " + seconds(c.current()) + ")" : "на время";
            case Quests.LEVEL -> "достичь " + (long) c.target() + " уровня";
            case Quests.VISIT_QUEST_GIVER -> "к квестодателю";
            case Quests.FINISH_GALAXY_GATE -> "пройти врата " + map(c.match(1));
            default -> "условие " + c.type();
        };
    }

    private static List<String> npcs(Quests.Condition c) {
        List<String> out = new ArrayList<>();
        int n = c.match(0);
        for (int i = 1; i <= n && i < c.matches().size(); i++) {
            out.add(npc(c.match(i)));
        }
        return out.isEmpty() ? List.of("NPC") : out;
    }

    private static List<String> maps(Quests.Condition c) {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < c.matches().size(); i++) {
            if (c.match(i) > 0) {
                out.add(map(c.match(i)));
            }
        }
        return out.isEmpty() ? List.of("?") : out;
    }

    private static String seconds(double ms) {
        long s = (long) (ms / 1000);
        return s >= 60 ? s / 60 + " мин" + (s % 60 > 0 ? " " + s % 60 + " с" : "") : s + " с";
    }

    /** {@code 425 000 000 опыта}… */
    public static String reward(Quests.Reward r) {
        String what = switch (r.lootId()) {
            case "currency_experience" -> "опыта";
            case "currency_honour" -> "чести";
            case "currency_credits" -> "кредитов";
            case "currency_uridium" -> "уридия";
            default -> r.lootId();
        };
        return String.format(java.util.Locale.ROOT, "%,d", r.amount()).replace(',', ' ') + " " + what;
    }

    /** {@code миссия}, {@code ежедневный}… */
    public static String type(List<Integer> types) {
        for (int t : types) {
            String s = switch (t) {
                case Quests.STARTER -> "начальный";
                case Quests.MISSION -> "миссия";
                case Quests.DAILY -> "ежедневный";
                case Quests.CHALLENGE -> "испытание";
                case Quests.EVENT -> "событие";
                case Quests.WEEKLY -> "еженедельный";
                case Quests.BATTLEPASS -> "Battle Pass";
                case Quests.DIMINISHING -> "убывающий";
                case Quests.SEASONPASS -> "Season Pass";
                default -> null;
            };
            if (s != null) {
                return s;
            }
        }
        return "квест";
    }
}
