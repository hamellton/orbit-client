package orbit.app.gui.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Font;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import orbit.config.BotConfig;
import orbit.config.Json;

/**
 * «Врата»: until the gate module (stage 5b) gets its own form, its settings — imported from DarkBot
 * (gate, kamikaze, builder, EBG) — are shown and edited as JSON.
 */
final class GateSection implements Section {

    private final JTextArea text = new JTextArea(24, 70);
    private final JComponent view;

    GateSection() {
        text.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        view = new Form()
                .section("Врата (GG)")
                .note("Пока модуль врат не готов, настройки показаны как есть (из импорта DarkBot): gateId — какие "
                        + "врата (71 Зета, 74 Каппа, −4 ЕБГ…), kamikaze — когда питомцу взрываться, builder — постройка "
                        + "врат и лимиты спинов. Когда модуль появится, здесь будет обычная форма.")
                .wide(new JScrollPane(text), true)
                .build(false);
    }

    @Override
    public String title() {
        return "Врата";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        JsonNode gate = c.modules().get(BotConfig.Module.GALAXY_GATE.key());
        try {
            text.setText(gate == null ? "" : Json.MAPPER.writeValueAsString(gate));
        } catch (JsonProcessingException e) {
            text.setText("");
        }
        text.setCaretPosition(0);
    }

    @Override
    public BotConfig apply(BotConfig c) {
        String t = text.getText().strip();
        if (t.isEmpty()) {
            if (!c.modules().containsKey(BotConfig.Module.GALAXY_GATE.key())) {
                return c;
            }
            java.util.Map<String, JsonNode> m = new java.util.TreeMap<>(c.modules());
            m.remove(BotConfig.Module.GALAXY_GATE.key());
            return new BotConfig(c.schema(), c.description(), c.module(), c.general(), c.npcs(), c.loot(), c.collect(),
                    c.pet(), c.safety(), c.zones(), c.safetySpots(), m, c.extra(), c.buy(), c.session(), c.flee());
        }
        try {
            JsonNode n = Json.MAPPER.readTree(t);
            if (!n.isObject()) {
                throw new IllegalArgumentException("Врата: нужен JSON-объект { … }");
            }
            return c.withModuleSettings(BotConfig.Module.GALAXY_GATE.key(), n);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Врата: ошибка в JSON, строка " + e.getLocation().getLineNr() + ": "
                    + e.getOriginalMessage());
        }
    }
}
