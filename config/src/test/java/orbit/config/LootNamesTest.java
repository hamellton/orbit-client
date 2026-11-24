package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LootNamesTest {

    @Test
    void namesWhatThePickerTakes() {
        assertEquals("палладий", LootNames.of("ore_8"));
        assertEquals("прометий", LootNames.of("ore_0"));
        assertEquals("бонус-бокс", LootNames.of("BONUS_BOX"));
        assertEquals("груз с корабля", LootNames.of("FROM_SHIP"));
        assertEquals("some new box", LootNames.of("SOME_NEW_BOX"));
    }

    @Test
    void namesTheEscortsBoxesByTheirNpc() {
        assertEquals("ящик Mimesis: Medic", LootNames.of("MIMESIS_AI_MEDIC_BOX"));
        assertEquals("ящик Mimesis: Mini Boss", LootNames.of("MIMESIS_AI_MINI_BOSS_BOX"));
        // counted under the raw name before: the day's totals carry it over
        assertEquals("ящик Mimesis: Mini Boss", LootNames.renamed("mimesis ai mini boss box"));
        assertEquals("груз с корабля", LootNames.renamed("груз с корабля"));
    }
}
