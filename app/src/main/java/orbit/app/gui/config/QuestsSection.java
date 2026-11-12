package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «Квесты» (QUESTS, plan 5б.9): which quest types the bot takes at the home base's quest giver, what kinds of
 * conditions it does, and how often it looks at the giver again ({@code modules.quests}: {@code types}, {@code do},
 * {@code revisitMinutes}).
 */
final class QuestsSection implements Section {

    private static final String KEY = BotConfig.Module.QUESTS.key();
    /** Types by setting key; the first four are on by default (the user's choice 2026-11-12). */
    static final Map<String, String> TYPES = new LinkedHashMap<>();
    /** Condition kinds by setting key, all on by default. */
    static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        TYPES.put("DAILY", "Ежедневные");
        TYPES.put("WEEKLY", "Еженедельные");
        TYPES.put("EVENT", "Событийные");
        TYPES.put("MISSION", "Миссии (цепочки)");
        TYPES.put("CHALLENGE", "Испытания");
        KINDS.put("KILL", "Убить NPC");
        KINDS.put("DAMAGE", "Урон по NPC");
        KINDS.put("SALVAGE", "Подобрать груз с убитых NPC");
        KINDS.put("BOX", "Собрать бонусные ящики");
        KINDS.put("ORE", "Собрать руду");
        KINDS.put("SELL", "Продать руду");
        KINDS.put("MAP", "Побывать на карте, долететь до точки");
    }

    static final List<String> DEFAULT_TYPES = List.of("DAILY", "WEEKLY", "EVENT", "MISSION");

    private final Map<String, JCheckBox> types = new LinkedHashMap<>();
    private final Map<String, JCheckBox> kinds = new LinkedHashMap<>();
    private final JSpinner revisit = Form.number(30, 1_440, 10);
    private final JCheckBox locator = new JCheckBox("Искать NPC квеста пеленгатором питомца");
    private final JComponent view;

    QuestsSection() {
        Form f = new Form().section("Какие квесты брать");
        TYPES.forEach((k, label) -> {
            JCheckBox b = new JCheckBox(label);
            types.put(k, b);
            f.row("", b);
        });
        f.note("Квесты берутся у квестодателя своей базы (x-8, иначе x-1). Миссии — пока есть свободные места,"
                + " ежедневные и еженедельные места не занимают.");
        f.section("Что делать по квестам");
        KINDS.forEach((k, label) -> {
            JCheckBox b = new JCheckBox(label);
            kinds.put(k, b);
            f.row("", b);
        });
        f.note("Берутся только квесты, все условия которых бот умеет. Не делает никогда: убить игроков или урон по"
                + " ним, пройти врата. NPC — на ближайшей карте, где их видели; патроны, безопасность и ящики — из"
                + " этого конфига.");
        f.row("", locator);
        f.note("Пока бот бьёт NPC квеста, питомец в режиме «Пеленгатор врагов» на этого NPC, и бот летит туда, куда"
                + " он показывает. Потом питомец возвращается к режиму из вкладки «Питомец». Питомец должен быть там"
                + " включён; продажа через торговый модуль и камикадзе важнее пеленгатора.");
        f.section("Квестодатель");
        f.row("Заглядывать снова раз в, мин", revisit);
        f.note("И после каждого сданного квеста — за следующим в цепочке; если делать нечего — раз в час.");
        view = f.build(true);
    }

    @Override
    public String title() {
        return "Квесты";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        List<String> on = strings(m.path("types"), DEFAULT_TYPES);
        types.forEach((k, b) -> b.setSelected(on.contains(k)));
        List<String> doing = strings(m.path("do"), List.copyOf(KINDS.keySet()));
        kinds.forEach((k, b) -> b.setSelected(doing.contains(k)));
        Form.set(revisit, m.path("revisitMinutes").asInt(360));
        locator.setSelected(m.path("locator").asBoolean(false));
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        boolean untouched = c.module() != BotConfig.Module.QUESTS && m.isEmpty();
        ArrayNode t = m.putArray("types");
        types.forEach((k, b) -> {
            if (b.isSelected()) {
                t.add(k);
            }
        });
        ArrayNode d = m.putArray("do");
        kinds.forEach((k, b) -> {
            if (b.isSelected()) {
                d.add(k);
            }
        });
        m.put("revisitMinutes", Form.intOf(revisit));
        m.put("locator", locator.isSelected());
        if (untouched && strings(t, List.of()).equals(DEFAULT_TYPES) && d.size() == KINDS.size()
                && Form.intOf(revisit) == 360 && !locator.isSelected()) {
            return c; // nothing set for a config of another module: do not add the section
        }
        return c.withModuleSettings(KEY, m);
    }

    private static List<String> strings(com.fasterxml.jackson.databind.JsonNode n, List<String> def) {
        if (!n.isArray()) {
            return def;
        }
        List<String> out = new java.util.ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }
}
