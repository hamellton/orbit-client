package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxCatalogTest {

    @Test
    void theUsersListOfBoxes() {
        // the user's list, 2026-11-12
        assertEquals(750, BoxCatalog.waitMs("ore_8"), "palladium");
        assertEquals(5_500, BoxCatalog.waitMs("PIRATE_BOOTY_GOLD"));
        assertEquals(0, BoxCatalog.waitMs("BONUS_BOX"));
        assertFalse(BoxCatalog.collectable("mine_3"), "a mine shows among the resources, it is no box");
        assertFalse(BoxCatalog.collectable("firework"));
        assertTrue(BoxCatalog.collectable("SOLAR_CLASH"));
        assertTrue(BoxCatalog.collectable("SOMETHING_NEW"), "unknown types as the config says");
        assertEquals("ящик с инвоков и бегемота", LootNames.of("SOLAR_CLASH"));
        assertEquals("бонус-бокс (яйцо)", LootNames.of("ALIEN_EGG"));
        assertEquals("бонус-бокс", LootNames.of("BONUS_BOX"), "the names the log had stay");
    }
}
