package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BoxCatalogTest {

    @Test
    void theUsersListOfBoxes() {
        // the list of 2026-11-12
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

    @Test
    void darkBotNamesABoxByTheFirstTypeOfItsPicture() {
        // the client's collectables XML: one picture for several server types, DarkBot keeps the first
        assertEquals("SOLAR_CLASH", BoxCatalog.darkBotName("MINDFIRE_BEHEMOTH_BOX_2"));
        assertEquals("SOLAR_CLASH", BoxCatalog.darkBotName("INVOKE_XVI_BOX_1"));
        assertEquals("TREASURE", BoxCatalog.darkBotName("MIMESIS_AI_MEDIC_BOX"));
        assertEquals("FROM_SHIP", BoxCatalog.darkBotName("FROM_SPACEBALL"));
        assertEquals(null, BoxCatalog.darkBotName("PIRATE_BOOTY"), "a picture of its own: the same name");
    }

    @Test
    void dmPluginsKeysAndTheirChests() {
        assertEquals("PIRATE_BOOTY_GOLD", BoxCatalog.bootyKey("GREEN").box(), "green keys open the yellow chest");
        assertEquals("BK", BoxCatalog.bootyKey("GREEN").code());
        assertEquals("BLUE", BoxCatalog.keyFor("PIRATE_BOOTY_BLUE").name());
        assertEquals("APOCALYPSE", BoxCatalog.keyFor("MASQUE_BOOTY_BOX").name());
        assertEquals(null, BoxCatalog.keyFor("BONUS_BOX"));
        assertEquals(15, BoxCatalog.bootyKeys().size());
        assertEquals(5_000, BoxCatalog.waitMs("EMPYRIAN_BOOTY_BOX"));
    }

    @Test
    void aWatchedKeyDecidesItsChest() {
        BotConfig.Collect k = new BotConfig.Collect(java.util.Map.of("PIRATE_BOOTY_GOLD",
                new BotConfig.BoxRule(false, 5_500, 0)), 400, false, true, BotConfig.Collect.CARGO_NONE, 0, null,
                java.util.List.of("GREEN"));
        assertEquals(Boolean.TRUE, k.byKeys("PIRATE_BOOTY_GOLD", java.util.Map.of("BK", 3)), "keys: on, the row aside");
        assertEquals(Boolean.FALSE, k.byKeys("PIRATE_BOOTY_GOLD", java.util.Map.of("BK", 0)), "none left: off");
        assertEquals(Boolean.FALSE, k.byKeys("PIRATE_BOOTY_GOLD", java.util.Map.of()), "no count yet: off");
        assertEquals(null, k.byKeys("PIRATE_BOOTY_BLUE", java.util.Map.of("BKB", 5)), "a key not watched: the row");
        assertEquals(k.bootyKeys(), k.withBoxes(java.util.Map.of()).bootyKeys(), "a module's own list keeps the keys");
    }

    @Test
    void aReserveAndASessionLimitKeepKeys() {
        BotConfig.Collect k = new BotConfig.Collect(java.util.Map.of(), 400, false, true, BotConfig.Collect.CARGO_NONE, 0,
                null, java.util.List.of("GREEN"), java.util.Map.of("GREEN", new BotConfig.KeyLimit(75, 10)));
        assertEquals(Boolean.FALSE, k.byKeys("PIRATE_BOOTY_GOLD", java.util.Map.of("BK", 75), null), "the reserve is kept");
        assertEquals(Boolean.TRUE, k.byKeys("PIRATE_BOOTY_GOLD", java.util.Map.of("BK", 76), null));
        assertEquals(Boolean.FALSE, k.byKeys("PIRATE_BOOTY_GOLD", java.util.Map.of("BK", 200), java.util.Map.of("BK", 10)),
                "ten spent this session: the limit");
        assertEquals(k.keyLimits(), k.withBoxes(java.util.Map.of()).keyLimits());
    }
}
