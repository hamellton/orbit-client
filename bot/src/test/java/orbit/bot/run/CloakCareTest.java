package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.protocol.gen.AssemblyBuildRequest;
import orbit.protocol.gen.AssemblyCollectRequest;
import orbit.protocol.gen.AssemblyRecipeAvailableStatusModule;
import orbit.protocol.gen.AssemblyRecipeContentModule;
import orbit.protocol.gen.AssemblyRecipeModule;
import orbit.protocol.gen.AssemblyRecipeReadyToCollectStatusModule;
import orbit.protocol.gen.AssemblyRecipeStatusModule;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

/**
 * The user, 2026-11-14: «крафт невидов и установка их на акк, если закончились — когда есть свободное окно: полёт на свою
 * базу на x-8, установка нового процессора и продолжение — как в даркботе в CARNAGE».
 */
class CloakCareTest {

    /** The site as the hangar view lists it, and the moves asked of it. */
    static final class Site implements HangarAccess {
        final List<Cpu> cpus = new ArrayList<>();
        final List<String> moves = new ArrayList<>();

        @Override
        public synchronized List<Cpu> cpus(String loot) {
            return List.copyOf(cpus);
        }

        @Override
        public synchronized void move(String id, int config, boolean toShip) {
            moves.add((toShip ? "in " : "out ") + id + "@" + config);
            for (int i = 0; i < cpus.size(); i++) {
                Cpu c = cpus.get(i);
                if (c.id().equals(id)) {
                    Set<Integer> configs = new java.util.TreeSet<>(c.configs());
                    if (toShip) {
                        configs.add(config);
                    } else {
                        configs.remove(config);
                    }
                    cpus.set(i, new Cpu(id, configs.isEmpty(), c.charges(), configs));
                }
            }
        }
    }

    /** On the company's 3-8 by its HQ, the item bar listing the cloak CPU with {@code charges}. */
    static Sim atHome(int charges) {
        int map = MapCatalog.bundled().byName("3-8").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        orbit.protocol.gen.AssetCreateCommand hq = new orbit.protocol.gen.AssetCreateCommand();
        hq.assetId = 900;
        hq.type = new orbit.protocol.gen.AssetTypeModule();
        hq.type.typeValue = 46;
        hq.factionId = 3;
        hq.posX = 10_100;
        hq.posY = 6_000;
        hq.userName = "HQ";
        sim.world.apply(hq, sim.now);
        cloak(sim, charges);
        return sim;
    }

    static void cloak(Sim sim, int charges) {
        orbit.protocol.gen.MenuInitializationCommand menu = new orbit.protocol.gen.MenuInitializationCommand();
        orbit.protocol.gen.MenuCategoryInitializationCommand cat = new orbit.protocol.gen.MenuCategoryInitializationCommand();
        cat.categoryId = "cpus";
        orbit.protocol.gen.MenuItemInitializationCommand it = new orbit.protocol.gen.MenuItemInitializationCommand();
        it.status = new orbit.protocol.gen.MenuItemStatusCommand();
        it.status.menuItemId = CloakCare.CLOAK;
        it.status.counterValue = charges;
        it.status.available = charges > 0;
        cat.menuItems = List.of(it);
        menu.categories = List.of(cat);
        sim.world.apply(menu, sim.now);
    }

    static void recipe(Sim sim, AssemblyRecipeStatusModule status) {
        AssemblyRecipeModule r = new AssemblyRecipeModule();
        r.recipeLootId = CloakCare.RECIPE;
        r.status = status;
        AssemblyRecipeContentModule c = new AssemblyRecipeContentModule();
        c.recipes = new ArrayList<>(List.of(r));
        sim.world.apply(c, sim.now);
    }

    /** Ticks for {@code ms} of game time, letting the site's thread answer; the last tick's result. */
    static boolean run(Sim sim, CloakCare care, boolean free, CloakPlan plan, long ms) throws InterruptedException {
        boolean busy = false;
        for (long t = 0; t < ms; t += 100) {
            busy = care.tick(false, free, plan);
            sim.now += 100 * Sim.MS;
            Thread.sleep(1);
        }
        return busy;
    }

    static final CloakPlan CRAFT = new CloakPlan(true, 1);

    static {
        CloakCare.SITE_GAP_MIN_MS = 0;
        CloakCare.SITE_GAP_MAX_MS = 0;
    }

    @Test
    void theEmptyCpuGivesWayToTheFullestSpareInBothConfigs() throws Exception {
        Sim sim = atHome(0);
        Site site = new Site();
        site.cpus.add(new HangarAccess.Cpu("old", false, 0, Set.of(1, 2)));
        site.cpus.add(new HangarAccess.Cpu("few", true, 3, Set.of()));
        site.cpus.add(new HangarAccess.Cpu("full", true, 50, Set.of()));
        CloakCare care = new CloakCare(sim.ctx);
        care.setHangar(site);
        sim.now += (CloakCare.SETTLE_MS + 1_000) * Sim.MS;
        run(sim, care, true, CRAFT, 75_000);
        assertEquals(List.of("out old@1", "in full@1", "out old@2", "in full@2"), site.moves);
        assertTrue(care.active(), "waits for the game to count the new CPU");
        cloak(sim, 50);
        assertFalse(run(sim, care, true, CRAFT, 500), "back to work");
        assertFalse(care.active());
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof AssemblyBuildRequest), "a spare: nothing crafted");
    }

    @Test
    void noSpareIsCraftedCollectedAndFitted() throws Exception {
        Sim sim = atHome(0);
        Site site = new Site();
        site.cpus.add(new HangarAccess.Cpu("old", false, 0, Set.of(1)));
        recipe(sim, new AssemblyRecipeAvailableStatusModule());
        CloakCare care = new CloakCare(sim.ctx);
        care.setHangar(site);
        sim.now += (CloakCare.SETTLE_MS + 1_000) * Sim.MS;
        run(sim, care, true, CRAFT, 75_000);
        AssemblyBuildRequest b = (AssemblyBuildRequest) sim.sent.stream().filter(p -> p instanceof AssemblyBuildRequest)
                .findFirst().orElseThrow(() -> new AssertionError("no build"));
        assertEquals(CloakCare.RECIPE, b.recipeLootId);
        assertEquals(1, b.amount);
        AssemblyRecipeReadyToCollectStatusModule ready = new AssemblyRecipeReadyToCollectStatusModule();
        ready.buildAmount = 1;
        recipe(sim, ready);
        run(sim, care, true, CRAFT, 1_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof AssemblyCollectRequest c
                && CloakCare.RECIPE.equals(c.recipeLootId)), "the batch collected");
        recipe(sim, new AssemblyRecipeAvailableStatusModule());
        synchronized (site) {
            site.cpus.add(new HangarAccess.Cpu("new", true, 50, Set.of()));
        }
        run(sim, care, true, CRAFT, 6_000);
        assertEquals(List.of("out old@1", "in new@1", "in new@2"), site.moves, "both configs");
    }

    @Test
    void noFreeWindowNoTrip() throws Exception {
        Sim sim = atHome(0);
        Site site = new Site();
        site.cpus.add(new HangarAccess.Cpu("full", true, 50, Set.of()));
        CloakCare care = new CloakCare(sim.ctx);
        care.setHangar(site);
        sim.now += (CloakCare.SETTLE_MS + 1_000) * Sim.MS;
        assertFalse(run(sim, care, false, CRAFT, 20_000));
        assertFalse(care.active());
        assertTrue(site.moves.isEmpty());
    }

    @Test
    void chargesLeftNoTrip() throws Exception {
        Sim sim = atHome(12);
        Site site = new Site();
        CloakCare care = new CloakCare(sim.ctx);
        care.setHangar(site);
        sim.now += (CloakCare.SETTLE_MS + 1_000) * Sim.MS;
        assertFalse(run(sim, care, true, CRAFT, 5_000));
        assertFalse(care.active());
    }
    @Test
    void aBuildingByThePortalIsNotTheStationTheShipFliesToTheHq() throws Exception {
        // live 2026-11-14 on 2-8: «at the base» by the portal, 9 000 off the HQ — the site refused the fitting
        int map = MapCatalog.bundled().byName("3-8").id();
        Sim sim = new Sim(BotConfig.empty(), map, 10_000, 6_000);
        orbit.protocol.gen.AssetCreateCommand dock = new orbit.protocol.gen.AssetCreateCommand();
        dock.assetId = 901;
        dock.type = new orbit.protocol.gen.AssetTypeModule();
        dock.type.typeValue = 53;
        dock.factionId = 3;
        dock.posX = 10_050;
        dock.posY = 6_000;
        dock.userName = "RepairDock";
        sim.world.apply(dock, sim.now);
        orbit.protocol.gen.AssetCreateCommand hq = new orbit.protocol.gen.AssetCreateCommand();
        hq.assetId = 900;
        hq.type = new orbit.protocol.gen.AssetTypeModule();
        hq.type.typeValue = 46;
        hq.factionId = 3;
        hq.posX = 13_000;
        hq.posY = 6_000;
        hq.userName = "HQ";
        sim.world.apply(hq, sim.now);
        cloak(sim, 0);
        Site site = new Site();
        site.cpus.add(new HangarAccess.Cpu("full", true, 50, Set.of()));
        CloakCare care = new CloakCare(sim.ctx);
        care.setHangar(site);
        sim.now += (CloakCare.SETTLE_MS + 1_000) * Sim.MS;
        run(sim, care, true, CRAFT, 3_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.MoveRequest m
                && Math.abs(m.targetX - 13_000) < 50), "to the HQ");
        assertTrue(site.moves.isEmpty(), "nothing on the site before the station");
    }
}
