package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import orbit.config.BotConfig;
import orbit.protocol.gen.LabUpdateItemRequest;
import org.junit.jupiter.api.Test;

class ServiceVisitTest {

    static final String VISIT = """
            {"enabled":true,"visit":true,"visitBelow":50000,"fillTo":200000,"visitWaitMinutes":15,"visitEveryHours":3,
             "items":{"laser":{"on":true,"ore":"PROMERIUM","below":50000,"load":10000}}}""";
    static final int WORK = 11;
    static final int CITADEL = 22;

    static Sim sim() throws Exception {
        Sim sim = new Sim(EnricherTest.config(VISIT, null), 1, 1_000, 1_000);
        sim.now = 1_000_000_000L;
        sim.ctx.ships().setServiceShip(CITADEL);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 1_000;
        si.y = 1_000;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.cargoSpace = 100_000;
        si.cargoSpaceMax = 100_000;
        sim.world.apply(si, sim.now);
        return sim;
    }

    @Test
    void lowWithNoOreInTheHoldAtACalmMomentTheShipIsChanged() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        assertEquals(0, v.tick(WORK, false).switchTo(), "not calm: no visit");
        ServiceVisit.Step s = v.tick(WORK, true);
        assertEquals(CITADEL, s.switchTo());
        assertEquals(ServiceVisit.Phase.GOING, v.phase());
    }

    @Test
    void enoughOreInTheHoldNoVisit() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        EnricherTest.hold(sim, new int[] {6, 50_000});
        assertEquals(0, new ServiceVisit(sim.ctx).tick(WORK, true).switchTo());
    }

    @Test
    void onTheServiceShipItBringsTheOreEnrichesUpToTheLevelAndGoesBack() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        java.util.List<Map<String, Long>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        v.setSkylab(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) {
                sent.add(amounts);
            }
        });
        v.tick(WORK, true); // → GOING
        sim.now += 60_000_000_000L; // logged in on the citadel
        assertTrue(v.tick(CITADEL, true).busy());
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.LabUpdateRequest), "the lab asked");
        EnricherTest.lab(sim, new int[] {0, 6, 19_990}); // the same enrichment: the account's
        for (int i = 0; i < 50 && sent.isEmpty(); i++) {
            v.tick(CITADEL, true);
            Thread.sleep(10);
        }
        assertEquals(Map.of("promerium", 18_001L), sent.get(0), "(200 000 − 19 990) / 10, rounded up");
        v.tick(CITADEL, true);
        sim.now += 30_000_000_000L;
        EnricherTest.hold(sim, new int[] {6, 18_001}); // arrived
        v.tick(CITADEL, true);
        LabUpdateItemRequest r = sim.sent.stream().filter(p -> p instanceof LabUpdateItemRequest)
                .map(p -> (LabUpdateItemRequest) p).findFirst().orElseThrow();
        assertEquals(18_001, r.updateWith.count);
        sim.now += 1_000_000_000L;
        EnricherTest.lab(sim, new int[] {0, 6, 200_000});
        ServiceVisit.Step back = v.tick(CITADEL, true);
        assertEquals(WORK, back.switchTo(), "all filled: back");
        assertEquals(0, v.tick(WORK, true).switchTo());
        assertEquals(ServiceVisit.Phase.IDLE, v.phase());
        assertEquals(0, v.tick(WORK, true).switchTo(), "not again soon");
    }

    @Test
    void anotherEnrichmentOnTheServiceShipStopsTheVisits() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        v.tick(WORK, true);
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true);
        EnricherTest.lab(sim, new int[] {0, 7, 900_000}); // the citadel's own
        assertEquals(WORK, v.tick(CITADEL, true).switchTo(), "back at once");
        v.tick(WORK, true);
        sim.now += 10L * 3_600_000_000_000L;
        assertFalse(v.tick(WORK, true).switchTo() != 0, "no more visits");
    }

    @Test
    void afterThePalladiumSaleTheServiceShipComesBeforeTheCollectOne() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        int sell = 33;
        int collect = 44;
        assertEquals(CITADEL, v.divert(sell, collect), "a visit is due: the citadel first");
        assertEquals(ServiceVisit.Phase.GOING, v.phase());
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true, false);
        EnricherTest.lab(sim, new int[] {0, 6, 200_000}); // filled (the ore was there)
        assertEquals(collect, v.tick(CITADEL, true, false).switchTo(), "then the collect ship");
    }

    @Test
    void noVisitDueTheModulesChangeStaysAsItWas() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 900_000});
        assertEquals(44, new ServiceVisit(sim.ctx).divert(33, 44));
    }
}
