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
    private final JCheckBox portal = new JCheckBox("Перед сменой лететь к порталу");
    private final JCheckBox sellOnDie = new JCheckBox("Погиб с палладием — сразу на корабль продажи");
    private final JSpinner extraWait = Form.number(0, 300, 1);
    private final JComponent view;

    PalladiumSection() {
        way.addActionListener(e -> enableTwoShips());
        view = new Form()
                .section("Сбор")
                .note("Карта, ящик «ore_8» (палладий) и зоны — во вкладках «Общее», «Сбор», «Зоны».")
                .section("Продажа")
                .row("Как продавать", way)
                .note("Два корабля: трюм полон — выход из игры, на сайте активным становится корабль продажи (он стоит"
                        + " на 5-2 у торговца), продаёт палладий, затем снова корабль сбора. Один корабль: лететь на"
                        + " 5-2, продать, вернуться.")
                .note("Какой корабль собирает, а какой продаёт — в настройках аккаунта («Аккаунты» → «Изменить»):"
                        + " у каждого аккаунта свои ангары. Не выбраны — продаёт один корабль.")
                .row("", portal)
                .row("", sellOnDie)
                .row("Пауза после смены, с", extraWait)
                .build(true);
    }

    private void enableTwoShips() {
        boolean two = way.getSelectedIndex() == 0;
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
        boolean two = m.path("twoShips").asBoolean(true);
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
        if (m.has("twoShips") || !two) {
            m.put("twoShips", two); // DarkBot has no such key: two ships whenever there are two hangars
        }
        // collectHangar/sellHangar (DarkBot's) stay as imported: used until the account has its own pair
        m.put("goPortalChange", portal.isSelected());
        m.put("sellOnDie", sellOnDie.isSelected());
        m.put("aditionalWaitingTime", Form.intOf(extraWait));
        if (untouched && two && !portal.isSelected() && sellOnDie.isSelected() && Form.intOf(extraWait) == 5) {
            return c; // nothing set for a config of another module: do not add the section
        }
        return c.withModuleSettings(KEY, m);
    }
}
