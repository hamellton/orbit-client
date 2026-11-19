package orbit.app.gui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «Мимесис» (docs/MIMESIS.md): the escort as DarkBot's DonorPlugin plays it, AstraBot's automation round it — what
 * between the runs, when to fly to the entry, after the freighter, EMAA-20 and its craft, the abilities, the pet's HP
 * Link. Writes {@code modules.mimesis}.
 */
final class MimesisSection implements Section {

    static final String KEY = "mimesis";
    static final String[] BETWEEN = {"Фармить ключи на рабочей карте (как AstraBot)", "Ждать на x-1 у входа",
        "Выходить из игры до следующего фрейтера"};
    static final String[] BETWEEN_KEYS = {"farm", "wait", "logout"};

    private final JComboBox<String> between = new JComboBox<>(BETWEEN);
    private final JSpinner startTravel = Form.number(0, 900, 30);
    private final JSpinner minKeys = Form.number(1, 100, 1);
    private final JSpinner stayHp = Form.number(0, 100, 5);
    private final JCheckBox ignoreNpc = new JCheckBox("Не бить NPC после фрейтера");
    private final JCheckBox ignoreBoxes = new JCheckBox("Не собирать ящики после фрейтера");
    private final JCheckBox emaa = new JCheckBox("EMAA-20 по Мимесисам (×7 урона), пока есть");
    static final String[] EMAA_MODES = {"только по тяжёлым (как AstraBot)", "по всем Мимесисам"};
    private final JComboBox<String> emaaMode = new JComboBox<>(EMAA_MODES);
    private final JSpinner emaaMin = Form.number(0, 1_000_000, 500);
    private final JCheckBox craft = new JCheckBox("Крафтить EMAA-20 из Byte Remnant (10 000 за 1 500 Byte и 15 000 уридия)");
    private final JSpinner craftBelow = Form.number(1, 1_000_000, 1_000);
    private final JCheckBox emp = new JCheckBox("EMP-01, когда HP ниже, %");
    private final JSpinner empBelow = Form.number(0, 100, 5);
    private final JCheckBox ish = new JCheckBox("ISH-01, когда HP ниже, %");
    private final JSpinner ishBelow = Form.number(0, 100, 5);
    private final JCheckBox leech = new JCheckBox("Энергетическая пиявка, когда HP ниже, %");
    private final JSpinner leechBelow = Form.number(0, 100, 5);
    private final JCheckBox solace = new JCheckBox("Лечение Solace, когда HP ниже, %");
    private final JSpinner solaceBelow = Form.number(0, 100, 5);
    private final JCheckBox ability = new JCheckBox("Боевая способность (Solaris+ и др.), когда NPC рядом не меньше");
    private final JSpinner abilityNpc = Form.number(1, 30, 1);
    private final JCheckBox hpLink = new JCheckBox("Питомец в HP Link, когда HP корабля ниже, %");
    private final JSpinner hpLinkHero = Form.number(0, 100, 5);
    private final JSpinner hpLinkPet = Form.number(0, 100, 5);
    private final JComponent view;

    MimesisSection() {
        view = new Form()
                .section("Мимесис — эскорт EM Freighter")
                .note("Работает с модулем «Мимесис» (выбирается в шапке). Рейс — как DarkBot (DonorPlugin): кружит вокруг"
                        + " фрейтера на 350–900, бьёт NPC не дальше 2 000 от него (сначала тех, кто бьёт фрейтер),"
                        + " ящики — не дальше 2 500; когда фрейтер довезён и вокруг пусто — выход порталом."
                        + " Каждый час вход открыт 5 минут, нужен Escort Key. Время берётся от сервера."
                        + " Воскрешение ставьте «на месте»: с базы на карту эскорта обратно не пустят.")
                .row("Между рейсами", between)
                .row("Вылетать ко входу на x-1 за, с", startTravel)
                .row("Идти, когда ключей не меньше", minKeys)
                .section("После фрейтера")
                .row("Уйти сразу, если HP ниже, %", stayHp)
                .row("", ignoreNpc)
                .row("", ignoreBoxes)
                .section("Патроны")
                .row("", Form.inline(emaa, new JLabel("не меньше"), emaaMin))
                .row("По кому", emaaMode)
                .note("Тяжёлые: Terror, Reflector, Cloning, Medic, Inspirit, Hardy, Obscured, Mirror, Marker, Piercing,"
                        + " Hounding, Sniper; остальным — патроны конфига (вкладка «Атака»). Нет EMAA-20 или меньше порога —"
                        + " бьёт патронами конфига.")
                .row("", Form.inline(craft, new JLabel("держать не меньше"), craftBelow))
                .section("Способности в рейсе (как AstraBot)")
                .row("", Form.inline(emp, empBelow))
                .row("", Form.inline(ish, ishBelow))
                .row("", Form.inline(leech, leechBelow))
                .row("", Form.inline(solace, solaceBelow))
                .row("", Form.inline(ability, abilityNpc))
                .row("", Form.inline(hpLink, hpLinkHero, new JLabel("HP питомца не ниже, %"), hpLinkPet))
                .note("Лимиты расхода EMP-01 и ISH-01 — на вкладке «Атака». Способность — только когда игра показывает"
                        + " её готовой.")
                .build(true);
    }

    @Override
    public Group group() {
        return Group.MODULE;
    }

    @Override
    public java.util.Set<BotConfig.Module> modules() {
        return java.util.Set.of(BotConfig.Module.MIMESIS);
    }

    @Override
    public String title() {
        return "Мимесис";
    }

    @Override
    public JComponent view() {
        return view;
    }

    private static boolean b(JsonNode n, String k, boolean d) {
        return n.path(k).isBoolean() ? n.path(k).asBoolean() : d;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode s = c.moduleSettings(KEY);
        String bt = s.path("between").asText("farm");
        between.setSelectedIndex("wait".equals(bt) ? 1 : "logout".equals(bt) ? 2 : 0);
        Form.set(startTravel, s.path("startTravelSeconds").asInt(180));
        Form.set(minKeys, s.path("minKeys").asInt(1));
        JsonNode a = s.path("after");
        Form.set(stayHp, a.path("stayHp").asInt(80));
        ignoreNpc.setSelected(b(a, "ignoreNpc", false));
        ignoreBoxes.setSelected(b(a, "ignoreBoxes", false));
        emaa.setSelected(b(s.path("emaa"), "on", true));
        Form.set(emaaMin, s.path("emaa").path("min").asInt(1_000));
        emaaMode.setSelectedIndex("all".equalsIgnoreCase(s.path("emaa").path("mode").asText("heavy")) ? 1 : 0);
        craft.setSelected(b(s.path("craftEmaa"), "on", false));
        Form.set(craftBelow, s.path("craftEmaa").path("below").asInt(10_000));
        emp.setSelected(b(s.path("emp"), "on", true));
        Form.set(empBelow, s.path("emp").path("below").asInt(50));
        ish.setSelected(b(s.path("ish"), "on", true));
        Form.set(ishBelow, s.path("ish").path("below").asInt(45));
        leech.setSelected(b(s.path("leech"), "on", true));
        Form.set(leechBelow, s.path("leech").path("below").asInt(30));
        solace.setSelected(b(s.path("solace"), "on", true));
        Form.set(solaceBelow, s.path("solace").path("below").asInt(60));
        ability.setSelected(b(s.path("ability"), "on", true));
        Form.set(abilityNpc, s.path("ability").path("minNpc").asInt(5));
        hpLink.setSelected(b(s.path("hpLink"), "on", true));
        Form.set(hpLinkHero, s.path("hpLink").path("heroBelow").asInt(70));
        Form.set(hpLinkPet, s.path("hpLink").path("petMin").asInt(70));
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (c.module() != BotConfig.Module.MIMESIS && !c.modules().containsKey(KEY)) {
            return c; // a config of another module keeps no Mimesis section
        }
        ObjectNode s = c.moduleSettings(KEY);
        s.put("between", BETWEEN_KEYS[Math.max(0, between.getSelectedIndex())]);
        s.put("startTravelSeconds", Form.intOf(startTravel));
        s.put("minKeys", Form.intOf(minKeys));
        ObjectNode a = s.putObject("after");
        a.put("stayHp", Form.intOf(stayHp));
        a.put("ignoreNpc", ignoreNpc.isSelected());
        a.put("ignoreBoxes", ignoreBoxes.isSelected());
        ObjectNode e = s.putObject("emaa");
        e.put("on", emaa.isSelected());
        e.put("min", Form.intOf(emaaMin));
        e.put("mode", emaaMode.getSelectedIndex() == 1 ? "all" : "heavy");
        ObjectNode cr = s.putObject("craftEmaa");
        cr.put("on", craft.isSelected());
        cr.put("below", Form.intOf(craftBelow));
        below(s, "emp", emp, empBelow);
        below(s, "ish", ish, ishBelow);
        below(s, "leech", leech, leechBelow);
        below(s, "solace", solace, solaceBelow);
        ObjectNode ab = s.putObject("ability");
        ab.put("on", ability.isSelected());
        ab.put("minNpc", Form.intOf(abilityNpc));
        ObjectNode l = s.putObject("hpLink");
        l.put("on", hpLink.isSelected());
        l.put("heroBelow", Form.intOf(hpLinkHero));
        l.put("petMin", Form.intOf(hpLinkPet));
        return c.withModuleSettings(KEY, s);
    }

    private static void below(ObjectNode s, String key, JCheckBox on, JSpinner v) {
        ObjectNode n = s.putObject(key);
        n.put("on", on.isSelected());
        n.put("below", Form.intOf(v));
    }
}
