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
        return c.type() == Quests.TIMER || c.type() == Quests.HASTE || c.type() == Quests.COUNTDOWN
                || c.type() == Quests.REAL_TIME_HASTE || c.type() == Quests.ENDURANCE;
    }

    /** A condition in Russian (kill Protegit, collect promerium on 1-BL or 3-BL…) with the maps it is bound to. */
    public static String condition(Quests.Condition c) {
        List<Integer> on = c.onMaps();
        String text = bare(c);
        return on.isEmpty() ? text : text + " (на " + String.join(" или ", on.stream().map(QuestTexts::map).toList()) + ")";
    }

    private static String bare(Quests.Condition c) {
        return switch (c.type()) {
            case Quests.KILL_NPC -> "убить " + npc(c.match(0));
            case Quests.KILL_NPCS -> "убить " + String.join(" или ", npcs(c));
            case Quests.DAMAGE_NPCS -> "урон по " + String.join(" или ", npcs(c));
            case Quests.COLLECT -> "собрать " + ore(c.match(0));
            case Quests.SELL_ORE -> "продать " + ore(c.match(0));
            case Quests.COLLECT_LOOT -> "собрать " + (c.matches().isEmpty() ? "предметы" : c.matches().get(0));
            case Quests.REFINE_ORE -> "переработать " + (c.matches().isEmpty() ? "руду" : c.matches().get(0));
            case Quests.COLLECT_BONUS_BOX -> "собрать бонусные ящики";
            case Quests.SALVAGE -> "подобрать груз с NPC" + (c.match(4) > 0 ? " (" + ore(c.match(4)) + ")" : "")
                    + (c.match(2) > 0 ? ": " + npc(c.match(2)) : "");
            case Quests.VISIT_MAP -> "побывать на " + map(c.match(0));
            case Quests.MAP, Quests.ON_MAP -> "на карте " + String.join(" или ", maps(c));
            case Quests.COORDINATES -> "долететь до " + c.match(0) / 100 + "/" + c.match(1) / 100 + " ("
                    + map(c.match(2)) + ")";
            case Quests.TRAVEL -> "пролететь";
            case Quests.KILL_PLAYERS, Quests.KILL_PLAYER -> "убить игроков";
            case Quests.DAMAGE_PLAYERS -> "урон по игрокам";
            case Quests.AVOID_DEATH -> c.target() > 0 ? "умереть не больше " + (long) c.target() + " раз" : "не умереть";
            case Quests.ENDURANCE -> "продержаться " + seconds(c.target());
            case Quests.REAL_TIME_HASTE -> "успеть за " + seconds(c.target()) + " (осталось " + seconds(c.current()) + ")";
            case Quests.MISCELLANEOUS -> "особое условие";
            case Quests.TIMER, Quests.HASTE, Quests.COUNTDOWN -> c.target() > 0
                    ? "за " + seconds(c.target()) + " (прошло " + seconds(c.current()) + ")" : "на время";
            case Quests.LEVEL -> "достичь " + (long) c.target() + " уровня";
            case Quests.VISIT_QUEST_GIVER -> "к квестодателю";
            case Quests.FINISH_GALAXY_GATE -> "пройти врата " + map(c.match(1));
            case Quests.CARGO -> "подобрать грузовые ящики";
            case Quests.COLLECT_BUILT_RECIPE -> "собрать скрафченное" + (c.matches().isEmpty() ? " (любой рецепт)"
                    : ": " + c.matches().get(0));
            case Quests.COMPLETED_OBJECTIVES -> "выполнить задания квестов";
            case Quests.COMPLETE_SEASON_PASS_QUESTS -> "выполнить миссии Season Pass";
            case Quests.DURING_EVENT -> "во время события " + (c.matches().isEmpty() ? "" : c.matches().get(0));
            case Quests.SPEND_AMMUNITION -> "выстрелить " + switch (c.match(0)) {
                case 1 -> "лазерными боеприпасами";
                case 2 -> "ракетами";
                default -> "боеприпасами";
            };
            case Quests.USE_ORE_UPDATE -> "улучшить рудой " + (c.matches().size() < 2 ? "в лаборатории"
                    : switch (c.matches().get(0)) {
                        case "LASER" -> "лазеры";
                        case "ROCKET" -> "ракеты";
                        case "SPEED" -> "двигатель";
                        case "SHIELD" -> "щит";
                        default -> c.matches().get(0);
                    } + " (" + item("resource_ore_" + c.matches().get(1).toLowerCase()) + ")");
            case Quests.FINISH_GALAXY_GATES -> "пройти любые из врат";
            case Quests.SPEND_ITEM -> "потратить " + (c.matches().isEmpty() ? "предметы" : item(c.matches().get(0)));
            default -> OTHER.getOrDefault(c.type(), "условие " + c.type());
        };
    }

    private static String item(String lootId) {
        String n = ItemNames.of(lootId);
        return n == null ? lootId : n;
    }

    /** The client's other condition types ({@code QuestConditionModule}), told by their kind only. */
    static final Map<Integer, String> OTHER = Map.ofEntries(Map.entry(0, "условие квеста"), Map.entry(7, "нанести урон"),
            Map.entry(8, "не получать урона"), Map.entry(9, "получить урон"), Map.entry(12, "держаться на расстоянии"),
            Map.entry(14, "без топлива"), Map.entry(15, "быть рядом"), Map.entry(17, "побывать на разных картах"),
            Map.entry(18, "пустое условие"), Map.entry(20, "боеприпасы"), Map.entry(21, "сберечь боеприпасы"),
            Map.entry(24, "украсть"), Map.entry(25, "убить"), Map.entry(26, "нанести урон"),
            Map.entry(31, "урон по игрокам чужих фирм"), Map.entry(32, "уничтожить объекты карты"),
            Map.entry(33, "урон по объектам карты"), Map.entry(35, "погибнуть"), Map.entry(36, "не убивать NPC"),
            Map.entry(37, "не убивать NPC"), Map.entry(38, "не убивать игроков"), Map.entry(39, "не наносить урон NPC"),
            Map.entry(40, "не наносить урон игрокам"), Map.entry(41, "не допустить"), Map.entry(42, "прыгнуть"),
            Map.entry(43, "не прыгать"), Map.entry(44, "не двигаться"), Map.entry(45, "множитель"),
            Map.entry(46, "держаться подальше"), Map.entry(47, "быть в группе"), Map.entry(48, "убить кого угодно"),
            Map.entry(49, "на сайте"), Map.entry(50, "в клиенте"), Map.entry(54, "особое условие"),
            Map.entry(56, "выполнить квест"), Map.entry(57, "быстрая покупка"), Map.entry(58, "вступить в группу"),
            Map.entry(59, "убить NPC только этими боеприпасами"), Map.entry(60, "убить игроков только этими боеприпасами"),
            Map.entry(64, "честь ниже нуля"), Map.entry(65, "особая честь"), Map.entry(66, "быть в клане"),
            Map.entry(68, "пройти стартовые врата"), Map.entry(70, "поставить предмет на панель"),
            Map.entry(71, "побывать на картах типа"), Map.entry(73, "пройти портал на карты типа"),
            Map.entry(74, "включить объект карты"), Map.entry(75, "посетить объект карты"),
            Map.entry(76, "улучшить Скайлаб до уровня"), Map.entry(78, "захватить маяк"),
            Map.entry(81, "набрать влияние"), Map.entry(82, "задание"), Map.entry(83, "побывать в зоне помех"),
            Map.entry(84, "успеть до даты"), Map.entry(86, "собрать ящики типа"), Map.entry(88, "включить процессор"),
            Map.entry(90, "пройти волну врат"), Map.entry(91, "очки Астрального вознесения"),
            Map.entry(92, "собрать трофейные ящики типа"), Map.entry(93, "потратить ингредиенты рецептов"),
            Map.entry(94, "есть золотой пропуск"), Map.entry(96, "войти в игру"),
            Map.entry(98, "получить корабль"), Map.entry(99, "получить дизайн корабля"),
            Map.entry(100, "пополнение на сумму"), Map.entry(101, "бустеры"));

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
        if (s >= 3600) {
            return s / 3600 + " ч" + (s % 3600 / 60 > 0 ? " " + s % 3600 / 60 + " мин" : "");
        }
        return s >= 60 ? s / 60 + " мин" + (s % 60 > 0 ? " " + s % 60 + " с" : "") : s + " с";
    }

    /** A reward in Russian, such as 425 000 000 experience. */
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

    /** The quest types in Russian, such as mission or daily. */
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
