package orbit.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class ConfigCheckTest {

    static final MapCatalog MAPS = MapCatalog.bundled();

    static boolean says(List<ConfigCheck.Problem> p, String text) {
        return p.stream().anyMatch(x -> x.message().contains(text));
    }

    @Test
    void killWithoutTargetsIsAnError() {
        BotConfig c = BotConfig.empty();
        List<ConfigCheck.Problem> p = ConfigCheck.check(c, MAPS);
        assertTrue(ConfigCheck.hasErrors(p));
        assertTrue(says(p, "ни одного NPC"));

        BotConfig ok = c.withNpcs(Map.of("-=[ Streuner ]=-", new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(1), null)));
        assertFalse(ConfigCheck.hasErrors(ConfigCheck.check(ok, MAPS)));
        BotConfig elsewhere = c.withNpcs(Map.of("-=[ Streuner ]=-", new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(2), null)));
        assertTrue(ConfigCheck.hasErrors(ConfigCheck.check(elsewhere, MAPS)));
    }

    @Test
    void cubikonOffTheX6MapsIsAWarning() {
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.CUBIKON);
        List<ConfigCheck.Problem> p = ConfigCheck.check(c, MAPS);
        assertTrue(says(p, "кубы появляются"));
        assertFalse(ConfigCheck.hasErrors(p));
        BotConfig on36 = c.withGeneral(new BotConfig.General(26, null, null, null, true, false, 300, false, false));
        assertFalse(says(ConfigCheck.check(on36, MAPS), "кубы появляются"));
    }

    @Test
    void repairThresholdsMustBeOrdered() {
        BotConfig c = BotConfig.empty().withSafety(new BotConfig.Safety(0.9, 0.5, 0.5, 0, null, null, 10, null, 10, 10));
        assertTrue(says(ConfigCheck.check(c, MAPS), "порог ухода на ремонт"));
    }

    @Test
    void unknownMapIsAnError() {
        BotConfig c = BotConfig.empty().withGeneral(new BotConfig.General(99_999, null, null, null, true, false, 300, false, false));
        assertTrue(says(ConfigCheck.check(c, MAPS), "неизвестна"));
    }

    @Test
    void behemothTrainSettingsOffTheTestedOnesAreSaid() {
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.BEHEMOTH);
        var m = Json.MAPPER.createObjectNode().put("trainMode", true);
        assertFalse(says(ConfigCheck.check(c.withModuleSettings("behemoth", m), MAPS), "бегемот среди поезда"),
                "defaults are the tested ones");
        m.put("trainEnemyLimit", 0).put("leaveSkipped", false);
        List<ConfigCheck.Problem> p = ConfigCheck.check(c.withModuleSettings("behemoth", m), MAPS);
        assertTrue(says(p, "от толпы не уходит"));
        assertTrue(says(p, "пропущенная карта"));
    }
}
