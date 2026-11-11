package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.protocol.gen.TradeRequest;
import orbit.protocol.gen.TradeSellOreRequest;
import org.junit.jupiter.api.Test;

class CargoCareTest {

    /** The hold: 1 600 room; prometium 900, endurium 700 (full), seprom 50 (kept by default). */
    private static void fullHold(Sim sim) {
        orbit.protocol.gen.LegacyModule cap = new orbit.protocol.gen.LegacyModule();
        cap.message = "0|A|c|1600";
        sim.world.apply(cap, sim.now);
        orbit.protocol.gen.AttributeOreCountUpdateCommand o = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        o.oreCountList = List.of(ore(0, 900), ore(1, 700), ore(7, 50), ore(3, 5_000));
        sim.world.apply(o, sim.now);
    }

    private static orbit.protocol.gen.OreCountModule ore(int type, double n) {
        orbit.protocol.gen.OreCountModule m = new orbit.protocol.gen.OreCountModule();
        m.oreType = new orbit.protocol.gen.OreTypeModule();
        m.oreType.typeValue = (short) type;
        m.count = n;
        return m;
    }

    /** The prices as the server sent them at 2-8's refinery on 2026-11-11 (osmium too, palladium not bought). */
    private static void prices(Sim sim) {
        orbit.protocol.gen.OreTradeInfoCommand r = new orbit.protocol.gen.OreTradeInfoCommand();
        r.prometiumPrice = 20;
        r.enduriumPrice = 30;
        r.terbiumPrice = 50;
        r.prometidPrice = 400;
        r.duraniumPrice = 400;
        r.promeriumPrice = 1_000;
        r.sepromPrice = 1_500;
        r.osmiumPrice = 6_000;
        r.palladiumExchangeRate = -1;
        r.type = 1;
        sim.world.apply(r, sim.now);
    }

    private static BotConfig config(String cargo) throws Exception {
        BotConfig c = CubikonModuleTest.config(90, false);
        BotConfig.Collect k = c.collect();
        return c.withCollect(new BotConfig.Collect(k.boxes(), k.radius(), k.stayAwayFromEnemies(), k.ignoreContested(),
                cargo, 0, null));
    }

    private static List<TradeSellOreRequest> sold(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof TradeSellOreRequest).map(p -> (TradeSellOreRequest) p).toList();
    }

    @Test
    void theHoldsFreeRoomIsTheCapacityLessTheOreButXenomit() throws Exception {
        // ru1 2026-11-11: cargoSpace −11 175 = 1 600 − 12 775 (all ores but 731 931 xenomit)
        Sim sim = new Sim(config(BotConfig.Collect.CARGO_STOP), 26, 10_000, 6_000);
        fullHold(sim);
        assertEquals(1_600 - 1_650, sim.world.snapshot(sim.now).cargoFree());
    }

    @Test
    void atTheBaseTheShipFliesToTheRefineryAndSellsTheConfigsOres() throws Exception {
        // VRU's 3-1 (map 9): the refinery as recorded on 2026-11-09 at 20080/11500
        Sim sim = new Sim(config(BotConfig.Collect.CARGO_BASE), 9, 17_000, 11_000);
        orbit.protocol.gen.AssetCreateCommand ref = new orbit.protocol.gen.AssetCreateCommand();
        ref.assetId = 900;
        ref.type = new orbit.protocol.gen.AssetTypeModule();
        ref.type.typeValue = 50;
        ref.factionId = 3;
        ref.posX = 20_080;
        ref.posY = 11_500;
        ref.userName = "OreTrade";
        sim.world.apply(ref, sim.now);
        fullHold(sim);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        for (int t = 0; t < 400 && sim.sent.stream().noneMatch(p -> p instanceof TradeRequest); t++) {
            sim.run(r::tick, 100);
        }
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 20_080, h.y() - 11_500) <= CargoCare.AT_REFINERY + 50, "at the refinery: " + h);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof TradeRequest), "the trade window asked for");
        prices(sim);
        sim.run(r::tick, 5_000);
        List<TradeSellOreRequest> s = sold(sim);
        assertEquals(2, s.size(), "prometium and endurium, not seprom: " + s);
        assertEquals(900, (long) s.get(0).toSell.count);
        assertEquals(1, s.get(1).toSell.oreType.typeValue);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.LegacyModule l && "TWC".equals(l.message)),
                "the window closed after the sale, as the client does");
    }

    @Test
    void throughThePetsTradePodTheShipKeepsWorking() throws Exception {
        BotConfig c = config(BotConfig.Collect.CARGO_PET).withPet(new BotConfig.Pet(true, "GUARD"));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        orbit.protocol.gen.PetInitializationCommand init = new orbit.protocol.gen.PetInitializationCommand();
        init.hasPet = true;
        init.hasFuel = true;
        init.petIsAlive = true;
        sim.world.apply(init, sim.now);
        orbit.protocol.gen.PetHeroActivationCommand a = new orbit.protocol.gen.PetHeroActivationCommand();
        a.ownerId = 1;
        a.petId = 500;
        a.petName = "P";
        sim.world.apply(a, sim.now);
        orbit.protocol.gen.PetGearAddCommand pod = new orbit.protocol.gen.PetGearAddCommand();
        pod.gearType = new orbit.protocol.gen.PetGearTypeModule();
        pod.gearType.typeValue = 8;
        pod.enabled = true;
        sim.world.apply(pod, sim.now);
        fullHold(sim);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 4_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.PetGearActivationRequest g
                && g.gearTypeToActivate.typeValue == 8), "the trade pod");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof TradeRequest), "the pod alone opens the window (recorded)");
        assertEquals(BotRunner.State.WORKING, r.state(), "the ship is not taken off its work");
        prices(sim);
        sim.run(r::tick, 5_000);
        assertEquals(2, sold(sim).size());
    }

    @Test
    void nothingToSellMeansNoTripJustNoMoreCargo() throws Exception {
        Sim sim = new Sim(config(BotConfig.Collect.CARGO_BASE), 26, 10_000, 6_000);
        orbit.protocol.gen.LegacyModule cap = new orbit.protocol.gen.LegacyModule();
        cap.message = "0|A|c|1600";
        sim.world.apply(cap, sim.now);
        orbit.protocol.gen.AttributeOreCountUpdateCommand o = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        o.oreCountList = List.of(ore(7, 11_998)); // main's seprom: kept by default
        sim.world.apply(o, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 3_000);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof TradeRequest));
        assertTrue(!r.status().contains("Трюм"), r.status());
    }

    @Test
    void aFullHoldLeavesTheBoxesThatFillIt() {
        assertTrue(BotConfig.Collect.fillsCargo("FROM_SHIP"));
        assertTrue(BotConfig.Collect.fillsCargo("ore_1"));
        assertTrue(BotConfig.Collect.fillsCargo("LUMINIUM_2"));
        assertTrue(BotConfig.Collect.fillsCargo("MUCOSUM"));
        assertTrue(!BotConfig.Collect.fillsCargo("BONUS_BOX"));
        assertTrue(!BotConfig.Collect.fillsCargo("PIRATE_BOOTY_GOLD"));
        assertEquals(Map.of(), Map.of());
    }
}
