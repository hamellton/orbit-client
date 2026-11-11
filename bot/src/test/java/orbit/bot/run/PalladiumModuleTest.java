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
    void palladiumInSightIsHarvestedThoughOutsideTheCollectRadius() {
        // live 2026-11-11: the ship roamed the field, the palladium (AddOreCommand, ore 8) was never taken
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        orbit.protocol.gen.AddOreCommand ore = new orbit.protocol.gen.AddOreCommand();
        ore.hash = "p1";
        ore.oreType = new orbit.protocol.gen.OreTypeModule();
        ore.oreType.typeValue = 8;
        ore.x = 21_500;
        ore.y = 20_000;
        sim.world.apply(ore, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 6_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.HarvestRequest h && "p1".equals(h.itemHash)),
                "flew 1 500 to it and harvested it (radius 400 counts only next to a target, as in DarkBot)");
    }

    @Test
    void palladiumIsClickedAtOnceAndTheWaitComesAfter() {
        // DarkBot's collector: collect first, then stay the box's wait (ore_8: 800 ms in the user's profile)
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        orbit.protocol.gen.AddOreCommand ore = new orbit.protocol.gen.AddOreCommand();
        ore.hash = "p1";
        ore.oreType = new orbit.protocol.gen.OreTypeModule();
        ore.oreType.typeValue = 8;
        ore.x = 20_000;
        ore.y = 20_000;
        sim.world.apply(ore, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 500);
        java.util.function.LongSupplier clicks = () -> sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.HarvestRequest).count();
        assertEquals(1, clicks.getAsLong(), "clicked within half a second, not after the 800 ms");
        sim.run(r::tick, 500);
        assertEquals(1, clicks.getAsLong(), "no second click inside the wait");
        sim.run(r::tick, 600);
        assertEquals(2, clicks.getAsLong(), "still there after the wait: clicked again");
    }

    @Test
    void theGamesBoxCheckIsDoneByPickingTheAskedBoxesAndNothingElse() {
        // live 2026-11-12 on gb2: the game's box check came, the bot kept flying at the circle's edge
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        orbit.protocol.gen.AddOreCommand ore = new orbit.protocol.gen.AddOreCommand();
        ore.hash = "p1";
        ore.oreType = new orbit.protocol.gen.OreTypeModule();
        ore.oreType.typeValue = 8;
        ore.x = 21_000;
        ore.y = 20_000;
        sim.world.apply(ore, sim.now);
        orbit.protocol.gen.CaptchaTriggerCommand c = new orbit.protocol.gen.CaptchaTriggerCommand();
        c.type = (short) orbit.protocol.gen.CaptchaTriggerCommand.ECAPTCHATYPE_SOME_BLACK;
        c.captchaParam = 3;
        c.captchaTimer = 40;
        c.posX = 20_000;
        c.posY = 20_000;
        c.Radius = 400;
        c.blackBox = 5;
        c.redBox = 3;
        sim.world.apply(c, sim.now);
        var boxes = sim.world.snapshot(sim.now).boxCheck().boxes();
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        int sent = sim.sent.size();
        sim.run(r::tick, 1_000);
        assertTrue(sim.sent.size() == sent, "reads the task first: " + sim.sent.subList(sent, sim.sent.size()));
        assertTrue(r.status().equals("ПРОВЕРКА: соберите 3 чёрных ящика из 5, осталось 40 с"), r.status());
        sim.run(r::tick, 20_000);
        var during = sim.sent.subList(sent, sim.sent.size());
        assertTrue(during.stream().noneMatch(p -> p instanceof orbit.protocol.gen.HarvestRequest), "no ore during the check");
        java.util.List<Integer> picked = during.stream().filter(p -> p instanceof orbit.protocol.gen.CaptchaCollectRequest)
                .map(p -> ((orbit.protocol.gen.CaptchaCollectRequest) p).index).toList();
        assertEquals(3, picked.size(), "three picks: " + picked);
        assertEquals(3, new java.util.HashSet<>(picked).size(), "three different boxes: " + picked);
        assertTrue(picked.stream().allMatch(i -> i < 5), "black ones only: " + picked);
        for (int i = 0; i < during.size(); i++) {
            if (during.get(i) instanceof orbit.protocol.gen.CaptchaCollectRequest p) {
                var box = boxes.stream().filter(b -> b.index() == p.index).findFirst().orElseThrow();
                orbit.protocol.gen.MoveRequest last = null;
                for (int j = i - 1; j >= 0 && last == null; j--) {
                    if (during.get(j) instanceof orbit.protocol.gen.MoveRequest m) {
                        last = m;
                    }
                }
                assertTrue(last != null && last.targetX == box.x() && last.targetY == box.y(), "flew onto the box first");
            }
        }
        assertEquals(0, sim.world.snapshot(sim.now).boxCheck().left());
        sim.world.apply(new orbit.protocol.gen.CaptchaResolvedCommand(), sim.now);
        sim.run(r::tick, 8_000); // the last box may lie across the circle from the ore
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.HarvestRequest), "back to work once solved");
    }

    private static orbit.protocol.gen.CaptchaTriggerCommand allRed(int x, int y) {
        orbit.protocol.gen.CaptchaTriggerCommand c = new orbit.protocol.gen.CaptchaTriggerCommand();
        c.type = (short) orbit.protocol.gen.CaptchaTriggerCommand.ECAPTCHATYPE_ALL_RED;
        c.captchaTimer = 3;
        c.posX = x;
        c.posY = y;
        c.Radius = 500;
        c.blackBox = 2;
        c.redBox = 6;
        return c;
    }

    private static java.util.List<Integer> picks(java.util.List<orbit.protocol.Packet> sent) {
        return sent.stream().filter(p -> p instanceof orbit.protocol.gen.CaptchaCollectRequest)
                .map(p -> ((orbit.protocol.gen.CaptchaCollectRequest) p).index).toList();
    }

    @Test
    void aShipFlyingAwayWhenTheCheckComesIsStoppedInsideTheCircle() {
        // live 2026-11-12 on ge3: on its way to a portal the ship flew out of the circle and picked nothing
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        orbit.protocol.gen.MoveRequest away = new orbit.protocol.gen.MoveRequest();
        away.positionX = 20_000;
        away.positionY = 20_000;
        away.targetX = 5_000;
        away.targetY = 20_000;
        sim.receive(away);
        sim.world.apply(allRed(20_000, 20_000), sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.tick();
        sim.run(r::tick, 300);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(!h.moving() && Math.hypot(h.x() - 20_000, h.y() - 20_000) < 500, "stopped in the circle: " + h.x());
        sim.run(r::tick, 20_000);
        assertEquals(java.util.Set.of(2, 3, 4, 5, 6, 7), new java.util.HashSet<>(picks(sim.sent)));
    }

    @Test
    void aShipTheServerHoldsStillPicksTheBoxesWhereItStands() {
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        sim.holdShip = true;
        sim.world.apply(allRed(20_000, 20_000), sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 60_000);
        assertEquals(6, picks(sim.sent).size(), "every red box, clicked a few times each first: " + picks(sim.sent));
        assertEquals(java.util.Set.of(2, 3, 4, 5, 6, 7), new java.util.HashSet<>(picks(sim.sent)));
    }

    @Test
    void theCheckStopsTheLasersAtOnce() {
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        sim.npc(50, "-=[ Barracuda ]=-", 20_300, 20_000);
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = 1;
        run.targetId = 50;
        sim.world.apply(run, sim.now);
        sim.world.apply(allRed(20_000, 20_000), sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        int sent = sim.sent.size();
        r.tick();
        assertTrue(sim.sent.subList(sent, sim.sent.size()).stream()
                .anyMatch(p -> p instanceof orbit.protocol.gen.AttackAbortLaserRequest), "lasers stopped: " + sim.sent);
    }

    @Test
    void aShipDestroyedDuringTheCheckIsRevived() {
        // live 2026-11-12 on ge3: killed by an NPC during the check, the bot stayed on the kill screen
        Sim sim = new Sim(config(), 93, 20_000, 20_000);
        sim.holdShip = true;
        sim.world.apply(allRed(20_000, 20_000), sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 1_000);
        orbit.protocol.gen.KillScreenPostCommand k = new orbit.protocol.gen.KillScreenPostCommand();
        k.killerName = "-=[ Barracuda ]=-";
        k.options = java.util.List.of();
        sim.world.apply(k, sim.now);
        assertEquals(null, sim.world.snapshot(sim.now).boxCheck(), "the check goes with the ship");
        sim.run(r::tick, 1_000);
        assertEquals(BotRunner.State.DEAD, r.state());
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
        // the client's palladium button: the legacy XCP|count, whole parts of the rate (950 → 945 at 15)
        assertEquals(List.of("XCP|945"), exchanges(sim));
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof TradeSellOreRequest), "not sold as ore (the game ignores that)");
    }

    private static List<String> exchanges(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.LegacyModule l && l.message.startsWith("XCP"))
                .map(p -> ((orbit.protocol.gen.LegacyModule) p).message).toList();
    }

    @Test
    void aSaleTheGameDidNotTakeIsNotRepeatedInALoop() {
        // live 2026-11-11: palladium «sold» every 12 s for 6 minutes, the hold never changed
        Sim sim = new Sim(config(), 92, 10_200, 6_200);
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
        hold(sim, 1_000);
        orbit.protocol.gen.OreTradeInfoCommand info = new orbit.protocol.gen.OreTradeInfoCommand();
        info.palladiumExchangeRate = 15;
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        for (int t = 0; t < 60; t++) { // a minute; the server answers every window and never takes the palladium
            sim.run(r::tick, 1_000);
            sim.world.apply(info, sim.now);
        }
        assertTrue(exchanges(sim).size() <= 2, "at most once more, then the pause: " + exchanges(sim));
    }

    private static orbit.protocol.gen.OreCountModule ore(int type, double n) {
        orbit.protocol.gen.OreCountModule m = new orbit.protocol.gen.OreCountModule();
        m.oreType = new orbit.protocol.gen.OreTypeModule();
        m.oreType.typeValue = (short) type;
        m.count = n;
        return m;
    }

    /** The user's profile: collect ship 500101 on 5-3, sell ship 500102 parked at 5-2; no portal before a switch. */
    private static BotConfig twoShips() throws Exception {
        return config().withModuleSettings("palladium", orbit.config.Json.MAPPER.readTree(
                "{\"goPortalChange\": false, \"sellOnDie\": true, \"collectHangar\": 500101, \"sellHangar\": 500102,"
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
        sim.ctx.ships().setPalladium(500101, 500102); // the account's pair
        hold(sim, 1_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(500101);
        sim.run(r::tick, 6_000);
        assertEquals(500102, r.hangarRequest(), "the sell ship");
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
        sim.ctx.ships().setPalladium(500101, 500102); // the account's pair
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
        r.setActiveHangar(500102);
        sim.run(r::tick, 2_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof TradeRequest), "trades at once");
        orbit.protocol.gen.OreTradeInfoCommand info = new orbit.protocol.gen.OreTradeInfoCommand();
        info.palladiumExchangeRate = 15;
        sim.world.apply(info, sim.now);
        sim.run(r::tick, 3_000);
        assertEquals(List.of("XCP|390"), exchanges(sim), "400 at 15: 390, ten stay aboard");
        hold(sim, 10); // the server took it; less than one rate's worth stays
        sim.run(r::tick, 2_000);
        assertEquals(500101, r.hangarRequest(), "back to the collect ship: " + r.status() + " selling=" + sim.ctx.stats().selling
                + " exchanged=" + exchanges(sim));
    }

    @Test
    void theConfigsDarkBotHangarsAloneSwitchNothing() throws Exception {
        // live 2026-11-11: the profile's hangars were another account's; the bot logged out every minute for them
        Sim sim = new Sim(twoShips(), 93, 10_000, 6_000);
        hold(sim, 1_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(500103);
        sim.run(r::tick, 3_000);
        assertEquals(0, r.hangarRequest(), "no ship of the account chosen: no switch");
        assertTrue(r.state() != BotRunner.State.STOPPING && r.state() != BotRunner.State.STOPPED,
                "no logout; sells with the one ship instead: " + r.state());
    }

    @Test
    void destroyedWithPalladiumTheSellShipInsteadOfARevive() throws Exception {
        Sim sim = new Sim(twoShips(), 93, 10_000, 6_000);
        sim.ctx.ships().setPalladium(500101, 500102); // the account's pair
        hold(sim, 300);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(500101);
        sim.run(r::tick, 1_000);
        orbit.protocol.gen.KillScreenPostCommand k = new orbit.protocol.gen.KillScreenPostCommand();
        k.killerName = "x";
        k.options = List.of();
        sim.world.apply(k, sim.now);
        sim.run(r::tick, 1_000);
        assertEquals(500102, r.hangarRequest());
        assertEquals(BotRunner.State.STOPPED, r.state(), "no revive: the connection ends for the switch");
        assertTrue(sim.repairs.isEmpty());
    }
}
