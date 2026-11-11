package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.config.BotConfig;
import orbit.protocol.gen.TradeRequest;
import orbit.protocol.gen.TradeSellOreRequest;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class PalladiumModuleTest {

    private static BotConfig config() {
        return BotConfig.empty().withModule(BotConfig.Module.PALLADIUM)
                .withGeneral(new BotConfig.General(93, null, null, null, false, false, 300, false, false))
                .withCollect(new BotConfig.Collect(java.util.Map.of("ore_8", new BotConfig.BoxRule(true, 800, 0)), 400, false, true));
    }

    @Test
    void fiveThreeAndFiveTwoAreLinkedByPortals() {
        assertTrue(!orbit.bot.nav.Router.route(MapCatalog.bundled(), 93, 10_000, 6_000, 92, 2, 25).isEmpty(), "5-3 → 5-2");
    }

    @Test
    void aFullHoldOfPalladiumIsSoldAtFiveTwosTrader() {
        // already on 5-2 (the trip itself is the base sale's): the trader, palladium only, whatever «Сбор» says
        Sim sim = new Sim(config(), 92, 9_000, 5_000);
        orbit.protocol.gen.AssetCreateCommand ref = new orbit.protocol.gen.AssetCreateCommand();
        ref.assetId = 700;
        ref.type = new orbit.protocol.gen.AssetTypeModule();
        ref.type.typeValue = 51;
        ref.factionId = 0;
        ref.posX = 10_500;
        ref.posY = 6_500;
        sim.world.apply(ref, sim.now);
        orbit.protocol.gen.LegacyModule cap = new orbit.protocol.gen.LegacyModule();
        cap.message = "0|A|c|1000";
        sim.world.apply(cap, sim.now);
        orbit.protocol.gen.AttributeOreCountUpdateCommand o = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        o.oreCountList = List.of(ore(8, 950), ore(0, 50));
        sim.world.apply(o, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        for (int t = 0; t < 600 && sim.sent.stream().noneMatch(p -> p instanceof TradeRequest); t++) {
            sim.run(r::tick, 100);
            var h = sim.hero();
            if (Math.hypot(h.x() - 10_500, h.y() - 6_500) <= 500 && !sim.world.snapshot(sim.now).activatable().containsKey(700)) {
                orbit.protocol.gen.MapAssetActionAvailableCommand a = new orbit.protocol.gen.MapAssetActionAvailableCommand();
                a.mapAssetId = 700;
                a.activatable = true;
                sim.world.apply(a, sim.now);
            }
        }
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof TradeRequest), "the trade window at 5-2's trader");
        orbit.protocol.gen.OreTradeInfoCommand info = new orbit.protocol.gen.OreTradeInfoCommand();
        info.palladiumExchangeRate = 15; // palladium is exchanged, at 5-2 only
        info.prometiumPrice = 20;
        sim.world.apply(info, sim.now);
        sim.run(r::tick, 5_000);
        List<TradeSellOreRequest> sold = sim.sent.stream().filter(p -> p instanceof TradeSellOreRequest)
                .map(p -> (TradeSellOreRequest) p).toList();
        assertEquals(1, sold.size(), "palladium only: " + sold);
        assertEquals(8, sold.getFirst().toSell.oreType.typeValue);
        assertEquals(950, (long) sold.getFirst().toSell.count);
    }

    private static orbit.protocol.gen.OreCountModule ore(int type, double n) {
        orbit.protocol.gen.OreCountModule m = new orbit.protocol.gen.OreCountModule();
        m.oreType = new orbit.protocol.gen.OreTypeModule();
        m.oreType.typeValue = (short) type;
        m.count = n;
        return m;
    }
}
