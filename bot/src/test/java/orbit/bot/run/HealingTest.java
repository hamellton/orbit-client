package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    static void hp(Sim sim, double hp) {
        orbit.protocol.gen.AttributeHitpointUpdateCommand u = new orbit.protocol.gen.AttributeHitpointUpdateCommand();
        u.hitpointsNow = (int) hp;
        u.hitpointsMax = 100_000;
        sim.world.apply(u, sim.now);
    }

    static long clicks(Sim sim, String id) {
        return sim.sent.stream().filter(p -> p instanceof MenuActionRequest m && id.equals(m.menuItemId)).count();
    }

    @Test
    void theRepairRobotIsNotClickedAgainWhileItRepairs() {
        BotConfig c = BotConfig.empty().withSafety(new BotConfig.Safety(0.2, 0.9, 0.05, 0, null,
                new BotConfig.Heal(0.6, -1, -1), 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 1, 5_000, 5_000);
        abilities(sim, Safety.REPAIR_BOT);
        hp(sim, 50_000);
        Safety s = new Safety(sim.ctx);
        double hp = 50_000;
        // out of combat: one click, then a pause while the robot gets going (HP does not move yet)
        for (int t = 0; t < 150; t++) {
            s.tick(false);
            sim.now += 100 * Sim.MS;
        }
        assertEquals(1, clicks(sim, Safety.REPAIR_BOT), "one click, the robot needs a moment");
        // the robot works: HP climbs, no more clicks
        for (int t = 0; t < 300; t++) {
            if (t % 10 == 0) {
                hp += 300;
                hp(sim, hp);
            }
            s.tick(false);
            sim.now += 100 * Sim.MS;
        }
        assertEquals(1, clicks(sim, Safety.REPAIR_BOT), "HP rising: the robot is at work");
        // it stopped short of the threshold: click again
        for (int t = 0; t < 100; t++) {
            s.tick(false);
            sim.now += 100 * Sim.MS;
        }
        assertEquals(2, clicks(sim, Safety.REPAIR_BOT));
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

    @Test
    void aRepairTargetBelowTheStartIsNoTrip() {
        // the palladium profile: go repair below 15 % with no NPC, repair up to 0 % — DarkBot never leaves then
        BotConfig c = BotConfig.empty().withSafety(new BotConfig.Safety(0, 0, 0.15, 0, null,
                new BotConfig.Heal(0.6, -1, -1), 10, "PORTAL", 0, 0));
        Sim sim = new Sim(c, 1, 5_000, 5_000);
        hp(sim, 10_000);
        Safety s = new Safety(sim.ctx);
        for (int t = 0; t < 50; t++) {
            assertTrue(!s.tick(false), "the module keeps working");
            sim.now += 100 * Sim.MS;
        }
        assertEquals(0, sim.ctx.stats().repairs.get());
        assertTrue(!s.repairing());
    }
}
