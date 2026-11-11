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

    /** The user's profile: collect ship 801119 on 5-3, sell ship 801116 parked at 5-2; no portal before a switch. */
    private static BotConfig twoShips() throws Exception {
        return config().withModuleSettings("palladium", orbit.config.Json.MAPPER.readTree(
                "{\"goPortalChange\": false, \"sellOnDie\": true, \"collectHangar\": 801119, \"sellHangar\": 801116,"
                        + " \"aditionalWaitingTime\": 5}"));
    }

    private static void hold(Sim sim, double palladium) {
        orbit.protocol.gen.LegacyModule cap = new orbit.protocol.gen.LegacyModule();
        cap.message = "0|A|c|1000";
        sim.world.apply(cap, sim.now);
        orbit.protocol.gen.AttributeOreCountUpdateCommand o = new orbit.protocol.gen.AttributeOreCountUpdateCommand();
        o.oreCountList = List.of(ore(8, palladium));
        sim.world.apply(o, sim.now);
    }

    @Test
    void theFullCollectShipLogsOutWhereItIsForTheSellShip() throws Exception {
        Sim sim = new Sim(twoShips(), 93, 10_000, 6_000);
        sim.ctx.ships().setPalladium(801119, 801116); // the account's pair
        hold(sim, 1_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(801119);
        sim.run(r::tick, 6_000);
        assertEquals(801116, r.hangarRequest(), "the sell ship");
        assertEquals(BotRunner.State.STOPPING, r.state());
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 10_000, h.y() - 6_000) < 50, "no flight to a portal (goPortalChange false): " + h);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.UIButtonTriggerRequest u && "logout".equals(u.itemId)),
                "logs out at once");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof TradeRequest), "no trip to 5-2 with the collect ship");
    }

    @Test
    void theSellShipSellsThePalladiumThenAsksForTheCollectShip() throws Exception {
        Sim sim = new Sim(twoShips(), 92, 10_200, 6_200); // parked by the trader
        sim.ctx.ships().setPalladium(801119, 801116); // the account's pair
        orbit.protocol.gen.AssetCreateCommand ref = new orbit.protocol.gen.AssetCreateCommand();
        ref.assetId = 700;
        ref.type = new orbit.protocol.gen.AssetTypeModule();
        ref.type.typeValue = 51;
        ref.posX = 10_500;
        ref.posY = 6_500;
        sim.world.apply(ref, sim.now);
        orbit.protocol.gen.MapAssetActionAvailableCommand a = new orbit.protocol.gen.MapAssetActionAvailableCommand();
        a.mapAssetId = 700;
        a.activatable = true;
        sim.world.apply(a, sim.now);
        hold(sim, 400); // not full: the sell ship sells whatever palladium there is
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(801116);
        sim.run(r::tick, 2_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof TradeRequest), "trades at once");
        orbit.protocol.gen.OreTradeInfoCommand info = new orbit.protocol.gen.OreTradeInfoCommand();
        info.palladiumExchangeRate = 15;
        sim.world.apply(info, sim.now);
        sim.run(r::tick, 3_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof TradeSellOreRequest s && s.toSell.oreType.typeValue == 8));
        hold(sim, 0); // the server took it
        sim.run(r::tick, 2_000);
        assertEquals(801119, r.hangarRequest(), "back to the collect ship: " + r.status() + " selling=" + sim.ctx.stats().selling
                + " sold=" + sim.sent.stream().filter(p -> p instanceof TradeSellOreRequest).count());
    }

    @Test
    void theConfigsDarkBotHangarsAloneSwitchNothing() throws Exception {
        // live 2026-11-11: the profile's hangars were another account's; the bot logged out every minute for them
        Sim sim = new Sim(twoShips(), 93, 10_000, 6_000);
        hold(sim, 1_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(332663);
        sim.run(r::tick, 3_000);
        assertEquals(0, r.hangarRequest(), "no ship of the account chosen: no switch");
        assertTrue(r.state() != BotRunner.State.STOPPING && r.state() != BotRunner.State.STOPPED,
                "no logout; sells with the one ship instead: " + r.state());
    }

    @Test
    void destroyedWithPalladiumTheSellShipInsteadOfARevive() throws Exception {
        Sim sim = new Sim(twoShips(), 93, 10_000, 6_000);
        sim.ctx.ships().setPalladium(801119, 801116); // the account's pair
        hold(sim, 300);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(801119);
        sim.run(r::tick, 1_000);
        orbit.protocol.gen.KillScreenPostCommand k = new orbit.protocol.gen.KillScreenPostCommand();
        k.killerName = "x";
        k.options = List.of();
        sim.world.apply(k, sim.now);
        sim.run(r::tick, 1_000);
        assertEquals(801116, r.hangarRequest());
        assertEquals(BotRunner.State.STOPPED, r.state(), "no revive: the connection ends for the switch");
        assertTrue(sim.repairs.isEmpty());
    }
}
