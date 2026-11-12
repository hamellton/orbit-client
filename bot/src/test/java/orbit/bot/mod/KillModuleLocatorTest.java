package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class KillModuleLocatorTest {

    @Test
    void theListsNameGivesTheGamesNpcNumber() {
        // the pet's locator on Boss Kristallon (the user, 2026-11-13)
        assertEquals(23, KillModule.npcNumber("..::{ Boss Kristallon }::.."));
        assertEquals(10, KillModule.npcNumber("-=[ Kristallon ]=-"));
        assertEquals(0, KillModule.npcNumber("-=[ Nobody ]=-"));
    }
}
