package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.bot.mod.GateModule;
import orbit.protocol.gen.EternalBlacklightBoosterCommand;
import orbit.protocol.gen.EternalBlacklightBoosterInfoCommand;
import orbit.protocol.gen.EternalBlacklightChooseOptionRequest;
import orbit.protocol.gen.EternalBlacklightCpuInfoCommand;
import orbit.protocol.gen.EternalBlacklightSkillPointInfoCommand;
import orbit.protocol.gen.EternalBlacklightWaveInfoCommand;
import orbit.protocol.gen.MenuActionRequest;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-16: EBG «1 в 1 как DarkBot» (SharedPlugin EternalBlacklightGate) with the runs, the CPU's craft. */
class EternalGateTest {

    static void cpus(Sim sim, int n) {
        EternalBlacklightCpuInfoCommand c = new EternalBlacklightCpuInfoCommand();
        c.cpuCount = n;
        sim.now = Math.max(sim.now, 1_000 * Sim.MS);
        sim.world.apply(c, sim.now);
    }

    static void wave(Sim sim, int wave) {
        EternalBlacklightWaveInfoCommand c = new EternalBlacklightWaveInfoCommand();
        c.currentWave = wave;
        sim.world.apply(c, sim.now);
    }

    @Test
    void onBlTheCpuOpensThePortalAndTheShipGoesIn() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"],\"eternal\":{\"map\":\"1-BL\"}}"), 306, 10_000, 6_000);
        cpus(sim, 2);
        GateModule m = new GateModule(sim.ctx);
        assertEquals(306, m.workingMap());
        GateModuleTest.run(sim, m, 1_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof MenuActionRequest r
                && "ammunition_ggportal_eternal-blacklight-cpu".equals(r.menuItemId)), "the CPU used");
        GateModuleTest.portal(sim, 700, 54, 12_000, 6_000);
        GateModuleTest.run(sim, m, 15_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 12_000, h.y() - 6_000) < 500, "at the portal: " + h.x() + "/" + h.y());
    }

    @Test
    void noCpuAndNoCraftTheAccountLogsOut() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"],\"eternal\":{\"whenOut\":\"logout\"}}"), 26,
                10_000, 6_000);
        cpus(sim, 0);
        GateModule m = new GateModule(sim.ctx);
        m.tick();
        assertNotNull(m.stopRequest(), "out of CPUs: home and out");
        Sim waits = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"],\"eternal\":{\"whenOut\":\"wait\"}}"), 26,
                10_000, 6_000);
        cpus(waits, 0);
        GateModule w = new GateModule(waits.ctx);
        w.tick();
        assertNull(w.stopRequest());
    }

    @Test
    void theLasersEnrichedFirst() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"],\"eternal\":{\"lasersMin\":50000}}"), 26,
                10_000, 6_000);
        cpus(sim, 3);
        EnricherTest.lab(sim, new int[] {0, 6, 20_000});
        GateModule m = new GateModule(sim.ctx);
        m.tick();
        assertEquals(26, m.workingMap(), "stays till enriched");
        assertTrue(m.status().contains("жду обогащения лазеров"), m.status());
        EnricherTest.lab(sim, new int[] {0, 6, 80_000});
        m.tick();
        assertEquals(306, m.workingMap(), "then to the BL map");
    }

    @Test
    void betweenWavesTheBoosterOfTheLowestPriority() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"]}"), 462, 10_000, 6_000);
        cpus(sim, 1);
        wave(sim, 5);
        EternalBlacklightBoosterInfoCommand b = new EternalBlacklightBoosterInfoCommand();
        b.option1 = booster(EternalBlacklightBoosterCommand.BOOSTERTYPE_SHIELD, 10);
        b.option2 = booster(EternalBlacklightBoosterCommand.BOOSTERTYPE_HITPOINTS, 6);
        b.option3 = booster(EternalBlacklightBoosterCommand.BOOSTERTYPE_DAMAGE, 4);
        b.skillPointInfo = new EternalBlacklightSkillPointInfoCommand();
        b.skillPointInfo.skillPoint = 1;
        sim.world.apply(b, sim.now);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.run(sim, m, 5_000);
        EternalBlacklightChooseOptionRequest r = sim.sent.stream().filter(p -> p instanceof EternalBlacklightChooseOptionRequest)
                .map(p -> (EternalBlacklightChooseOptionRequest) p).findFirst().orElseThrow();
        assertEquals(3, r.choosenOption, "damage (priority 0) over hull and shield");
    }

    @Test
    void theBrakeExitTakesTheHomePortalBetweenWaves() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"],\"eternal\":{\"brakeOnWave\":12,"
                + "\"brakeAction\":\"EXIT\"}}"), 462, 10_000, 6_000);
        cpus(sim, 1);
        wave(sim, 12);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.portal(sim, 800, 1, 4_000, 6_000);
        GateModuleTest.portal(sim, 801, 54, 16_000, 6_000);
        GateModuleTest.run(sim, m, 30_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 4_000, h.y() - 6_000) < 500, "home, not the next stage: " + h.x() + "/" + h.y());
    }

    @Test
    void withoutTheBrakeTheHomePortalIsNeverTaken() throws Exception {
        Sim sim = new Sim(GateModuleTest.config("{\"gates\":[\"eternal\"]}"), 462, 10_000, 6_000);
        cpus(sim, 1);
        wave(sim, 12);
        GateModule m = new GateModule(sim.ctx);
        GateModuleTest.portal(sim, 800, 1, 4_000, 6_000);
        GateModuleTest.run(sim, m, 30_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 4_000, h.y() - 6_000) > 1_000, "the only portal home is left: " + h.x() + "/" + h.y());
    }

    private static EternalBlacklightBoosterCommand booster(int type, int value) {
        EternalBlacklightBoosterCommand c = new EternalBlacklightBoosterCommand();
        c.boosterType = (short) type;
        c.value = value;
        return c;
    }
}
