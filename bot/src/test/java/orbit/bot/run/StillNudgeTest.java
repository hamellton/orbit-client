package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.protocol.gen.MoveRequest;
import org.junit.jupiter.api.Test;

/** A kick may come from idling: a ship standing still moves a few metres back and forth. */
class StillNudgeTest {

    @Test
    void aShipStandingForMinutesStepsAsideALittle() throws Exception {
        Sim sim = new Sim(CubikonModuleTest.config(90, false), 26, 10_000, 6_000);
        sim.holdShip = true; // the server keeps it where it is: it never gets anywhere
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setPaused(true); // a manual pause holds the ship: no step then
        sim.run(r::tick, BotRunner.STILL_MAX_MS + 10_000);
        assertEquals(0, sim.sent.stream().filter(p -> p instanceof MoveRequest).count(), "paused: no step");
        r.setPaused(false);
        sim.sent.clear();
        sim.run(r::tick, BotRunner.STILL_MAX_MS + 10_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof MoveRequest m
                && Math.hypot(m.targetX - 10_000, m.targetY - 6_000) <= 320 && Math.hypot(m.targetX - 10_000, m.targetY - 6_000) >= 140), "a short step");
    }
}
