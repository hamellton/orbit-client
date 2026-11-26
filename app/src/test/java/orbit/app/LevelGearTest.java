package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

/** The leveling configs' P.E.T. by what the account owns. */
class LevelGearTest {

    @Test
    void anOwnedPetIsOutPassiveWithItsFuelBought() {
        BotConfig c = BotConfig.empty();
        BotConfig pet = LevelGear.gear(c, true);
        assertTrue(pet.pet().enabled());
        assertEquals("PASSIVE", pet.pet().gear());
        assertTrue(pet.pet().buyFuel());
        assertEquals(c.general(), pet.general(), "the formation is the config's own");
        assertSame(c, LevelGear.gear(c, false), "no pet: the config as it is");
    }
}
