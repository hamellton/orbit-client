package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import javax.swing.JComponent;
import orbit.config.BotConfig;
import orbit.config.Json;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-14: «давай сделаем юзер френдли, как в даркботе в модуле ГГ». */
class GateSectionTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    static BotConfig gateConfig(String json) throws Exception {
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.GALAXY_GATE);
        return c.withModuleSettings("gate", Json.MAPPER.readTree(json));
    }

    @Test
    void theFormReadsAndWritesTheModulesSettings() throws Exception {
        GateSection s = new GateSection();
        BotConfig c = gateConfig("""
                {"gates":[{"gate":"beta","on":true,"minLives":2},"alpha"],"build":true,"extraEnergyOnly":false,
                 "maxSpinCost":80,"minUridium":500000,"spins100":0.2,"spins10":0.5,"useMultiAt":3,"buyLives":true,
                 "keepLives":4,"trinityAttribute":2,"trinityDifficulty":5,"someFutureKey":7}""");
        s.load(c);
        assertEquals(c, s.apply(c), "untouched: the same");
        s.load(c);
        // a change: the form writes all it shows, unknown keys kept
        javax.swing.JCheckBox place = field(s, "place");
        place.setSelected(false);
        JsonNode m = s.apply(c).modules().get("gate");
        assertFalse(m.path("place").asBoolean(true));
        assertEquals("beta", m.path("gates").get(0).path("gate").asText());
        assertEquals(2, m.path("gates").get(0).path("minLives").asInt());
        assertEquals("alpha", m.path("gates").get(1).path("gate").asText());
        assertTrue(m.path("build").asBoolean());
        assertFalse(m.path("extraEnergyOnly").asBoolean());
        assertEquals(80, m.path("maxSpinCost").asInt());
        assertEquals(0.2, m.path("spins100").asDouble(), 1e-9);
        assertEquals(3, m.path("useMultiAt").asInt());
        assertEquals(4, m.path("keepLives").asInt());
        assertEquals(2, m.path("trinityAttribute").asInt());
        assertEquals(5, m.path("trinityDifficulty").asInt());
        assertEquals(7, m.path("someFutureKey").asInt());
    }

    @Test
    void anotherModulesConfigGetsNoGateSection() {
        GateSection s = new GateSection();
        BotConfig c = BotConfig.empty();
        s.load(c);
        assertEquals(c, s.apply(c));
    }

    @Test
    void picture() throws Exception {
        GateSection s = new GateSection();
        s.load(gateConfig("{\"gates\":[\"alpha\",\"beta\",\"gamma\"],\"build\":true,\"buyLives\":true}"));
        JComponent v = s.view();
        v.setSize(620, 1100);
        layout(v);
        BufferedImage img = new BufferedImage(620, 1100, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        v.printAll(g);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new java.io.File("build/gate-section.png"));
    }

    private static void layout(java.awt.Component c) {
        c.doLayout();
        if (c instanceof java.awt.Container k) {
            for (java.awt.Component ch : k.getComponents()) {
                if (ch.getWidth() == 0) {
                    ch.setSize(ch.getPreferredSize());
                }
                layout(ch);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object o, String name) throws Exception {
        java.lang.reflect.Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return (T) f.get(o);
    }
}
