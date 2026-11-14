package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.config.BotConfig;
import orbit.protocol.gen.AssemblyBuildRequest;
import orbit.protocol.gen.AssemblyCollectRequest;
import orbit.protocol.gen.AssemblyRecipeAvailableStatusModule;
import orbit.protocol.gen.AssemblyRecipeBuildStatusModule;
import orbit.protocol.gen.AssemblyRecipeContentModule;
import orbit.protocol.gen.AssemblyRecipeModule;
import orbit.protocol.gen.AssemblyRecipeReadyToCollectStatusModule;
import orbit.protocol.gen.AssemblyRecipeStatusModule;
import orbit.protocol.gen.InventoryItemModule;
import orbit.protocol.gen.InventoryItemUpdatedCommand;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-14: «крафт буквально всего, чтобы можно было конфигурировать это в конфигах». */
class CraftWatchTest {

    static final String RSB = "recipe_blueprint-rsb75-blacklight";

    static void recipe(Sim sim, AssemblyRecipeStatusModule status) {
        AssemblyRecipeModule r = new AssemblyRecipeModule();
        r.recipeLootId = RSB;
        r.status = status;
        AssemblyRecipeContentModule c = new AssemblyRecipeContentModule();
        c.recipes = new java.util.ArrayList<>(List.of(r));
        sim.world.apply(c, sim.now);
    }

    static void have(Sim sim, double amount) {
        InventoryItemModule i = new InventoryItemModule();
        i.lootId = "ammunition_laser_rsb-75";
        i.amount = amount;
        InventoryItemUpdatedCommand u = new InventoryItemUpdatedCommand();
        u.item = i;
        sim.world.apply(u, sim.now);
    }

    static Sim sim(int below) {
        BotConfig.Buy buy = BotConfig.Buy.OFF.withCrafts(List.of(
                new BotConfig.CraftRule(RSB, "ammunition_laser_rsb-75", below, 2)));
        return new Sim(BotConfig.empty().withBuy(buy), 26, 7_500, 3_900);
    }

    static void runFor(Sim sim, CraftWatch w, long ms) {
        for (long t = 0; t < ms; t += 100) {
            w.tick();
            sim.now += 100 * Sim.MS;
        }
    }

    static long sent(Sim sim, Class<?> type) {
        return sim.sent.stream().filter(type::isInstance).count();
    }

    @Test
    void aLowItemStartsTheRecipeAndTheReadyBatchIsCollected() {
        Sim sim = sim(1_000);
        have(sim, 500);
        recipe(sim, new AssemblyRecipeAvailableStatusModule());
        CraftWatch w = new CraftWatch(sim.ctx);
        runFor(sim, w, 30_000);
        assertEquals(1, sent(sim, AssemblyBuildRequest.class), "500 of 1 000: one batch started");
        AssemblyBuildRequest b = (AssemblyBuildRequest) sim.sent.stream()
                .filter(AssemblyBuildRequest.class::isInstance).findFirst().orElseThrow();
        assertEquals(2.0, b.amount);
        assertEquals(RSB, b.recipeLootId);
        AssemblyRecipeBuildStatusModule building = new AssemblyRecipeBuildStatusModule();
        building.buildAmount = 2;
        building.remainingTimeMillis = 5_000;
        recipe(sim, building);
        runFor(sim, w, 10_000);
        assertEquals(0, sent(sim, AssemblyCollectRequest.class), "building: nothing to collect");
        AssemblyRecipeReadyToCollectStatusModule ready = new AssemblyRecipeReadyToCollectStatusModule();
        ready.buildAmount = 2;
        recipe(sim, ready);
        runFor(sim, w, 10_000);
        assertEquals(1, sent(sim, AssemblyCollectRequest.class), "ready: collected");
    }

    @Test
    void enoughOfTheItemStartsNothing() {
        Sim sim = sim(1_000);
        have(sim, 5_000);
        recipe(sim, new AssemblyRecipeAvailableStatusModule());
        CraftWatch w = new CraftWatch(sim.ctx);
        runFor(sim, w, 30_000);
        assertEquals(0, sent(sim, AssemblyBuildRequest.class));
    }

    @Test
    void anUnknownCountIsNoZero() {
        Sim sim = sim(1_000);
        recipe(sim, new AssemblyRecipeAvailableStatusModule());
        CraftWatch w = new CraftWatch(sim.ctx);
        runFor(sim, w, 30_000);
        assertTrue(sim.sent.stream().noneMatch(AssemblyBuildRequest.class::isInstance),
                "the game has not told how many: nothing crafted");
    }
}
