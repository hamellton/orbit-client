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
    /** The game's units of each item's enrichment (its lab window): shots, rockets, minutes, minutes. */
    static final String[] UNITS = {"выстр.", "шт.", "мин.", "мин."};
    /** Defaults in those units: a laser shoots far more than the others last. */
    static final int[] DEFAULT_BELOW = {50_000, 1_000, 1_000, 1_000};
    static final int[] DEFAULT_LOAD = {10_000, 100, 100, 100};
    static final int[] DEFAULT_FILL = {200_000, 5_000, 5_000, 5_000};

    private final JCheckBox enrich = new JCheckBox("Обогащать из трюма");
    private final JCheckBox[] on = new JCheckBox[4];
    private final JComboBox<String>[] ore = newCombos();
    private final JSpinner[] below = new JSpinner[4];
    private final JSpinner[] load = new JSpinner[4];
    private final JSpinner[] fillTo = new JSpinner[4];
    private final JSpinner keepInCargo = Form.number(0, 1_000_000, 100);
    private final JSpinner perHour = Form.number(1, 600, 1);

    private final JCheckBox visit = new JCheckBox("Пересаживаться на корабль для Скайлаба и обогащения");
    private final JCheckBox visitFast = new JCheckBox("Быстрый транспорт при пересадке (за уридий)");
    private final JSpinner visitWait = Form.number(1, 240, 1);
    private final JSpinner visitEvery = Form.number(1, 72, 1);

    private final JCheckBox skylab = new JCheckBox("Привозить руду со Скайлаба в трюм");
    private final JSpinner[] send = new JSpinner[4];
    private final JSpinner every = Form.number(5, 1440, 5);
    private final JCheckBox forEnrich = new JCheckBox("И сразу, когда обогащению не хватает руды в трюме");
    private final JSpinner keepOnSkylab = Form.number(0, 10_000_000, 100);
    private final JCheckBox fast = new JCheckBox("Быстрый транспорт (за уридий)");
    private final JSpinner fastMinFree = Form.number(0, 100, 5);
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
                .note("Остаток — как в лаборатории игры: у лазеров выстрелы, у ракет штуки, у двигателей и щитов"
                        + " минуты. Когда остаток меньше порога, бот кладёт руду из трюма (как перетаскивание в"
                        + " лаборатории); 1 руда = 10 выстрелов, штук или минут. Если предмет уже обогащён другой рудой,"
                        + " бот догружает ту же, чтобы не смешивать.");
        int w = 0;
        for (int i = 0; i < 4; i++) {
            on[i] = new JCheckBox(ITEM_LABELS[i]);
            w = Math.max(w, on[i].getPreferredSize().width);
        }
        for (int i = 0; i < 4; i++) {
            on[i].setPreferredSize(new java.awt.Dimension(w, on[i].getPreferredSize().height)); // the fields in a column
            below[i] = Form.number(0, 100_000_000, 100);
            load[i] = Form.number(1, 10_000_000, 10);
            fillTo[i] = Form.number(1, 1_000_000_000, 100);
            f.row("", Form.inline(on[i], ore[i], new JLabel("меньше"), below[i], new JLabel(UNITS[i] + " · по"), load[i],
                    new JLabel("руды")));
        }
        f.row("Оставлять в трюме руды", keepInCargo)
                .row("Не чаще, раз в час", perHour)
                .section("Пересадка на корабль для Скайлаба")
                .row("", visit)
                .note("Корабль выбирается в настройках аккаунта («Аккаунты» → «Изменить» → «Скайлаб и обогащение»),"
                        + " например Цитадель с большим трюмом. Когда у отмеченного предмета остаток меньше его порога"
                        + " выше, а руды в трюме нет, бот в спокойный момент модуля (не во вратах, не в бою) пересаживается"
                        + " на него, привозит руду со Скайлаба, обогащает каждый отмеченный предмет до его уровня ниже и"
                        + " пересаживается обратно.");
        for (int i = 0; i < 4; i++) {
            f.row(ITEM_LABELS[i] + ": обогащать до", Form.inline(fillTo[i], new JLabel(UNITS[i])));
        }
        f
                .row("", visitFast)
                .row("Ждать транспорт, мин", visitWait)
                .row("Не чаще, раз в часов", visitEvery)
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
                .row("Быстрый: если всё не влезает — везти, когда свободно трюма, %", fastMinFree)
                .note("Если нужное не влезает в трюм целиком, платный транспорт ждёт, пока свободно хотя бы столько"
                        + " процентов трюма, — чтобы не платить за мелкие поездки. Что влезает целиком — везёт сразу.")
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
            Form.set(below[i], it.path("below").asInt(DEFAULT_BELOW[i]));
            Form.set(load[i], it.path("load").asInt(DEFAULT_LOAD[i]));
            Form.set(fillTo[i], it.path("fillTo").asInt(e.path("fillTo").asInt(DEFAULT_FILL[i])));
        }
        Form.set(keepInCargo, e.path("keepInCargo").asInt(0));
        Form.set(perHour, e.path("maxPerHour").asInt(30));
        visit.setSelected(e.path("visit").asBoolean(false));
        visitFast.setSelected(e.path("visitFast").asBoolean(false));
        Form.set(visitWait, e.path("visitWaitMinutes").asInt(15));
        Form.set(visitEvery, e.path("visitEveryHours").asInt(3));
        ObjectNode s = c.moduleSettings("skylab");
        skylab.setSelected(s.path("enabled").asBoolean(false));
        for (int i = 0; i < 4; i++) {
            Form.set(send[i], s.path("ores").path(SKYLAB_ORES[i]).asInt(0));
        }
        Form.set(every, s.path("everyMinutes").asInt(20));
        forEnrich.setSelected(s.path("forEnrich").asBoolean(true));
        Form.set(keepOnSkylab, s.path("keepOnSkylab").asInt(0));
        fast.setSelected(s.path("fast").asBoolean(false));
        Form.set(fastMinFree, s.path("fastMinFreePercent").asInt(50));
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
                it.put("fillTo", Form.intOf(fillTo[i]));
            }
            e.put("keepInCargo", Form.intOf(keepInCargo));
            e.put("maxPerHour", Form.intOf(perHour));
            e.put("visit", visit.isSelected());
            e.remove("visitBelow"); // the items' own thresholds now
            e.remove("fillTo");
            e.put("visitFast", visitFast.isSelected());
            e.put("visitWaitMinutes", Form.intOf(visitWait));
            e.put("visitEveryHours", Form.intOf(visitEvery));
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
            s.put("fastMinFreePercent", Form.intOf(fastMinFree));
            c = c.withModuleSettings("skylab", s);
        }
        return c; // an untouched config of another kind keeps no such sections
    }
}
