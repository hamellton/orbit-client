package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import orbit.world.Quests;
import org.junit.jupiter.api.Test;

/** An ore a quest names as loot (the starter quest 20003: «resource_ore_prometium»). */
class QuestLootOreTest {

    static Quests.Condition loot(String id) {
        return new Quests.Condition(1, Quests.COLLECT_LOOT, List.of(id), 10, 0, true, false, true, List.of());
    }

    @Test
    void oresByName() {
        assertEquals(0, QuestModule.lootOre(loot("resource_ore_prometium")));
        assertEquals(2, QuestModule.lootOre(loot("RESOURCE_ORE_TERBIUM")));
        assertEquals(6, QuestModule.lootOre(loot("resource_ore_promerium")));
        assertEquals(-1, QuestModule.lootOre(loot("resource_collectable_prismatium")), "a resource from NPCs, not an ore");
        assertEquals(-1, QuestModule.lootOre(new Quests.Condition(1, Quests.COLLECT_LOOT, List.of(), 1, 0, true, false, true,
                List.of())));
    }
}
