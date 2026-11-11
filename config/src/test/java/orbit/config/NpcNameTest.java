package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NpcNameTest {

    @Test
    void aRuleUnderTheBareNameMatchesTheGamesDecoratedOne() {
        BotConfig.NpcRule bare = new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null);
        BotConfig.NpcRule exact = new BotConfig.NpcRule(560, -100, true, Set.of(), Set.of(), null);
        BotConfig c = BotConfig.empty().withNpcs(Map.of("Protegit", bare, "-=[ Cubikon ]=-", exact,
                "..::{ Boss Kristallin }::..", exact));
        assertEquals(bare, c.npc("-=[ Protegit ]=-"), "added from the game's table, seen in the game");
        assertEquals(exact, c.npc("-=[ Cubikon ]=-"));
        assertEquals("boss kristallin", BotConfig.bare("..::{ Boss Kristallin }::.."));
        assertNull(c.npc("-=[ Kristallin ]=-"), "«Kristallin» is not «Boss Kristallin»");
    }
}
