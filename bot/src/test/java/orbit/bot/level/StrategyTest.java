package orbit.bot.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Quests or farming (invented states). */
class StrategyTest {

    static AccountState account(long credits) {
        return new AccountState(4, credits, 0, 1, "ship_liberator", Set.of("ship_liberator"), Map.of("lasers", 4, "generators", 6),
                Map.of(1, Map.of("lasers", List.of("equipment_weapon_laser_lf-1")),
                        2, Map.of("lasers", List.of("equipment_weapon_laser_lf-1"))), Map.of(), 0);
    }

    static Strategy.Decision decide(long credits, String map, boolean idle, Strategy.Rates r) {
        LevelPlan plan = LevelPlan.bundled();
        AccountState s = account(credits);
        return Strategy.decide(s, plan, Advisor.advise(s, plan), 1, map, idle, r);
    }

    @Test
    void questsByDefault() {
        Strategy.Decision d = decide(0, "1-1", false, Strategy.Rates.UNKNOWN);
        assertEquals(Strategy.Mode.QUESTS, d.mode());
        assertEquals("Leveling MMO Quests", d.config());
        assertTrue(d.reason().contains("не измерен"), d.reason());
    }

    @Test
    void noQuestFarmsWhereTheQuestsLed() {
        assertEquals("Leveling MMO x-3", decide(0, "1-3", true, Strategy.Rates.UNKNOWN).config());
        assertEquals("Leveling MMO x-1", decide(0, "2-3", true, Strategy.Rates.UNKNOWN).config(), "another company's map: own x-1");
        assertEquals("Leveling MMO x-1", decide(0, "1-5", true, Strategy.Rates.UNKNOWN).config());
    }

    @Test
    void aSmallGapFarmingClosesFastIsFarmed() {
        // the scalpel II costs 300 000, reserve 30 000: 280 000 credits leave a gap of 50 000
        Strategy.Decision d = decide(280_000, "1-2", false, new Strategy.Rates(20_000, 80_000, 0, 0));
        assertEquals(Strategy.Mode.FARM, d.mode(), d.reason());
        assertEquals("Leveling MMO x-2", d.config());
        Strategy.Decision big = decide(10_000, "1-2", false, new Strategy.Rates(20_000, 80_000, 0, 0));
        assertEquals(Strategy.Mode.QUESTS, big.mode(), "a gap of 320 000 is four hours of farming: " + big.reason());
        Strategy.Decision slow = decide(280_000, "1-2", false, new Strategy.Rates(70_000, 80_000, 0, 0));
        assertEquals(Strategy.Mode.QUESTS, slow.mode(), "farming hardly better: " + slow.reason());
    }

    @Test
    void noFactionNoDecision() {
        LevelPlan plan = LevelPlan.bundled();
        AccountState s = account(0);
        assertEquals(null, Strategy.decide(s, plan, Advisor.advise(s, plan), 0, "1-1", true, Strategy.Rates.UNKNOWN).config());
    }
}
