package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.bot.run.DispatchCare;
import orbit.config.BotConfig;

/**
 * «Диспетчер» (the dispatch's retrievers, as AstraBot and PikaPika's DispatchNew): on or off, which retriever, how many of
 * it at once, slots left free, the instant finish. Works with any module. Writes {@code modules.dispatch}.
 */
final class DispatchSection implements Section {

    static final String KEY = DispatchCare.KEY;

    private final JCheckBox enabled = new JCheckBox("Отправлять сборщиков сам (как AstraBot)");
    private final JComboBox<String> retriever = new JComboBox<>(
            DispatchCare.KINDS.stream().map(DispatchCare.Kind::label).toArray(String[]::new));
    private final JSpinner count = Form.number(1, 5, 1);
    private final JSpinner freeSlots = Form.number(0, 5, 1);
    private final JCheckBox instant = new JCheckBox("Завершать мгновенно за Quick Coupon");
    private final JComponent view;

    DispatchSection() {
        view = new Form()
                .section("Диспетчер")
                .row("", enabled)
                .note("Работает при любом модуле, без окна игры: раз в минуту смотрит слоты, забирает готовых, в свободный"
                        + " слот отправляет выбранного. Во вратах и на эскорте Мимесиса не трогает. Цена проверяется"
                        + " заранее; отказ сервера — пауза 30 мин.")
                .row("Сборщик", retriever)
                .row("Таких в работе не больше", count)
                .row("Оставлять пустых слотов", freeSlots)
                .row("", instant)
                .build(true);
    }

    @Override
    public String title() {
        return "Диспетчер";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode s = c.moduleSettings(KEY);
        enabled.setSelected(s.path("enabled").asBoolean(false));
        DispatchCare.Kind k = DispatchCare.byName(s.path("retriever").asText("R-01"));
        retriever.setSelectedIndex(Math.max(0, DispatchCare.KINDS.indexOf(k != null ? k : DispatchCare.byName("R-01"))));
        Form.set(count, s.path("count").asInt(5));
        Form.set(freeSlots, s.path("freeSlots").asInt(0));
        instant.setSelected(s.path("instant").asBoolean(false));
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (!enabled.isSelected() && !c.modules().containsKey(KEY)) {
            return c; // never set: the config keeps no section
        }
        ObjectNode s = c.moduleSettings(KEY);
        s.put("enabled", enabled.isSelected());
        s.put("retriever", DispatchCare.KINDS.get(Math.max(0, retriever.getSelectedIndex())).name());
        s.put("count", Form.intOf(count));
        s.put("freeSlots", Form.intOf(freeSlots));
        s.put("instant", instant.isSelected());
        return c.withModuleSettings(KEY, s);
    }
}
