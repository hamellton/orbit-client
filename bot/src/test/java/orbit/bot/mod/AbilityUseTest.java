package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import orbit.config.BotConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AbilityUseTest {

    @AfterEach
    void forget() {
        AbilityUse.forget();
    }

    @Test
    void theHoursAndTheDaysLimits() {
        // the user, 2026-11-13: limits for EMP-01 and ISH-01 in the config
        BotConfig.Spend s = new BotConfig.Spend(1, 5, 2, 3, 0);
        long t = 1_000_000_000_000L;
        assertNull(AbilityUse.limited(1, "emp", s, t));
        AbilityUse.used(1, "emp", t);
        AbilityUse.used(1, "emp", t + 60_000);
        assertNotNull(AbilityUse.limited(1, "emp", s, t + 120_000), "two in the hour");
        assertNull(AbilityUse.limited(1, "emp", s, t + AbilityUse.HOUR_MS + 1), "the next hour");
        AbilityUse.used(1, "emp", t + AbilityUse.HOUR_MS + 1);
        assertNotNull(AbilityUse.limited(1, "emp", s, t + 2 * AbilityUse.HOUR_MS), "three in the day");
        assertNull(AbilityUse.limited(1, "emp", s, t + AbilityUse.DAY_MS + 60_001), "a day later");
        assertNull(AbilityUse.limited(2, "emp", s, t + 120_000), "another pilot's count is his own");
        assertEquals(0, AbilityUse.today(2, "emp", t));
    }
}
