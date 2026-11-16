package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.KillModule;
import orbit.config.BotConfig;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.CollectBoxRequest;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipSelectionCommand;
import org.junit.jupiter.api.Test;

/** DarkBot's Kill & Collect: boxes taken in a fight (LootNCollectorModule), contested ones left (CollectorModule). */
class FightBoxesTest {

    static BotConfig config() {
        return BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, false, null))
                .withNpcs(Map.of("-=[ Target ]=-", new BotConfig.NpcRule(500, 0, true, Set.of(), Set.of(), null)))
                .withCollect(new BotConfig.Collect(Map.of("SOLAR_CLASH", new BotConfig.BoxRule(true, 0, -10000)), 400,
                        false, true));
    }

    static void box(Sim sim, String hash, int x, int y) {
        AddBoxCommand b = new AddBoxCommand();
        b.hash = hash;
        b.boxType = "SOLAR_CLASH";
        b.x = x;
        b.y = y;
        sim.world.apply(b, sim.now);
    }

    static boolean picked(Sim sim, String hash) {
        return sim.sent.stream().anyMatch(p -> p instanceof CollectBoxRequest r && hash.equals(r.itemHash));
    }

    @Test
    void aBoxWithinTheCollectRadiusIsTakenWhileTheTargetIsShot() {
        Sim sim = new Sim(config(), 1, 10_500, 6_000);
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        KillModule m = new KillModule(sim.ctx, true, true);
        sim.run(m::tick, 2_000);
        box(sim, "near", 10_500, 6_300);
        box(sim, "far", 10_500, 7_200);
        sim.run(m::tick, 6_000);
        assertTrue(picked(sim, "near"), "within 400 of the ship: taken in the fight");
        assertFalse(picked(sim, "far"), "beyond 400 while the target lives: left");
    }

    @Test
    void noBoxWhileTheTargetIsUnderAQuarterOfItsHp() {
        Sim sim = new Sim(config(), 1, 10_500, 6_000);
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        KillModule m = new KillModule(sim.ctx, true, true);
        sim.run(m::tick, 2_000);
        ShipSelectionCommand hp = new ShipSelectionCommand();
        hp.userId = 7;
        hp.hitpoints = 200_000;
        hp.hitpointsMax = 1_000_000;
        hp.shieldMax = 500_000;
        sim.world.apply(hp, sim.now);
        box(sim, "near", 10_500, 6_300);
        sim.run(m::tick, 4_000);
        assertFalse(picked(sim, "near"), "the target is finished first");
    }

    @Test
    void aBoxAnotherPlayerFliesToAndReachesFirstIsLeft() {
        Sim sim = new Sim(config(), 1, 10_500, 6_000);
        KillModule m = new KillModule(sim.ctx, false, true);
        box(sim, "theirs", 10_500, 6_300);
        ShipCreateCommand p = new ShipCreateCommand();
        p.userId = 50;
        p.userName = "other";
        p.x = 10_500;
        p.y = 6_500;
        sim.world.apply(p, sim.now);
        orbit.protocol.gen.MoveCommand mv = new orbit.protocol.gen.MoveCommand();
        mv.userId = 50;
        mv.x = 10_500;
        mv.y = 6_300;
        mv.timeToTarget = 1_000;
        sim.world.apply(mv, sim.now);
        sim.run(m::tick, 600);
        assertFalse(picked(sim, "theirs"), "200 away and flying to it: theirs (DarkBot IGNORE_CONTESTED_BOXES)");
    }
}
