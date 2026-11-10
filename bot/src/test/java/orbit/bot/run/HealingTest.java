package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.config.BotConfig;
import orbit.protocol.gen.AttackHitCommand;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.MenuCategoryInitializationCommand;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.MenuItemInitializationCommand;
import orbit.protocol.gen.MenuItemStatusCommand;
import org.junit.jupiter.api.Test;

class HealingTest {

    static void abilities(Sim sim, String... ids) {
        MenuInitializationCommand menu = new MenuInitializationCommand();
        MenuCategoryInitializationCommand cat = new MenuCategoryInitializationCommand();
        cat.categoryId = "ship_abilities";
        cat.menuItems = java.util.Arrays.stream(ids).map(id -> {
            MenuItemInitializationCommand it = new MenuItemInitializationCommand();
            it.status = new MenuItemStatusCommand();
            it.status.menuItemId = id;
            it.status.available = true;
            it.status.activatable = true;
            return it;
        }).toList();
        menu.categories = List.of(cat);
        sim.world.apply(menu, sim.now);
    }

    static void hurt(Sim sim, double hp, int shield) {
        AttackHitCommand hit = new AttackHitCommand();
        hit.attackerId = 99;
        hit.victimId = 1;
        hit.victimHitpoints = hp;
        hit.victimShield = shield;
        sim.world.apply(hit, sim.now);
    }

    static boolean used(Sim sim, String id) {
        return sim.sent.stream().anyMatch(p -> p instanceof MenuActionRequest m && id.equals(m.menuItemId));
    }

    @Test
    void aegisHealsHpShieldAndPodInCombat() {
        BotConfig c = BotConfig.empty().withSafety(new BotConfig.Safety(0.2, 0.9, 0.05, 0, null,
                new BotConfig.Heal(0.6, 0.5, 0.5), 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 1, 5_000, 5_000);
        abilities(sim, "ability_aegis_hp-repair", "ability_aegis_shield-repair", "ability_aegis_repair-pod");
        hurt(sim, 40_000, 10_000); // HP 40 %, shield 20 %, attacked
        Safety s = new Safety(sim.ctx);
        for (int t = 0; t < 120; t++) {
            s.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(used(sim, "ability_aegis_hp-repair"), "HP repair");
        assertTrue(used(sim, "ability_aegis_shield-repair"), "shield repair");
        assertTrue(used(sim, "ability_aegis_repair-pod"), "repair pod");
    }

    @Test
    void solaceHealsAndNothingAboveTheThreshold() {
        BotConfig c = BotConfig.empty().withSafety(new BotConfig.Safety(0.2, 0.9, 0.05, 0, null,
                new BotConfig.Heal(0.6, -1, -1), 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 1, 5_000, 5_000);
        abilities(sim, "ability_solace");
        hurt(sim, 70_000, 50_000);
        Safety s = new Safety(sim.ctx);
        for (int t = 0; t < 50; t++) {
            s.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(!used(sim, "ability_solace"), "HP 70 % is above 60 %");
        hurt(sim, 50_000, 50_000);
        for (int t = 0; t < 50; t++) {
            s.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(used(sim, "ability_solace"));
    }
}
