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
        v.refusedWaitMs = 0;
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
        v.refusedWaitMs = 0;
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
        EnricherTest.hold(sim, new int[] {6, 0}); // the hold of this ship, listed after the login
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
        v.refusedWaitMs = 0;
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
        v.refusedWaitMs = 0;
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
    @Test
    void theHoldOfTheShipComeInCountsNotTheLastShipsOres() throws Exception {
        // live 2026-11-18 on gb2: the quest ship's 20 000 seprom and the Skylab ship's capacity — 14 192 ordered into a
        // hold of 8 000, refused
        Sim sim = sim();
        orbit.protocol.gen.ShipInitializationCommand quest = new orbit.protocol.gen.ShipInitializationCommand();
        quest.userId = 1;
        quest.mapId = 1;
        quest.hitPoints = 100;
        quest.hitMax = 100;
        quest.cargoSpace = 20_000;
        quest.cargoSpaceMax = 40_000;
        sim.world.apply(quest, sim.now);
        EnricherTest.hold(sim, new int[] {7, 20_000}); // seprom
        sim.now += 1_000_000_000L;
        orbit.protocol.gen.ShipInitializationCommand skylab = new orbit.protocol.gen.ShipInitializationCommand();
        skylab.userId = 1;
        skylab.mapId = 1;
        skylab.hitPoints = 100;
        skylab.hitMax = 100;
        skylab.cargoSpace = 7_997;
        skylab.cargoSpaceMax = 8_000;
        sim.world.apply(skylab, sim.now);
        assertEquals(7_997, sim.world.snapshot(sim.now).cargoFree(), "this ship's free room, not 8 000 − 20 000");
        EnricherTest.hold(sim, new int[] {8, 3}); // its own hold listed
        assertEquals(7_997, sim.world.snapshot(sim.now).cargoFree());
    }

    @Test
    void aRefusedTransportIsTriedAgainWithTheSameAmount() throws Exception {
        Sim sim = sim();
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        v.refusedWaitMs = 0;
        java.util.List<Map<String, Long>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        v.setSkylab(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) throws java.io.IOException {
                sent.add(Map.copyOf(amounts));
                if (sent.size() == 1) {
                    throw new java.io.IOException("Skylab: после отправки транспорт не в пути — сайт не принял (" + amounts + ")");
                }
            }
        });
        v.tick(WORK, true);
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true);
        EnricherTest.lab(sim, new int[] {0, 6, 19_990});
        EnricherTest.hold(sim, new int[] {6, 0});
        for (int i = 0; i < 100 && sent.size() < 2; i++) {
            v.tick(CITADEL, true);
            Thread.sleep(5);
        }
        assertEquals(2, sent.size(), "tried again");
        assertEquals(sent.get(0).get("promerium"), sent.get(1).get("promerium"), "the same: the amount was not the cause");
        v.tick(CITADEL, true);
        assertEquals(ServiceVisit.Phase.SERVICE, v.phase(), "the visit goes on, not back with nothing");
    }

    @Test
    void aRefusedQuickTransportIsNeverSentAsTheSlowOne() throws Exception {
        // no switch to the free, slow transport (live on int1: it held the visit for minutes)
        Sim sim = new Sim(EnricherTest.config(VISIT.replace("\"visit\":true", "\"visit\":true,\"visitFast\":true"), null),
                1, 1_000, 1_000);
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
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        v.refusedWaitMs = 0;
        java.util.List<Boolean> kinds = new java.util.concurrent.CopyOnWriteArrayList<>();
        v.setSkylab(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) throws java.io.IOException {
                kinds.add(fast);
                if (kinds.size() <= 2) {
                    throw new java.io.IOException("Skylab: после отправки транспорт не в пути — сайт не принял (" + amounts + ")");
                }
            }
        });
        v.tick(WORK, true);
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true);
        EnricherTest.lab(sim, new int[] {0, 6, 19_990});
        EnricherTest.hold(sim, new int[] {6, 0});
        for (int i = 0; i < 100 && kinds.size() < 3; i++) {
            v.tick(CITADEL, true);
            Thread.sleep(5);
        }
        assertEquals(3, kinds.size(), "tried again twice");
        assertTrue(kinds.stream().allMatch(Boolean::booleanValue), "always the quick one: " + kinds);
    }

    @Test
    void aSmallHoldGetsTheOreInSeveralTripsOfOneVisit() throws Exception {
        // live 2026-11-16 on gb2: 14 000 promerium wanted, an 8 000 hold with 5 000 in it — one trip of 2 997, back
        Sim sim = sim();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 1_000;
        si.y = 1_000;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.cargoSpace = 3_000;
        si.cargoSpaceMax = 3_000;
        sim.world.apply(si, sim.now);
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        v.refusedWaitMs = 0;
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
        v.tick(WORK, true);
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true);
        int left = 19_990;
        EnricherTest.lab(sim, new int[] {0, 6, left});
        EnricherTest.hold(sim, new int[] {6, 0}); // the hold of this ship, listed after the login
        for (int trip = 1; trip <= 3; trip++) {
            for (int i = 0; i < 100 && sent.size() < trip; i++) {
                v.tick(CITADEL, true);
                sim.now += 100_000_000L;
                Thread.sleep(5);
            }
            assertEquals(trip, sent.size(), "trip " + trip);
            assertEquals(3_000L, sent.get(trip - 1).get("promerium"), "as much as the hold takes");
            v.tick(CITADEL, true);
            sim.now += 5_000_000_000L;
            EnricherTest.hold(sim, new int[] {6, 3_000}); // landed
            v.tick(CITADEL, true); // into the lab
            sim.now += 1_000_000_000L;
            left += 30_000;
            EnricherTest.lab(sim, new int[] {0, 6, left});
            EnricherTest.hold(sim, new int[] {6, 0});
            sim.now += 11_000_000_000L;
        }
        assertEquals(ServiceVisit.Phase.SERVICE, v.phase(), "still at it");
    }
    @Test
    void theNextTripWaitsAMomentAfterTheLastOne() throws Exception {
        // live 2026-11-19 on int1: every quick order sent 1–4 s after a delivery was refused
        Sim sim = sim();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 1_000;
        si.y = 1_000;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.cargoSpace = 3_000;
        si.cargoSpaceMax = 3_000;
        sim.world.apply(si, sim.now);
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        java.util.List<Long> at = new java.util.concurrent.CopyOnWriteArrayList<>();
        v.setSkylab(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) {
                at.add(sim.now);
            }
        });
        v.tick(WORK, true);
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true);
        EnricherTest.lab(sim, new int[] {0, 6, 19_990});
        EnricherTest.hold(sim, new int[] {6, 0});
        for (int i = 0; i < 100 && at.isEmpty(); i++) {
            v.tick(CITADEL, true);
            sim.now += 100_000_000L;
            Thread.sleep(5);
        }
        assertEquals(1, at.size(), "the first trip");
        EnricherTest.hold(sim, new int[] {6, 3_000}); // landed at once (quick)
        v.tick(CITADEL, true);
        EnricherTest.lab(sim, new int[] {0, 6, 49_990});
        EnricherTest.hold(sim, new int[] {6, 0});
        for (int i = 0; i < 200 && at.size() < 2; i++) {
            v.tick(CITADEL, true);
            sim.now += 100_000_000L;
            Thread.sleep(2);
        }
        assertEquals(2, at.size(), "the second trip");
        assertTrue(at.get(1) - at.get(0) >= ServiceVisit.TRIP_GAP_MS * 1_000_000L,
                "not sooner than the gap: " + (at.get(1) - at.get(0)) / 1_000_000 + " ms");
    }

    @Test
    void aQuickTransportSpentBeforeItsOrderIsDoneStillLeadsToTheNextTrip() throws Exception {
        // live 2026-11-16 on gb2: the ore landed and went into the lab while the site's page was read again
        Sim sim = sim();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 1_000;
        si.y = 1_000;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.cargoSpace = 3_000;
        si.cargoSpaceMax = 3_000;
        sim.world.apply(si, sim.now);
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(sim.ctx);
        v.refusedWaitMs = 0;
        java.util.List<Map<String, Long>> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch landed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch spent = new java.util.concurrent.CountDownLatch(1);
        v.setSkylab(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) throws java.io.IOException {
                sent.add(amounts);
                if (sent.size() == 1) {
                    landed.countDown(); // in the hold at once
                    try {
                        spent.await(5, java.util.concurrent.TimeUnit.SECONDS); // the page read again only now
                    } catch (InterruptedException e) {
                        throw new java.io.IOException(e);
                    }
                }
            }
        });
        v.tick(WORK, true);
        sim.now += 60_000_000_000L;
        v.tick(CITADEL, true);
        EnricherTest.lab(sim, new int[] {0, 6, 19_990});
        EnricherTest.hold(sim, new int[] {6, 0}); // the hold of this ship, listed after the login
        for (int i = 0; i < 100 && landed.getCount() > 0; i++) {
            v.tick(CITADEL, true);
            Thread.sleep(5);
        }
        EnricherTest.hold(sim, new int[] {6, 3_000});
        sim.now += 1_000_000_000L;
        v.tick(CITADEL, true); // into the lab while the order is still on its way
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof LabUpdateItemRequest), "enriched at once");
        sim.now += 11_000_000_000L;
        EnricherTest.lab(sim, new int[] {0, 6, 49_990});
        EnricherTest.hold(sim, new int[] {6, 0});
        spent.countDown();
        for (int i = 0; i < 200 && sent.size() < 2; i++) {
            v.tick(CITADEL, true);
            sim.now += 100_000_000L;
            Thread.sleep(5);
        }
        assertEquals(2, sent.size(), "the second trip, not a 15 min wait");
    }
    @Test
    void stoppedOnTheServiceShipItGoesBackToItsOwnAfterTheNextStart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        // live 2026-11-16 on gb2: stopped on the Citadel mid-visit, started again — it flew off to the cubes on it
        Sim sim = sim();
        BotContext c = sim.ctx;
        BotContext disk = new BotContext(c.world(), c.config(), c.actions(), c.nav(), c.traveler(), c.maps(), c.random(),
                c.clock(), c.repair(), c.ship(), dir, c.stats(), c.ships());
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        ServiceVisit v = new ServiceVisit(disk);
        assertEquals(CITADEL, v.tick(WORK, true).switchTo());
        // the app closed here; a new start logs in on the citadel: the visit finished there first
        ServiceVisit again = new ServiceVisit(disk);
        again.setSkylab(new SkylabAccess() {
            @Override
            public Stock stock() {
                return new Stock(false, Map.of("promerium", 1_000_000L));
            }

            @Override
            public void transport(Map<String, Long> amounts, boolean fast) {
            }
        });
        sim.now += 1_000_000_000L;
        assertEquals(0, again.tick(CITADEL, true).switchTo(), "the lab of this ship asked, no ship change");
        assertEquals(ServiceVisit.Phase.SERVICE, again.phase());
        sim.now += 1_000_000_000L;
        EnricherTest.lab(sim, new int[] {0, 6, 900_000}); // all enriched up to its level
        assertEquals(WORK, again.tick(CITADEL, true).switchTo(), "nothing more to do: back to its own ship");
        assertEquals(ServiceVisit.Phase.RETURNING, again.phase());
        assertEquals(ServiceVisit.Step.NONE, again.tick(WORK, true));
        assertEquals(ServiceVisit.Phase.IDLE, again.phase());
        assertEquals(0, new ServiceVisit(disk).savedReturn(CITADEL), "done: nothing kept");
    }

    @Test
    void theServiceShipChosenAsTheWorkingOneIsLeftAlone(@org.junit.jupiter.api.io.TempDir java.nio.file.Path dir)
            throws Exception {
        Sim sim = sim();
        BotContext c = sim.ctx;
        BotContext disk = new BotContext(c.world(), c.config(), c.actions(), c.nav(), c.traveler(), c.maps(), c.random(),
                c.clock(), c.repair(), c.ship(), dir, c.stats(), c.ships());
        EnricherTest.lab(sim, new int[] {0, 6, 900_000});
        assertEquals(0, new ServiceVisit(disk).tick(CITADEL, true).switchTo(), "no visit cut short: it stays");
    }
}
