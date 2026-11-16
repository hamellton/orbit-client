package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.Targets;
import orbit.config.BotConfig;
import orbit.protocol.gen.AttackLaserRunCommand;
import org.junit.jupiter.api.Test;

class PassiveNpcTest {

    static BotConfig config() {
        return BotConfig.empty().withNpcs(Map.of(
                "\\\\ Impulse II //", new BotConfig.NpcRule(560, 0, true, Set.of("PASSIVE"), Set.of(), null)));
    }

    @Test
    void aPassiveNpcIsTakenOnlyOnceItShootsTheShip() {
        Sim sim = new Sim(config(), 307, 10_000, 6_000);
        sim.npc(7, "\\\\ Impulse II //", 10_400, 6_000);
        assertNull(Targets.best(sim.config, sim.world.snapshot(sim.now), Double.MAX_VALUE),
                "DarkBot «Passive»: left alone while it does not shoot");

        AttackLaserRunCommand run = new AttackLaserRunCommand();
        run.attackerId = 7;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
        assertEquals(7, Targets.best(sim.config, sim.world.snapshot(sim.now), Double.MAX_VALUE).id());
    }
}
