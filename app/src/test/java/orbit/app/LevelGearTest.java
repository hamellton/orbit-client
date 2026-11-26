package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

/** The leveling configs' formation and pet by what the account owns. */
class LevelGearTest {

    @Test
    void xEverywhereTheMothForAttackOnceOwnedAndThePetPassive() {
        BotConfig c = BotConfig.empty();
        BotConfig x = LevelGear.gear(c, true, false, false);
        assertEquals(LevelGear.X, x.general().offensive().formation());
        assertEquals(LevelGear.X, x.general().roam().formation());
        assertEquals(LevelGear.X, x.general().run().formation());
        assertEquals(c.general().run().config(), x.general().run().config(), "the ship config is kept");

        BotConfig both = LevelGear.gear(c, true, true, false);
        assertEquals(LevelGear.MOTH, both.general().offensive().formation(), "the Moth attacks");
        assertEquals(LevelGear.X, both.general().run().formation(), "X runs");

        BotConfig pet = LevelGear.gear(c, false, false, true);
        assertTrue(pet.pet().enabled());
        assertEquals("PASSIVE", pet.pet().gear());
        assertTrue(pet.pet().buyFuel());
        assertEquals(c.general(), pet.general(), "no formation owned: the config's own");
    }
}
