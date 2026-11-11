package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «Палладий» (PALLADIUM): how the full hold of palladium is sold — with one ship (fly to 5-2's trader and back) or
 * with two (the collect ship logs out, the sell ship parked at 5-2 sells, then the collect ship again), as the
 * user's DarkBot does it (DmPlugin «Palladium Hangar» settings: {@code collectHangar}, {@code sellHangar},
 * {@code goPortalChange}, {@code sellOnDie}, {@code aditionalWaitingTime}).
 */
final class PalladiumSection implements Section {

    private static final String KEY = BotConfig.Module.PALLADIUM.key();

    private final JComboBox<String> way = new JComboBox<>(new String[] {"Два корабля (смена ангара)", "Один корабль"});
    private final JSpinner collectHangar = Form.number(0, Integer.MAX_VALUE, 1);
    private final JSpinner sellHangar = Form.number(0, Integer.MAX_VALUE, 1);
    private final JCheckBox portal = new JCheckBox("Перед сменой лететь к порталу");
    private final JCheckBox sellOnDie = new JCheckBox("Погиб с палладием — сразу на корабль продажи");
    private final JSpinner extraWait = Form.number(0, 300, 1);
    private final JComponent view;

    PalladiumSection() {
        hideGrouping(collectHangar);
        hideGrouping(sellHangar);
        way.addActionListener(e -> enableTwoShips());
        view = new Form()
                .section("Сбор")
                .note("Карта, ящик «ore_8» (палладий) и зоны — во вкладках «Общее», «Сбор», «Зоны».")
                .section("Продажа")
                .row("Как продавать", way)
                .note("Два корабля: трюм полон — выход из игры, на сайте активным становится корабль продажи (он стоит"
                        + " на 5-2 у торговца), продаёт палладий, затем снова корабль сбора. Один корабль: лететь на"
                        + " 5-2, продать, вернуться.")
                .row("Ангар сбора", collectHangar)
                .row("Ангар продажи", sellHangar)
                .note("Номера ангаров бот пишет в журнал при входе: «Ангары: …; сейчас …».")
                .row("", portal)
                .row("", sellOnDie)
                .row("Пауза после смены, с", extraWait)
                .build(true);
    }

    private static void hideGrouping(JSpinner s) {
        if (s.getEditor() instanceof JSpinner.NumberEditor e) {
            e.getFormat().setGroupingUsed(false);
            e.getTextField().setColumns(9);
            e.getTextField().setValue(s.getValue());
        }
    }

    private void enableTwoShips() {
        boolean two = way.getSelectedIndex() == 0;
        collectHangar.setEnabled(two);
        sellHangar.setEnabled(two);
        portal.setEnabled(two);
        sellOnDie.setEnabled(two);
        extraWait.setEnabled(two);
    }

    @Override
    public String title() {
        return "Палладий";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        int collect = m.path("collectHangar").asInt(0);
        int sell = m.path("sellHangar").asInt(0);
        Form.set(collectHangar, collect);
        Form.set(sellHangar, sell);
        boolean two = m.path("twoShips").asBoolean(collect > 0 && sell > 0 && collect != sell);
        way.setSelectedIndex(two ? 0 : 1);
        portal.setSelected(m.path("goPortalChange").asBoolean(false));
        sellOnDie.setSelected(m.path("sellOnDie").asBoolean(true));
        Form.set(extraWait, m.path("aditionalWaitingTime").asInt(5));
        enableTwoShips();
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        boolean untouched = c.module() != BotConfig.Module.PALLADIUM && m.isEmpty();
        boolean two = way.getSelectedIndex() == 0;
        int collect = Form.intOf(collectHangar);
        int sell = Form.intOf(sellHangar);
        if (c.module() == BotConfig.Module.PALLADIUM && two && (collect <= 0 || sell <= 0 || collect == sell)) {
            throw new IllegalArgumentException("«Палладий»: для двух кораблей нужны два разных ангара — сбора и продажи");
        }
        if (m.has("twoShips") || two != (collect > 0 && sell > 0 && collect != sell)) {
            m.put("twoShips", two); // only when it differs from what the two hangars say (DarkBot has no such key)
        }
        m.put("collectHangar", collect);
        m.put("sellHangar", sell);
        m.put("goPortalChange", portal.isSelected());
        m.put("sellOnDie", sellOnDie.isSelected());
        m.put("aditionalWaitingTime", Form.intOf(extraWait));
        if (untouched && !two && collect == 0 && sell == 0) {
            return c; // nothing set for a config of another module: do not add the section
        }
        return c.withModuleSettings(KEY, m);
    }
}
