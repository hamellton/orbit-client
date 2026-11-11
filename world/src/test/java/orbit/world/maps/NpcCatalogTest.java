package orbit.world.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NpcCatalogTest {

    @Test
    void theLocatorsNumbersOn3_6HaveTheirNames() {
        // possibleTargetValues on 3-6 (2026-11-11): 161, 13, 12, 10, 9, 22; 13 led the locator to the Cubikon
        NpcCatalog c = NpcCatalog.bundled();
        assertEquals("Cubikon", c.name(13));
        assertEquals("Protegit", c.name(12));
        assertEquals("Kristallon", c.name(10));
        assertEquals("Kristallin", c.name(9));
        assertEquals("Boss Kristallin", c.name(22));
        assertEquals("Plagued Kristallin", c.name(161));
        assertNull(c.name(99_999));
        assertTrue(c.all().size() > 200, "every NPC of the game: " + c.all().size());
        assertEquals("Cubikon (13)", new NpcCatalog.Npc(13, "Cubikon").label());
    }
}
