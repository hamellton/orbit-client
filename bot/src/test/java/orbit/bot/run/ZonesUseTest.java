package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import orbit.bot.mod.KillModule;
import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

class ZonesUseTest {

    /** 3-1 (map 9): preferred — one cell in the bottom-right corner of a 10×10 grid; avoided — the left half. */
    private static BotConfig config() {
        List<String> pref = new ArrayList<>();
        List<String> avoid = new ArrayList<>();
        for (int y = 0; y < 10; y++) {
            pref.add(y == 8 ? "........#." : "..........");
            avoid.add("#####.....");
        }
        return BotConfig.empty().withModule(BotConfig.Module.COLLECT)
                .withCollect(new BotConfig.Collect(Map.of("ore_8", new BotConfig.BoxRule(true, 0, 0)), 5_000, false, false))
                .withZones(Map.of(9, new BotConfig.Zones(new BotConfig.ZoneGrid(10, pref), new BotConfig.ZoneGrid(10, avoid))));
    }

    @Test
    void roamingStaysInThePreferredZone() {
        Sim sim = new Sim(config(), 9, 15_000, 6_000);
        KillModule m = new KillModule(sim.ctx, false, true);
        for (int i = 0; i < 5; i++) {
            m.pause();
            m.tick();
            var d = sim.ctx.nav().destination();
            assertTrue(d.x() >= 8 * 2_100 && d.x() <= 9 * 2_100 && d.y() >= 8 * 1_310 && d.y() <= 9 * 1_310,
                    "inside the preferred cell: " + d);
        }
    }

    @Test
    void aBoxInTheAvoidedZoneIsLeft() {
        Sim sim = new Sim(config(), 9, 11_000, 6_000);
        orbit.protocol.gen.AddBoxCommand left = new orbit.protocol.gen.AddBoxCommand();
        left.hash = "avoided";
        left.boxType = "ore_8";
        left.x = 9_000; // in the avoided left half (cells 0–4 → x < 10 500)
        left.y = 6_000;
        sim.world.apply(left, sim.now);
        KillModule m = new KillModule(sim.ctx, false, true);
        sim.run(m::tick, 5_000);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.CollectBoxRequest), "not picked");
    }
}
