package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.bot.mod.Attacker;
import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

class AttackerTest {

    static BotConfig config(boolean circle, List<String> fallback, Set<String> flags) {
        return BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, circle, fallback))
                .withNpcs(Map.of("-=[ Target ]=-", new BotConfig.NpcRule(500, 0, true, flags, Set.of(), null)));
    }

    /** Angles (degrees) of the hero round the target over a fight. */
    static double sweep(Sim sim, Attacker a, long ms) {
        double first = Double.NaN;
        double total = 0;
        double last = Double.NaN;
        for (long t = 0; t < ms; t += 100) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
            var h = sim.hero();
            double ang = Math.toDegrees(Math.atan2(h.y() - 6_000, h.x() - 10_000));
            if (!Double.isNaN(last)) {
                double d = ang - last;
                d = (d + 540) % 360 - 180;
                total += d;
            }
            last = ang;
            if (Double.isNaN(first)) {
                first = ang;
            }
        }
        return Math.abs(total);
    }

    @Test
    void circlesRoundTheTargetAtItsRadius() {
        Sim sim = new Sim(config(true, null, Set.of()), 1, 10_500, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        double swept = sweep(sim, a, 30_000);
        assertTrue(swept > 120, "went a good way round: " + swept);
        var h = sim.hero();
        double r = Math.hypot(h.x() - 10_000, h.y() - 6_000);
        assertTrue(r > 380 && r < 620, "about the radius: " + r);
    }

    @Test
    void noCirclingWhenOffOrFlagged() {
        for (BotConfig c : List.of(config(false, null, Set.of()), config(true, null, Set.of("NO_CIRCLE")))) {
            Sim sim = new Sim(c, 1, 10_500, 6_000);
            sim.lasers("ammunition_laser_lcb-10");
            sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
            Attacker a = new Attacker(sim.ctx);
            a.target(sim.world.snapshot(sim.now).ships().get(7));
            assertTrue(sweep(sim, a, 15_000) < 10, "stays put");
        }
    }

    @Test
    void takesTheSpareAmmunitionWhenTheMainRunsOut() {
        BotConfig c = config(false, List.of("ammunition_laser_ucb-100", "ammunition_laser_mcb-25"), Set.of());
        Sim sim = new Sim(c, 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_mcb-50", "ammunition_laser_ucb-100", "ammunition_laser_mcb-25");
        // LCB-10 (the main one) is not on board at all; UCB-100 is the first spare
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        for (int t = 0; t < 80; t++) {
            a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertEquals("ammunition_laser_ucb-100", sim.world.snapshot(sim.now).selected("lasers").id());
    }

    @Test
    void givesUpATargetThatLosesNothingAndDoesNotPickItAgainAtOnce() {
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(7, "-=[ Target ]=-", 10_000, 6_000);
        Attacker a = new Attacker(sim.ctx);
        a.target(sim.world.snapshot(sim.now).ships().get(7));
        boolean kept = true;
        for (int t = 0; t < 400 && kept; t++) { // 40 s; the fake server never takes HP off
            kept = a.tick(true);
            sim.now += 100 * Sim.MS;
        }
        assertTrue(!kept, "dropped after 30 s without damage");
        assertEquals(0, a.targetId());
        assertTrue(a.gaveUp(7));
        sim.now += (Attacker.GIVE_UP_MS + 1_000) * Sim.MS;
        assertTrue(!a.gaveUp(7), "after a while it may be tried again");
    }

    @Test
    void anyLaserAmmunitionWhenNeitherTheMainNorASpareIsOnBoard() {
        Sim sim = new Sim(config(false, null, Set.of()), 1, 10_400, 6_000);
        sim.lasers("ammunition_laser_mcb-25", "ammunition_laser_sab-50");
        // the config wants LCB-10, none on board, no spares: MCB-25, not SAB
        assertEquals("ammunition_laser_mcb-25", Attacker.usable(sim.ctx.config().get(), sim.world.snapshot(sim.now),
                "ammunition_laser_lcb-10"));
    }
}
