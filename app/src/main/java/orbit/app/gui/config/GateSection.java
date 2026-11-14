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
                .note("Настройки модуля врат (JSON), подробно — docs/GALAXY-GATES.md:")
                .note("gates — порядок: [\"alpha\", \"beta\", \"gamma\", \"zeta\", \"kappa\", \"trinity\"] или"
                        + " {\"gate\": \"alpha\", \"on\": true, \"minLives\": 2}; place — ставить собранные (да);")
                .note("build — спинить (нет); extraEnergyOnly — только бесплатная энергия (да); иначе за уридий, если цена"
                        + " спина ≤ maxSpinCost (100) и уридия ≥ minUridium (1 000 000);")
                .note("spinAmount 1/5/10/100 (как AstraBot) или spins100/spins10/spins5 — доля собранных частей, ниже"
                        + " которой спинить по 100/10/5 (как DarkBot); spinDelay мс (500); useMultiAt — множитель с xN (0 — нет);")
                .note("buyLives — докупать жизни (нет), keepLives — держать не меньше (3), lifeMinUridium — не тратить ниже (0),"
                        + " maxLivesToBuy — на врата (0 — без лимита);")
                .note("boxRadius — ящики во вратах (1 500); trinityAttribute 1 Hellfire / 2 Genome, trinityDifficulty 1–5.")
                .wide(minHeight(new JScrollPane(text), 140), true)
                .build(true);
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
                    c.pet(), c.safety(), c.zones(), c.safetySpots(), m, c.extra(), c.buy(), c.session(), c.flee(), c.group());
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

    private static JScrollPane minHeight(JScrollPane s, int h) {
        s.setMinimumSize(new java.awt.Dimension(200, h));
        return s;
    }
}
