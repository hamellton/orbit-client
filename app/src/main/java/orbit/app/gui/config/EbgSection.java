package orbit.app.gui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import orbit.config.BotConfig;

/**
 * «Вечные врата» (EBG, docs/EBG.md; the user, 2026-11-16: «всё через конфиг, очень понятно и просто, чтобы никто не
 * запутался»): on or off, how many runs, the CPU and its craft, what then, the lasers enriched before a run, the brake,
 * the Uber Kristallons, the boosters and the abilities. Writes {@code modules.gate.eternal} and the gate list.
 */
final class EbgSection implements Section {

    static final String KEY = GateSection.KEY;
    static final String[] MAPS = {"Своей компании", "1-BL", "2-BL", "3-BL"};
    static final String[] OUT = {"Улететь на базу и выйти из игры", "Ждать на месте"};
    static final String[] BRAKE = {"Самоубийство у NPC (быстро, следующий забег сразу)", "Выйти порталом домой"};
    static final List<String> BOOSTERS = List.of("DAMAGE", "DAMAGE_LASER", "HITCHANCE_LASER", "HITPOINTS",
            "ABILITY_COOLDOWN_TIME", "DAMAGE_ROCKETS", "SHIELD", "SPEED");
    static final String[] BOOSTERS_RU = {"Урон", "Урон лазеров", "Точность лазеров", "Корпус (HP)", "Перезарядка",
        "Урон ракет", "Щит", "Скорость"};
    static final int[] BOOSTERS_DEFAULT = {0, 0, 0, 1, 1, 1, 2, 2};
    static final String HARD_NPCS = "uberkristallon, strokelight barrage, uberprotegit";

    private final JCheckBox on = new JCheckBox("Проходить Вечные врата (EBG)");
    private final JSpinner runs = Form.number(0, 10_000, 1);
    private final JComboBox<String> map = new JComboBox<>(MAPS);
    private final JCheckBox craft = new JCheckBox("Крафтить CPU в сборке, если они кончились (пока хватает ресурсов)");
    private final JSpinner craftKeep = Form.number(1, 50, 1);
    private final JComboBox<String> whenOut = new JComboBox<>(OUT);
    private final JSpinner lasersMin = Form.number(0, 100_000_000, 10_000);
    private final JSpinner lasersWait = Form.number(1, 240, 5);
    private final JCheckBox lasersGo = new JCheckBox("Если не обогатились за это время — идти всё равно");
    private final JSpinner brakeWave = Form.number(0, 999, 1);
    private final JComboBox<String> brakeAction = new JComboBox<>(BRAKE);
    private final JCheckBox split = new JCheckBox("Разводить двух Uber Kristallon по разным углам (волна 47 каждого цикла)");
    private final JCheckBox boostAuto = new JCheckBox("Выбирать бустеры между волнами самому");
    private final JSpinner[] boostPrio = new JSpinner[BOOSTERS.size()];
    private final JCheckBox ish = new JCheckBox("ISH-01 (щит) на тяжёлых NPC");
    private final JTextField ishNpcs = new JTextField(30);
    private final JCheckBox emp = new JCheckBox("EMP-01 на тяжёлых NPC");
    private final JTextField empNpcs = new JTextField(30);
    private final JCheckBox ability = new JCheckBox("Способность корабля (Orcus и др.) на тяжёлых NPC");
    private final JTextField abilityNpcs = new JTextField(30);
    private final JCheckBox leech = new JCheckBox("Энергетическая пиявка");
    private final JSpinner leechWave = Form.number(1, 999, 1);
    private final JComponent view;

    EbgSection() {
        Form f = new Form()
                .section("Вечные врата Блэклайт")
                .row("", on)
                .note("Работает с модулем «Галактические врата» (вкладка «Общее»). Вход: на BL-карте бот использует CPU,"
                        + " открывается портал, бот прыгает. Жизнь одна: смерть — забег окончен, следующий снова с 1-й"
                        + " волны. Бьёт всех NPC, кроме Abide I и Steadfast III на волне босса, как DarkBot: волна не"
                        + " кончится, пока живы её NPC.")
                .row("Сколько забегов сделать (0 — без конца)", runs)
                .row("BL-карта для входа", map)
                .section("CPU")
                .row("", craft)
                .row("Держать CPU не меньше", craftKeep)
                .row("Когда CPU нет и скрафтить не из чего", whenOut)
                .section("Лазеры перед забегом")
                .row("Не входить, пока на лазерах меньше, выстрелов (0 — не проверять)", lasersMin)
                .row("Ждать обогащения не дольше, мин", lasersWait)
                .row("", lasersGo)
                .note("Обогащает вкладка «Обогащение» (из трюма, со Скайлаба или пересадкой на корабль для Скайлаба)."
                        + " Здесь только порог: бот ждёт, пока лазеры наберут нужное, и только потом летит в портал.")
                .section("Тормоз — закончить забег на волне")
                .row("Волна (0 — не заканчивать)", brakeWave)
                .row("Как закончить", brakeAction)
                .note("Дальше волны идут всё сильнее. Самоубийство — корабль перестаёт стрелять, подлетает к NPC и"
                        + " ждёт гибели (ремонт на базе бесплатный). Выход — между волнами в портал домой.")
                .section("Бой")
                .row("", split)
                .section("Бустеры (меньше число — важнее, 0–10)")
                .row("", boostAuto);
        for (int i = 0; i < BOOSTERS.size(); i++) {
            boostPrio[i] = Form.number(0, 10, 1);
            f.row(BOOSTERS_RU[i], boostPrio[i]);
        }
        f.section("Способности (имена NPC через запятую)")
                .row("", Form.inline(ish, ishNpcs))
                .row("", Form.inline(emp, empNpcs))
                .row("", Form.inline(ability, abilityNpcs))
                .row("", Form.inline(leech, new JLabel("с волны"), leechWave))
                .note("Порог HP для ISH — на вкладке «Атака». По умолчанию тяжёлые: " + HARD_NPCS + ".");
        view = f.build(true);
    }

    @Override
    public String title() {
        return "Вечные врата";
    }

    @Override
    public JComponent view() {
        return view;
    }

    private static boolean b(JsonNode n, String k, boolean d) {
        return n.path(k).isBoolean() ? n.path(k).asBoolean() : d;
    }

    private static String npcs(JsonNode n) {
        if (!n.path("npcs").isArray()) {
            return HARD_NPCS;
        }
        java.util.List<String> out = new java.util.ArrayList<>();
        n.path("npcs").forEach(x -> out.add(x.asText()));
        return String.join(", ", out);
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode s = c.moduleSettings(KEY);
        boolean listed = false;
        for (JsonNode g : s.path("gates")) {
            listed |= "eternal".equals(g.isTextual() ? g.asText() : g.path("gate").asText());
        }
        on.setSelected(listed || s.path("gateId").asInt(0) == -4);
        JsonNode e = s.path("eternal");
        Form.set(runs, e.path("runs").asInt(0));
        String m = e.path("map").asText("own");
        map.setSelectedItem("own".equalsIgnoreCase(m) ? MAPS[0] : m);
        craft.setSelected(b(e, "craft", false));
        Form.set(craftKeep, e.path("craftKeep").asInt(1));
        whenOut.setSelectedIndex("wait".equalsIgnoreCase(e.path("whenOut").asText("logout")) ? 1 : 0);
        Form.set(lasersMin, e.path("lasersMin").asInt(0));
        Form.set(lasersWait, e.path("lasersWaitMinutes").asInt(20));
        lasersGo.setSelected(b(e, "lasersGoAnyway", true));
        Form.set(brakeWave, e.path("brakeOnWave").asInt(0));
        brakeAction.setSelectedIndex("EXIT".equalsIgnoreCase(e.path("brakeAction").asText("SUICIDE")) ? 1 : 0);
        split.setSelected(b(e, "splitUberKristallon", true));
        boostAuto.setSelected(b(e.path("boosters"), "auto", true));
        for (int i = 0; i < BOOSTERS.size(); i++) {
            Form.set(boostPrio[i], e.path("boosters").path("priority").path(BOOSTERS.get(i)).asInt(BOOSTERS_DEFAULT[i]));
        }
        ish.setSelected(b(e.path("ish"), "on", false));
        ishNpcs.setText(npcs(e.path("ish")));
        emp.setSelected(b(e.path("emp"), "on", false));
        empNpcs.setText(npcs(e.path("emp")));
        ability.setSelected(b(e.path("ability"), "on", false));
        abilityNpcs.setText(npcs(e.path("ability")));
        leech.setSelected(b(e.path("leech"), "on", false));
        Form.set(leechWave, e.path("leech").path("fromWave").asInt(123));
    }

    private static void npcs(ObjectNode n, boolean on, String text) {
        n.put("on", on);
        ArrayNode a = n.putArray("npcs");
        for (String x : text.split(",")) {
            if (!x.isBlank()) {
                a.add(x.strip().toLowerCase(java.util.Locale.ROOT));
            }
        }
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode s = c.moduleSettings(KEY);
        boolean listed = false;
        for (JsonNode g : s.path("gates")) {
            listed |= "eternal".equals(g.isTextual() ? g.asText() : g.path("gate").asText());
        }
        if (!on.isSelected() && !listed && !s.has("eternal")) {
            return c; // a config of other gates keeps no EBG section
        }
        if (on.isSelected()) {
            ArrayNode gates = s.putArray("gates");
            gates.add("eternal"); // EBG is played alone
        } else if (listed) {
            ArrayNode gates = s.putArray("gates");
            for (String k : List.of("alpha", "beta", "gamma")) {
                gates.add(k);
            }
        }
        ObjectNode e = s.putObject("eternal");
        e.put("runs", Form.intOf(runs));
        e.put("map", map.getSelectedIndex() == 0 ? "own" : String.valueOf(map.getSelectedItem()));
        e.put("craft", craft.isSelected());
        e.put("craftKeep", Form.intOf(craftKeep));
        e.put("whenOut", whenOut.getSelectedIndex() == 1 ? "wait" : "logout");
        e.put("lasersMin", Form.intOf(lasersMin));
        e.put("lasersWaitMinutes", Form.intOf(lasersWait));
        e.put("lasersGoAnyway", lasersGo.isSelected());
        e.put("brakeOnWave", Form.intOf(brakeWave));
        e.put("brakeAction", brakeAction.getSelectedIndex() == 1 ? "EXIT" : "SUICIDE");
        e.put("splitUberKristallon", split.isSelected());
        ObjectNode bo = e.putObject("boosters");
        bo.put("auto", boostAuto.isSelected());
        ObjectNode prio = bo.putObject("priority");
        for (int i = 0; i < BOOSTERS.size(); i++) {
            prio.put(BOOSTERS.get(i), Form.intOf(boostPrio[i]));
        }
        npcs(e.putObject("ish"), ish.isSelected(), ishNpcs.getText());
        npcs(e.putObject("emp"), emp.isSelected(), empNpcs.getText());
        npcs(e.putObject("ability"), ability.isSelected(), abilityNpcs.getText());
        ObjectNode l = e.putObject("leech");
        l.put("on", leech.isSelected());
        l.put("fromWave", Form.intOf(leechWave));
        return c.withModuleSettings(KEY, s);
    }
}
