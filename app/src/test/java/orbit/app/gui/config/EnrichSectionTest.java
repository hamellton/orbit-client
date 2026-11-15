package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.config.BotConfig;
import orbit.config.Json;
import org.junit.jupiter.api.Test;

class EnrichSectionTest {

    @Test
    void anUntouchedConfigKeepsNoSuchSections() {
        EnrichSection s = new EnrichSection();
        BotConfig c = BotConfig.empty();
        s.load(c);
        assertSame(c, s.apply(c));
    }

    @Test
    void settingsRoundTrip() throws Exception {
        BotConfig c = BotConfig.empty()
                .withModuleSettings("enrich", Json.MAPPER.readTree("""
                        {"enabled":true,"items":{"laser":{"on":true,"ore":"SEPROM","below":40000,"load":5000}},
                         "keepInCargo":200,"maxPerHour":12}"""))
                .withModuleSettings("skylab", Json.MAPPER.readTree("""
                        {"enabled":true,"ores":{"promerium":1000},"everyMinutes":30,"forEnrich":true,"fast":false}"""));
        EnrichSection s = new EnrichSection();
        s.load(c);
        BotConfig out = s.apply(c);
        var e = out.moduleSettings("enrich");
        assertTrue(e.path("enabled").asBoolean());
        assertEquals("SEPROM", e.path("items").path("laser").path("ore").asText());
        assertEquals(40000, e.path("items").path("laser").path("below").asInt());
        assertEquals("SEPROM", e.path("items").path("rockets").path("ore").asText(), "the default of the rockets");
        assertEquals(12, e.path("maxPerHour").asInt());
        var k = out.moduleSettings("skylab");
        assertEquals(1000, k.path("ores").path("promerium").asInt());
        assertEquals(30, k.path("everyMinutes").asInt());
        assertEquals(true, k.path("enabled").asBoolean(), "«привозить в трюм текущего корабля»");
        assertEquals(false, out.moduleSettings("enrich").path("visit").asBoolean(true));
    }
}
