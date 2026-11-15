package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «Обогащение» (the user, 2026-11-15: «бот сам присылал со Скайлаба и сам обогащал лазеры, ракеты, движки и щиты —
 * настраивать в конфигурациях»): {@code modules.enrich} (the lab kept up from the hold, the bot's {@code Enricher}) and
 * {@code modules.skylab} (ore from the Skylab into the hold, the bot's {@code SkylabCare}). Both off by default.
 */
final class EnrichSection implements Section {

    static final String[] ITEMS = {"laser", "rockets", "drive", "shield"};
    static final String[] ITEM_LABELS = {"Лазеры", "Ракеты", "Двигатели", "Щиты"};
    static final String[] ORES = {"PROMERIUM", "SEPROM", "PROMETID", "DURANIUM"};
    static final String[] ORE_LABELS = {"Promerium", "Seprom", "Prometid", "Duranium"};
    /** The defaults: as the user's own ship was enriched (recording of 2026-11-14). */
    static final String[] DEFAULT_ORE = {"PROMERIUM", "SEPROM", "PROMERIUM", "SEPROM"};
    static final String[] SKYLAB_ORES = {"promerium", "seprom", "prometid", "duranium"};

    private final JCheckBox enrich = new JCheckBox("Обогащать из трюма");
    private final JCheckBox[] on = new JCheckBox[4];
    private final JComboBox<String>[] ore = newCombos();
    private final JSpinner[] below = new JSpinner[4];
    private final JSpinner[] load = new JSpinner[4];
    private final JSpinner keepInCargo = Form.number(0, 1_000_000, 100);
    private final JSpinner perHour = Form.number(1, 600, 1);

    private final JCheckBox skylab = new JCheckBox("Привозить руду со Скайлаба в трюм");
    private final JSpinner[] send = new JSpinner[4];
    private final JSpinner every = Form.number(5, 1440, 5);
    private final JCheckBox forEnrich = new JCheckBox("И сразу, когда обогащению не хватает руды в трюме");
    private final JSpinner keepOnSkylab = Form.number(0, 10_000_000, 100);
    private final JCheckBox fast = new JCheckBox("Быстрый транспорт (за уридий)");
    private final JComponent view;

    @SuppressWarnings("unchecked")
    private static JComboBox<String>[] newCombos() {
        JComboBox<String>[] c = new JComboBox[4];
        for (int i = 0; i < 4; i++) {
            c[i] = new JComboBox<>(ORE_LABELS);
        }
        return c;
    }

    EnrichSection() {
        Form f = new Form()
                .section("Обогащение")
                .row("", enrich)
                .note("Когда у предмета остаётся меньше порога, бот кладёт руду из трюма (как кнопка в лаборатории"
                        + " игры). Порог — в числах карточки «Обогащение» (1 руда = 10). Если предмет уже обогащён"
                        + " другой рудой, бот догружает ту же, чтобы не смешивать.");
        int w = 0;
        for (int i = 0; i < 4; i++) {
            on[i] = new JCheckBox(ITEM_LABELS[i]);
            w = Math.max(w, on[i].getPreferredSize().width);
        }
        for (int i = 0; i < 4; i++) {
            on[i].setPreferredSize(new java.awt.Dimension(w, on[i].getPreferredSize().height)); // the fields in a column
            below[i] = Form.number(0, 100_000_000, 1_000);
            load[i] = Form.number(1, 10_000_000, 100);
            f.row("", Form.inline(on[i], ore[i], new JLabel("если меньше"), below[i], new JLabel("класть по"), load[i]));
        }
        f.row("Оставлять в трюме руды", keepInCargo)
                .row("Не чаще, раз в час", perHour)
                .section("Скайлаб → трюм")
                .row("", skylab)
                .note("Транспортный модуль Скайлаба везёт руду в трюм, корабль никуда не летит. Пока транспорт в пути,"
                        + " новый не отправляется; не больше, чем влезет в трюм и лежит на складе.");
        for (int i = 0; i < 4; i++) {
            send[i] = Form.number(0, 10_000_000, 100);
            f.row(ORE_LABELS[i] + ", за раз", send[i]);
        }
        view = f.row("Проверять раз в, мин", every)
                .row("", forEnrich)
                .row("Оставлять на складе Скайлаба", keepOnSkylab)
                .row("", fast)
                .build(true);
    }

    @Override
    public String title() {
        return "Обогащение";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode e = c.moduleSettings("enrich");
        enrich.setSelected(e.path("enabled").asBoolean(false));
        for (int i = 0; i < 4; i++) {
            var it = e.path("items").path(ITEMS[i]);
            on[i].setSelected(it.path("on").asBoolean(false));
            String o = it.path("ore").asText(DEFAULT_ORE[i]).toUpperCase(java.util.Locale.ROOT);
            int k = java.util.Arrays.asList(ORES).indexOf(o);
            ore[i].setSelectedIndex(k < 0 ? 0 : k);
            Form.set(below[i], it.path("below").asInt(50_000));
            Form.set(load[i], it.path("load").asInt(10_000));
        }
        Form.set(keepInCargo, e.path("keepInCargo").asInt(0));
        Form.set(perHour, e.path("maxPerHour").asInt(30));
        ObjectNode s = c.moduleSettings("skylab");
        skylab.setSelected(s.path("enabled").asBoolean(false));
        for (int i = 0; i < 4; i++) {
            Form.set(send[i], s.path("ores").path(SKYLAB_ORES[i]).asInt(0));
        }
        Form.set(every, s.path("everyMinutes").asInt(20));
        forEnrich.setSelected(s.path("forEnrich").asBoolean(true));
        Form.set(keepOnSkylab, s.path("keepOnSkylab").asInt(0));
        fast.setSelected(s.path("fast").asBoolean(false));
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode e = c.moduleSettings("enrich");
        boolean enrichTouched = !e.isEmpty() || enrich.isSelected();
        if (enrichTouched) {
            e.put("enabled", enrich.isSelected());
            ObjectNode items = e.putObject("items");
            for (int i = 0; i < 4; i++) {
                ObjectNode it = items.putObject(ITEMS[i]);
                it.put("on", on[i].isSelected());
                it.put("ore", ORES[ore[i].getSelectedIndex()]);
                it.put("below", Form.intOf(below[i]));
                it.put("load", Form.intOf(load[i]));
            }
            e.put("keepInCargo", Form.intOf(keepInCargo));
            e.put("maxPerHour", Form.intOf(perHour));
            c = c.withModuleSettings("enrich", e);
        }
        ObjectNode s = c.moduleSettings("skylab");
        if (!s.isEmpty() || skylab.isSelected()) {
            s.put("enabled", skylab.isSelected());
            ObjectNode ores = s.putObject("ores");
            for (int i = 0; i < 4; i++) {
                ores.put(SKYLAB_ORES[i], Form.intOf(send[i]));
            }
            s.put("everyMinutes", Form.intOf(every));
            s.put("forEnrich", forEnrich.isSelected());
            s.put("keepOnSkylab", Form.intOf(keepOnSkylab));
            s.put("fast", fast.isSelected());
            c = c.withModuleSettings("skylab", s);
        }
        return c; // an untouched config of another kind keeps no such sections
    }
}
