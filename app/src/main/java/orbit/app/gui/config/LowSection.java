package orbit.app.gui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «LoW»: the group gate's settings (docs/LOW.md; the user, 2026-11-18) — the leader's helper accounts (§11: the one running
 * the config leads and starts them himself), how the gate is passed (the leader alone, the helpers out; the anchor; all together), the start, the relays,
 * the Vagrants of the Falcon, the chase, the life saver on the Falcon, the mines. Writes {@code modules.low}.
 */
final class LowSection implements Section {

    static final String KEY = BotConfig.Module.LOW.key();
    static final String[] MODES = {
        "Лидер проходит один: помощники после запуска выходят из группы и уходят",
        "Лидер один, помощники остаются в группе (якорь: лидер вернётся после гибели)",
        "Все вместе в группе (награда делится)"};
    static final String[] MODE_KEYS = {"solo", "anchor", "together"};
    static final String[] EXITS = {
        "Погибнуть в радиации, возродиться на базе и выйти из игры до следующего захода",
        "Погибнуть у NPC, не стреляя (если радиации не видно), и выйти из игры до следующего захода",
        "Остаться во вратах и бить NPC самим (свои награды)"};
    static final String[] EXIT_KEYS = {"radiation", "npc", "stay"};
    static final String[] RELAYS = {"Не ждать", "У реле 1", "У реле 2", "У реле 3", "У реле 4"};
    static final String[] MINES = {"ammunition_mine_ddm-01", "ammunition_mine_acm-01", "ammunition_mine_empm-01",
        "ammunition_mine_sabm-01", "ammunition_mine_slm-01", "ammunition_mine_smb-01"};
    static final String[] MINES_RU = {"DDM-01 (урон)", "ACM-01", "EMPM-01 (ЭМИ)", "SABM-01 (щит)", "SLM-01 (замедление)",
        "SMB-01"};

    static final String[] BETWEEN = {"Ждать на базе, в игре", "Выйти из игры и войти, когда лидер соберёт группу"};
    static final String[] BETWEEN_KEYS = {"base", "logout"};

    private final javax.swing.JPanel helpers = new javax.swing.JPanel(new orbit.app.gui.WrapLayout(
            java.awt.FlowLayout.LEFT, 10, 2));
    private final java.util.List<JCheckBox> helperBoxes = new java.util.ArrayList<>();
    private final JComboBox<String> between = new JComboBox<>(BETWEEN);
    private final JComboBox<String> mode = new JComboBox<>(MODES);
    private final JComboBox<String> exit = new JComboBox<>(EXITS);
    private final JSpinner restMinutes = Form.number(5, 600, 5);
    private final JSpinner minGroup = Form.number(1, 8, 1);
    private final JSpinner gatherMinutes = Form.number(1, 60, 1);
    private final JCheckBox waitForAll = new JCheckBox("Не заряжать реле, пока не вошли все участники группы (как Kekka)");
    private final JComboBox<String> waitAtRelay = new JComboBox<>(RELAYS);
    private final JSpinner leaveVagrants = Form.number(0, 20, 1);
    private final JSpinner chaseMax = Form.number(0, 10_000, 100);
    private final JCheckBox followLeader = new JCheckBox("Участники бьют ту же цель, что и лидер");
    private final JSpinner boxRadius = Form.number(0, 10_000, 100);
    private final JCheckBox emp = new JCheckBox("EMP-01 на Century Falcon при HP ниже, %");
    private final JSpinner empBelow = Form.number(1, 99, 5);
    private final JCheckBox ish = new JCheckBox("ISH-01 на Century Falcon при HP ниже, %");
    private final JSpinner ishBelow = Form.number(1, 99, 5);
    private final JCheckBox ability = new JCheckBox("Способность корабля (Orcus и др.) на Century Falcon");
    private final JCheckBox mines = new JCheckBox("Ставить мины, когда рядом с кораблём NPC не меньше");
    private final JSpinner minesNpcs = Form.number(1, 30, 1);
    private final JComboBox<String> mineItem = new JComboBox<>(MINES_RU);
    private final JComponent view;

    LowSection() {
        Form f = new Form()
                .section("Группа")
                .note("Работает с модулем «LoW (групповые врата)» (выбирается в шапке). Во врата пускают группу от трёх:"
                        + " лидер прыгает первым на x-3 в портал, помощники — за ним. Этот конфиг ставится только лидеру,"
                        + " запускать нужно только его: помощников он запускает сам, если они не в работе. У помощника"
                        + " остаются свой конфиг (ремонт, покупки, питомец) и свой прокси. Остановили лидера — помощники"
                        + " тоже остановятся.")
                .row("Помощники (аккаунты)", helpers)
                .row("Как проходить", mode)
                .row("Помощники после запуска", exit)
                .row("Между заходами помощники", between)
                .row("Вне игры не дольше, мин", restMinutes)
                .row("Прыгать, когда в группе не меньше", minGroup)
                .row("Ждать участников у портала не дольше, мин", gatherMinutes)
                .section("Во вратах")
                .row("", waitForAll)
                .row("Ждать участников перед зарядом реле", waitAtRelay)
                .row("Оставлять Vagrant живыми при живом Century Falcon", leaveVagrants)
                .note("Реле заряжаются по порядку DarkBot (4, 2, 3, 1) и только когда NPC нет. Пока жив босс и рядом"
                        + " осталось столько Vagrant или меньше, их не трогаем — иначе босс зовёт новую стаю.")
                .row("Не гнаться за убежавшим NPC дальше (0 — всегда)", chaseMax)
                .row("", followLeader)
                .row("Радиус сбора ящиков между волнами", boxRadius)
                .section("Спасение жизни на боссе")
                .row("", Form.inline(emp, empBelow))
                .row("", Form.inline(ish, ishBelow))
                .row("", ability)
                .section("Мины")
                .row("", Form.inline(mines, minesNpcs, new JLabel("мина"), mineItem));
        view = f.build(true);
    }

    @Override
    public Group group() {
        return Group.MODULE;
    }

    @Override
    public java.util.Set<BotConfig.Module> modules() {
        return java.util.Set.of(BotConfig.Module.LOW);
    }

    @Override
    public String title() {
        return "LoW";
    }

    @Override
    public JComponent view() {
        return view;
    }

    private static boolean b(JsonNode n, String k, boolean d) {
        return n.path(k).isBoolean() ? n.path(k).asBoolean() : d;
    }

    private static int index(String[] keys, String v, int def) {
        for (int i = 0; i < keys.length; i++) {
            if (keys[i].equalsIgnoreCase(v)) {
                return i;
            }
        }
        return def;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode s = c.moduleSettings(KEY);
        java.util.Set<String> chosen = new java.util.LinkedHashSet<>();
        s.path("helpers").forEach(x -> chosen.add(x.asText()));
        java.util.List<String> all = AccountNames.all();
        for (String x : chosen) {
            if (all.stream().noneMatch(x::equalsIgnoreCase)) {
                all.add(x); // a helper not in the program (any more) stays listed, to be unticked
            }
        }
        helpers.removeAll();
        helperBoxes.clear();
        for (String a : all) {
            JCheckBox box = new JCheckBox(a, chosen.stream().anyMatch(a::equalsIgnoreCase));
            helperBoxes.add(box);
            helpers.add(box);
        }
        if (all.isEmpty()) {
            helpers.add(new JLabel("Аккаунтов нет — добавьте их в окне аккаунтов"));
        }
        helpers.revalidate();
        between.setSelectedIndex(index(BETWEEN_KEYS, s.path("between").asText("base"), 0));
        mode.setSelectedIndex(index(MODE_KEYS, s.path("mode").asText("solo"), 0));
        String ex = s.path("helpersExit").asText("radiation");
        exit.setSelectedIndex(index(EXIT_KEYS, "exit".equalsIgnoreCase(ex) ? "radiation" : ex, 0));
        Form.set(restMinutes, s.path("restMinutes").asInt(60));
        Form.set(minGroup, s.path("minGroup").asInt(3));
        Form.set(gatherMinutes, s.path("gatherMinutes").asInt(10));
        waitForAll.setSelected(b(s, "waitForAll", true));
        waitAtRelay.setSelectedIndex(Math.max(0, Math.min(4, s.path("waitAtRelay").asInt(0))));
        Form.set(leaveVagrants, s.path("leaveVagrants").asInt(3));
        Form.set(chaseMax, s.path("chaseMax").asInt(1_500));
        followLeader.setSelected(b(s, "followLeaderTarget", true));
        Form.set(boxRadius, s.path("boxRadius").asInt(1_500));
        JsonNode l = s.path("lifeSaver");
        emp.setSelected(b(l, "emp", false));
        Form.set(empBelow, l.path("empBelow").asInt(45));
        ish.setSelected(b(l, "ish", false));
        Form.set(ishBelow, l.path("ishBelow").asInt(40));
        ability.setSelected(b(l, "ability", true));
        JsonNode m = s.path("mines");
        mines.setSelected(b(m, "on", false));
        Form.set(minesNpcs, m.path("minNpcs").asInt(4));
        mineItem.setSelectedIndex(index(MINES, m.path("item").asText(MINES[0]), 0));
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode s = c.moduleSettings(KEY);
        ArrayNode a = s.putArray("helpers");
        for (JCheckBox box : helperBoxes) {
            if (box.isSelected()) {
                a.add(box.getText());
            }
        }
        s.put("between", BETWEEN_KEYS[between.getSelectedIndex()]);
        s.put("mode", MODE_KEYS[mode.getSelectedIndex()]);
        s.put("helpersExit", EXIT_KEYS[exit.getSelectedIndex()]);
        s.put("restMinutes", Form.intOf(restMinutes));
        s.put("minGroup", Form.intOf(minGroup));
        s.put("gatherMinutes", Form.intOf(gatherMinutes));
        s.put("waitForAll", waitForAll.isSelected());
        s.put("waitAtRelay", waitAtRelay.getSelectedIndex());
        s.put("leaveVagrants", Form.intOf(leaveVagrants));
        s.put("chaseMax", Form.intOf(chaseMax));
        s.put("followLeaderTarget", followLeader.isSelected());
        s.put("boxRadius", Form.intOf(boxRadius));
        ObjectNode l = s.putObject("lifeSaver");
        l.put("emp", emp.isSelected());
        l.put("empBelow", Form.intOf(empBelow));
        l.put("ish", ish.isSelected());
        l.put("ishBelow", Form.intOf(ishBelow));
        l.put("ability", ability.isSelected());
        ObjectNode m = s.putObject("mines");
        m.put("on", mines.isSelected());
        m.put("minNpcs", Form.intOf(minesNpcs));
        m.put("item", MINES[mineItem.getSelectedIndex()]);
        return c.withModuleSettings(KEY, s);
    }
}
