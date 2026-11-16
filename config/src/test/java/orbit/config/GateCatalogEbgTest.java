package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GateCatalogEbgTest {

    @Test
    void ebgsNpcsAndMaps() {
        GateCatalog.Gate g = GateCatalog.byMap(462);
        assertEquals("eternal", g.key());
        assertEquals(g, GateCatalog.byBuilderId(-4), "DarkBot's gateId −4");
        assertEquals("uberkristallon", GateCatalog.gateNpc("( UberKristallon ) EBG47"));
        assertEquals(47, GateCatalog.gateWave("( UberKristallon ) EBG47"));
        assertEquals("barrage seeker rocket", GateCatalog.gateNpc("<=< Barrage Seeker Rocket >=> EBG51"));
        assertEquals(645, GateCatalog.defaultRule(g, "( UberKristallon ) EBG47").radius(), 0.1);
        assertEquals(-90, GateCatalog.defaultRule(g, "<=< Barrage Seeker Rocket >=> EBG51").priority());
        assertFalse(GateCatalog.defaultRule(g, "\\\\ Abide I // EBG51").kill(), "the guard is never shot");
        assertTrue(GateCatalog.defaultRule(g, "-=[ Mordon ]=- EBG1").kill());
        assertEquals(560, GateCatalog.defaultRule(g, "-=[ Mordon ]=- EBG1").radius(), 0.1);
        assertEquals(37, GateCatalog.gateWave("-=[ Protegit ]=- α37"), "ABG as before");
    }
}
