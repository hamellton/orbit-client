package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.LabUpdateItemRequest;
import org.junit.jupiter.api.Test;

class EnricherTest {

    static BotConfig config(String enrich, String skylab) throws Exception {
        BotConfig c = BotConfig.empty().withModuleSettings("enrich", Json.MAPPER.readTree(enrich));
        return skylab == null ? c : c.withModuleSettings("skylab", Json.MAPPER.readTree(skylab));
    }

    static final String ON = """
            {"enabled":true,"items":{"laser":{"on":true,"ore":"PROMERIUM","below":50000,"load":10000},
             "shield":{"on":true,"ore":"SEPROM","below":50000,"load":1000}}}""";

    /** The lab's answer: items {item, ore, left}. */
    static void lab(Sim sim, int[]... items) {
        orbit.protocol.gen.LabUpdateItemCommand c = new orbit.protocol.gen.LabUpdateItemCommand();
        for (int[] it : items) {
            orbit.protocol.gen.UpdateItemModule u = new orbit.protocol.gen.UpdateItemModule();
            u.itemToUpdate = new orbit.protocol.gen.LabItemModule();
            u.itemToUpdate.itemValue = (short) it[0];
            u.oreCountToUpdateWith = new orbit.protocol.gen.OreCountModule();
            u.oreCountToUpdateWith.oreType = new orbit.protocol.gen.OreTypeModule();
            u.oreCountToUpdateWith.oreType.typeValue = (short) it[1];
            u.oreCountToUpdateWith.count = it[2];
            c.itemsUpdatedInfo.add(u);
        }
        sim.world.apply(c, sim.now);
    }

    static void hold(Sim sim, int[]... ores) {
        orbit.protocol.gen.AttributeOreCountUpdateCommand c = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        for (int[] o : ores) {
            orbit.protocol.gen.OreCountModule m = new orbit.protocol.gen.OreCountModule();
            m.oreType = new orbit.protocol.gen.OreTypeModule();
            m.oreType.typeValue = (short) o[0];
            m.count = o[1];
            c.oreCountList.add(m);
        }
        sim.world.apply(c, sim.now);
    }

    static List<LabUpdateItemRequest> requests(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof LabUpdateItemRequest).map(p -> (LabUpdateItemRequest) p).toList();
    }

    @Test
    void anItemBelowItsThresholdGetsOreFromTheHoldOneAtATime() throws Exception {
        Sim sim = new Sim(config(ON, null), 1, 1_000, 1_000);
        lab(sim, new int[] {0, 6, 20_000}, new int[] {3, 7, 9_000});
        hold(sim, new int[] {6, 50_000}, new int[] {7, 600});
        Enricher e = new Enricher(sim.ctx);
        e.tick();
        e.tick(); // no answer yet: no second request
        List<LabUpdateItemRequest> r = requests(sim);
        assertEquals(1, r.size());
        assertEquals(0, r.get(0).itemToUpdate.itemValue, "the laser first");
        assertEquals(6, r.get(0).updateWith.oreType.typeValue);
        assertEquals(10_000, r.get(0).updateWith.count, "the config's load");
        sim.now += 1_000_000;
        lab(sim, new int[] {0, 6, 120_000}, new int[] {3, 7, 9_000}); // answered
        e.tick();
        r = requests(sim);
        assertEquals(2, r.size());
        assertEquals(3, r.get(1).itemToUpdate.itemValue);
        assertEquals(600, r.get(1).updateWith.count, "no more than the hold has");
    }

    @Test
    void aRunningEnrichmentIsToppedUpWithItsOwnOreNotTheConfigs() throws Exception {
        Sim sim = new Sim(config(ON, null), 1, 1_000, 1_000);
        lab(sim, new int[] {0, 7, 1_000}); // lasers run on seprom, the config says promerium
        hold(sim, new int[] {6, 50_000}, new int[] {7, 50_000});
        new Enricher(sim.ctx).tick();
        assertEquals(7, requests(sim).get(0).updateWith.oreType.typeValue);
    }

    @Test
    void lackingOreIsToldForTheSkylabNoRequest() throws Exception {
        Sim sim = new Sim(config(ON, null), 1, 1_000, 1_000);
        lab(sim, new int[] {0, 6, 1_000});
        hold(sim, new int[] {8, 5}); // palladium only
        Enricher e = new Enricher(sim.ctx);
        e.tick();
        assertTrue(requests(sim).isEmpty());
        assertEquals(Map.of(6, 10_000L, 7, 1_000L), e.missing(), "the laser's promerium, the empty shield's seprom");
    }

    @Test
    void offByDefault() throws Exception {
        Sim sim = new Sim(BotConfig.empty(), 1, 1_000, 1_000);
        lab(sim, new int[] {0, 6, 1});
        hold(sim, new int[] {6, 50_000});
        new Enricher(sim.ctx).tick();
        assertTrue(requests(sim).isEmpty());
    }

    @Test
    void theSkylabSendsWhatIsWantedNoMoreThanTheStorageHolds() throws Exception {
        Sim sim = new Sim(config(ON, """
                {"enabled":true,"ores":{"seprom":1000},"everyMinutes":20,"keepOnSkylab":100}"""), 1, 1_000, 1_000);
        lab(sim, new int[] {0, 6, 1_000});
        hold(sim, new int[] {8, 5});
        Enricher e = new Enricher(sim.ctx);
        e.tick();
        java.util.concurrent.CompletableFuture<Map<String, Long>> sent = new java.util.concurrent.CompletableFuture<>();
        SkylabCare care = new SkylabCare(sim.ctx, e::missing);
        care.setAccess(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("seprom", 600L, "promerium", 99_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) {
                sent.complete(amounts);
            }
        });
        care.tick();
        Map<String, Long> got = sent.get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertEquals(500L, got.get("seprom"), "600 there, 100 kept");
        assertEquals(10_000L, got.get("promerium"), "what the enrichment lacks");
    }

    @Test
    void theOresOfTheEnrichmentAreNotSold() throws Exception {
        BotConfig c = config(ON, null); // laser promerium, shield seprom
        CargoPlan plan = new CargoPlan("BASE", 0, List.of(0, 1, 2, 4, 5, 6));
        assertEquals(List.of(0, 1, 2, 4, 5), CargoCare.keepEnrichOres(c, plan).ores(), "promerium kept");
        assertEquals(plan, CargoCare.keepEnrichOres(BotConfig.empty(), plan), "no enrichment: as it was");
        // refining on (the leveling configs): the drive below its threshold keeps the raw ores and the refined ones
        BotConfig refining = BotConfig.empty().withModuleSettings("enrich", Json.MAPPER.readTree(
                "{\"enabled\":true,\"refine\":true,\"items\":{\"drive\":{\"on\":true,\"ore\":\"PROMERIUM\",\"below\":100}}}"));
        CargoPlan all = new CargoPlan(BotConfig.Collect.CARGO_BASE, 0, List.of(0, 1, 2, 3, 4, 5, 6));
        orbit.world.WorldSnapshot.Lab low = new orbit.world.WorldSnapshot.Lab(java.util.Map.of(2, new orbit.world.WorldSnapshot.Enrichment(6, 40)), 1);
        orbit.world.WorldSnapshot.Lab charged = new orbit.world.WorldSnapshot.Lab(java.util.Map.of(2, new orbit.world.WorldSnapshot.Enrichment(6, 150)), 1);
        assertEquals(List.of(), CargoCare.keepEnrichOres(refining, all, low, 5).ores(), "low: all kept for promerium");
        assertEquals(List.of(), CargoCare.keepEnrichOres(refining, all, null, -1).ores(), "lab not read: kept");
        assertEquals(List.of(0, 1, 2), CargoCare.keepEnrichOres(refining, all, low, 0).ores(),
                "low but no xenomit: raw sold, prometid and duranium kept");
        assertEquals(List.of(0, 1, 2, 4, 5), CargoCare.keepEnrichOres(refining, all, charged, 5).ores(),
                "charged: raw and refined sold, xenomit and promerium kept");
    }

    static Map<String, Long> fastSend(int free, int max) throws Exception {
        Sim sim = new Sim(config(ON, """
                {"enabled":true,"ores":{"promerium":10000},"everyMinutes":20,"fast":true,"fastMinFreePercent":50}"""),
                1, 1_000, 1_000);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 1_000;
        si.y = 1_000;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.cargoSpace = free;
        si.cargoSpaceMax = max;
        sim.world.apply(si, sim.now);
        java.util.concurrent.CompletableFuture<Map<String, Long>> sent = new java.util.concurrent.CompletableFuture<>();
        SkylabCare care = new SkylabCare(sim.ctx, Map::of);
        care.setAccess(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) {
                sent.complete(amounts);
            }
        });
        care.tick();
        try {
            return sent.get(1, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            return Map.of();
        }
    }

    @Test
    void thePaidTransportGoesIntoAHoldAtLeastHalfFreeElseWaits() throws Exception {
        // the citadel without premium holds 8 000; 6 000 free of 10 000 wanted — 6 000 go
        assertEquals(Map.of("promerium", 6_000L), fastSend(6_000, 8_000));
        assertEquals(Map.of(), fastSend(3_000, 8_000), "3 000 of 8 000 free: under 50 %, the paid one waits");
    }
}
