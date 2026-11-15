package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JRadioButton;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «Обогащение» (the user, 2026-11-15/16: «бот сам присылал со Скайлаба и обогащал… в конфигурациях»; «упрости — не могу
 * разобраться»): what is enriched and with what, where the ore comes from, the transport — the rest under
 * «Дополнительно». Writes {@code modules.enrich} (the bot's {@code Enricher}, {@code ServiceVisit}) and
 * {@code modules.skylab} ({@code SkylabCare}); off by default.
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
    static final int[] DEFAULT_BELOW = {50_000, 1_000, 6_000, 6_000};
    static final int[] DEFAULT_LOAD = {10_000, 100, 1_000, 1_000};

    private final JCheckBox enrich = new JCheckBox("Обогащать");
    private final JCheckBox[] on = new JCheckBox[4];
    private final JComboBox<String>[] ore = newCombos();
    private final JSpinner[] below = new JSpinner[4];
    private final JSpinner[] load = new JSpinner[4];
    private final JLabel[] gives = new JLabel[4];

    private final JRadioButton fromHold = new JRadioButton("Только из трюма — ничего не привозить");
    private final JRadioButton fromSkylab = new JRadioButton("Привозить со Скайлаба в трюм текущего корабля");
    private final JRadioButton viaService = new JRadioButton("Пересаживаться на корабль для Скайлаба и привозить туда");
    private final JRadioButton normal = new JRadioButton("Обычный (бесплатно, ~10–15 мин)");
    private final JRadioButton fast = new JRadioButton("Быстрый (за уридий)");

    private final JCheckBox more = new JCheckBox("Показать дополнительные настройки");
    private final JSpinner keepInCargo = Form.number(0, 1_000_000, 100);
    private final JSpinner perHour = Form.number(1, 600, 1);
    private final JSpinner visitWait = Form.number(1, 240, 1);
    private final JSpinner visitEvery = Form.number(1, 72, 1);
    private final JSpinner every = Form.number(5, 1440, 5);
    private final JSpinner[] send = new JSpinner[4];
    private final JSpinner keepOnSkylab = Form.number(0, 10_000_000, 100);
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
                .section("1. Что обогащать")
                .row("", enrich)
                .note("Остаток — как в лаборатории игры: у лазеров выстрелы, у ракет штуки, у двигателей и щитов"
                        + " минуты (100 часов = 6 000 мин.). Когда остаток меньше заданного, бот кладёт руду из трюма;"
                        + " 1 руда = 10 выстрелов, штук или минут.");
        int w = 0;
        for (int i = 0; i < 4; i++) {
            on[i] = new JCheckBox(ITEM_LABELS[i]);
            w = Math.max(w, on[i].getPreferredSize().width);
        }
        for (int i = 0; i < 4; i++) {
            on[i].setPreferredSize(new java.awt.Dimension(w, on[i].getPreferredSize().height)); // the fields in a column
            below[i] = Form.number(0, 100_000_000, 100);
            load[i] = Form.number(1, 10_000_000, 100);
            gives[i] = new JLabel();
            int k = i;
            load[k].addChangeListener(e -> showGives(k));
            f.row("", Form.inline(on[i], ore[i], new JLabel("если меньше"), below[i], new JLabel(UNITS[i] + " — положить"),
                    load[i], new JLabel("руды"), gives[i]));
        }
        ButtonGroup src = new ButtonGroup();
        src.add(fromHold);
        src.add(fromSkylab);
        src.add(viaService);
        ButtonGroup tr = new ButtonGroup();
        tr.add(normal);
        tr.add(fast);
        f.section("2. Откуда брать руду, если в трюме её нет")
                .row("", fromHold)
                .row("", fromSkylab)
                .row("", viaService)
                .note("Корабль для Скайлаба выбирается в аккаунте («Аккаунты» → «Изменить» → «Скайлаб и обогащение»),"
                        + " например Цитадель. Бот пересаживается на него в спокойный момент, а у палладия — сразу после"
                        + " продажи, перед кораблём сбора; привозит руду, обогащает и пересаживается обратно.")
                .section("3. Транспорт со Скайлаба")
                .row("", normal)
                .row("", fast);
        Form adv = new Form()
                .row("Оставлять в трюме руды", keepInCargo)
                .row("Обогащений не чаще, раз в час", perHour)
                .row("Пересадка: ждать транспорт, мин", visitWait)
                .row("Пересадка: не чаще, раз в часов", visitEvery)
                .row("Скайлаб: проверять раз в, мин", every)
                .row("Оставлять на складе Скайлаба", keepOnSkylab)
                .row("Быстрый: если всё не влезает — ждать, пока свободно трюма, %", fastMinFree)
                .note("Привозить про запас по таймеру (кроме того, что нужно обогащению), только в трюм текущего"
                        + " корабля; 0 — не возить:");
        for (int i = 0; i < 4; i++) {
            send[i] = Form.number(0, 10_000_000, 100);
            adv.row(ORE_LABELS[i] + ", за раз", send[i]);
        }
        JComponent advanced = adv.build(false);
        advanced.setVisible(false);
        more.addActionListener(e -> {
            advanced.setVisible(more.isSelected());
            advanced.revalidate();
            if (advanced.getParent() != null) {
                advanced.getParent().revalidate();
                advanced.getParent().repaint();
            }
        });
        view = f.section("Дополнительно").row("", more).wide(advanced, false).build(true);
    }

    private void showGives(int i) {
        gives[i].setText("(+" + String.format("%,d", (long) Form.intOf(load[i]) * 10) + " " + UNITS[i] + ")");
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
        ObjectNode s = c.moduleSettings("skylab");
        enrich.setSelected(e.path("enabled").asBoolean(false));
        for (int i = 0; i < 4; i++) {
            var it = e.path("items").path(ITEMS[i]);
            on[i].setSelected(it.path("on").asBoolean(false));
            String o = it.path("ore").asText(DEFAULT_ORE[i]).toUpperCase(java.util.Locale.ROOT);
            int k = java.util.Arrays.asList(ORES).indexOf(o);
            ore[i].setSelectedIndex(k < 0 ? 0 : k);
            Form.set(below[i], it.path("below").asInt(DEFAULT_BELOW[i]));
            Form.set(load[i], it.path("load").asInt(DEFAULT_LOAD[i]));
            showGives(i);
        }
        if (e.path("visit").asBoolean(false)) {
            viaService.setSelected(true);
        } else if (s.path("enabled").asBoolean(false)) {
            fromSkylab.setSelected(true);
        } else {
            fromHold.setSelected(true);
        }
        boolean f = e.path("visit").asBoolean(false) ? e.path("visitFast").asBoolean(false) : s.path("fast").asBoolean(false);
        (f ? fast : normal).setSelected(true);
        Form.set(keepInCargo, e.path("keepInCargo").asInt(0));
        Form.set(perHour, e.path("maxPerHour").asInt(30));
        Form.set(visitWait, e.path("visitWaitMinutes").asInt(15));
        Form.set(visitEvery, e.path("visitEveryHours").asInt(3));
        Form.set(every, s.path("everyMinutes").asInt(20));
        for (int i = 0; i < 4; i++) {
            Form.set(send[i], s.path("ores").path(SKYLAB_ORES[i]).asInt(0));
        }
        Form.set(keepOnSkylab, s.path("keepOnSkylab").asInt(0));
        Form.set(fastMinFree, s.path("fastMinFreePercent").asInt(50));
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode e = c.moduleSettings("enrich");
        ObjectNode s = c.moduleSettings("skylab");
        boolean anySkylab = fromSkylab.isSelected() || viaService.isSelected();
        if (e.isEmpty() && s.isEmpty() && !enrich.isSelected() && !anySkylab) {
            return c; // an untouched config of another kind keeps no such sections
        }
        e.put("enabled", enrich.isSelected());
        ObjectNode items = e.putObject("items");
        for (int i = 0; i < 4; i++) {
            ObjectNode it = items.putObject(ITEMS[i]);
            it.put("on", on[i].isSelected());
            it.put("ore", ORES[ore[i].getSelectedIndex()]);
            it.put("below", Form.intOf(below[i]));
            it.put("load", Form.intOf(load[i]));
            // on the service ship: up to the threshold and one load more (the old «до» field is gone)
        }
        e.remove("visitBelow");
        e.remove("fillTo");
        e.put("keepInCargo", Form.intOf(keepInCargo));
        e.put("maxPerHour", Form.intOf(perHour));
        e.put("visit", viaService.isSelected());
        // the way chosen, kept for the header's «Скайлаб» switch (off and on again restores it)
        if (anySkylab) {
            e.put("source", viaService.isSelected() ? "service" : "skylab");
        }
        e.put("visitFast", fast.isSelected());
        e.put("visitWaitMinutes", Form.intOf(visitWait));
        e.put("visitEveryHours", Form.intOf(visitEvery));
        c = c.withModuleSettings("enrich", e);
        s.put("enabled", fromSkylab.isSelected());
        s.put("forEnrich", true);
        ObjectNode ores = s.putObject("ores");
        for (int i = 0; i < 4; i++) {
            ores.put(SKYLAB_ORES[i], Form.intOf(send[i]));
        }
        s.put("everyMinutes", Form.intOf(every));
        s.put("keepOnSkylab", Form.intOf(keepOnSkylab));
        s.put("fast", fast.isSelected());
        s.put("fastMinFreePercent", Form.intOf(fastMinFree));
        return c.withModuleSettings("skylab", s);
    }
}
