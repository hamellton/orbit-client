package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.config.BotConfig;
import orbit.config.RecipeCosts;
import orbit.protocol.gen.InventoryItemModule;
import orbit.protocol.gen.InventoryItemUpdatedCommand;
import org.junit.jupiter.api.Test;

class RecipeCostsTest {

    static void resource(Sim sim, String id, double amount) {
        InventoryItemUpdatedCommand c = new InventoryItemUpdatedCommand();
        c.item = new InventoryItemModule();
        c.item.lootId = id;
        c.item.amount = amount;
        sim.world.apply(c, sim.now);
    }

    @Test
    void theEternalCpusPriceFromTheClient() {
        var parts = RecipeCosts.of("recipe_blueprint-eternal-cpu");
        assertEquals(3, parts.size());
        assertTrue(parts.contains(new RecipeCosts.Part("resource_collectable_eternal-fragment", 500)));
        assertTrue(parts.contains(new RecipeCosts.Part("currency_credits", 150_000)));
    }

    @Test
    void whatIsLackingIsToldAndEnoughIsNull() {
        Sim sim = new Sim(BotConfig.empty(), 26, 10_000, 6_000);
        resource(sim, "resource_collectable_eternal-fragment", 120);
        resource(sim, "resource_collectable_rinusk", 900);
        String lack = CraftWatch.lacking(sim.world.snapshot(sim.now), "recipe_blueprint-eternal-cpu", 1);
        assertTrue(lack != null && lack.contains("120 из 500"), lack);
        resource(sim, "resource_collectable_eternal-fragment", 700);
        String credits = CraftWatch.lacking(sim.world.snapshot(sim.now), "recipe_blueprint-eternal-cpu", 1);
        assertTrue(credits == null || credits.contains("150 000"), "only the credits, if the sim has none: " + credits);
        assertNull(CraftWatch.lacking(sim.world.snapshot(sim.now), "recipe_no-such", 1));
    }
}
