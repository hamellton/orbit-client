package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import orbit.bot.mod.GateModule;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.GalaxyGateBuilderGateModule;
import orbit.protocol.gen.GalaxyGateBuilderInfoCommand;
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.JumpgateCreateCommand;
import orbit.protocol.gen.MapAssetActionAvailableCommand;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-14: «модель гелекси гейта по примеру даркбота и астработа — сначала абг, зеты, коппы, трініті». */
class GateModuleTest {

    static BotConfig config(String json) throws Exception {
        ObjectNode s = (ObjectNode) Json.MAPPER.readTree(json);
        BotConfig c = BotConfig.empty();
        c = new BotConfig(c.schema(), "", BotConfig.Module.GALAXY_GATE, c.general(), c.npcs(), c.loot(), c.collect(),
                c.pet(), c.safety(), c.zones(), c.safetySpots(), c.modules(), c.extra(), c.buy(), c.session(), c.flee(),
                c.group());
        return c.withModuleSettings(BotConfig.Module.GALAXY_GATE.key(), s);
    }

    static int home() {
        return MapCatalog.bundled().byName("3-1").id();
    }

    /** The builder's answer: each {id, parts, lives}. */
    static void info(Sim sim, double energy, int[]... gates) {
        sim.now = Math.max(sim.now, 1_000 * Sim.MS); // the game's clock is never 0
        GalaxyGateBuilderInfoCommand c = new GalaxyGateBuilderInfoCommand();
        c.money = 300_000;
        c.samples = energy;
        c.energy_cost = 100;
        c.spinamount_selected = 1;
        List<GalaxyGateBuilderGateModule> list = new ArrayList<>();
        for (int[] g : gates) {
            GalaxyGateBuilderGateModule m = new GalaxyGateBuilderGateModule();
            m.id = (byte) g[0];
            m.currPart = (short) g[1];
            m.livesLeft = g[2];
            m.lifePrice = g[2] > 0 ? 10_000 : -1;
            m.mode = switch (g[0]) {
                case 1 -> "alpha";
                case 2 -> "beta";
                case 3 -> "gamma";
                default -> "gate" + g[0];
            };
            list.add(m);
        }
        c.gates = list;
        sim.world.apply(c, sim.now);
    }

    static void portal(Sim sim, int id, int design, int x, int y) {
        JumpgateCreateCommand p = new JumpgateCreateCommand();
        p.gateId = id;
        p.designId = design;
        p.x = x;
        p.y = y;
        sim.world.apply(p, sim.now);
    }

    /** Ticks the module, offering a portal's jump once the ship is close (as the server). */
    static void run(Sim sim, GateModule m, long ms) {
        for (long t = 0; t < ms; t += 100) {
            m.tick();
            var w = sim.world.snapshot(sim.now);
            for (var p : w.portals().values()) {
                boolean near = Math.hypot(p.x() - w.hero().x(), p.y() - w.hero().y()) < 500;
                if (near != w.activatable().containsKey(p.id())) {
                    MapAssetActionAvailableCommand a = new MapAssetActionAvailableCommand();
                    a.mapAssetId = p.id();
                    a.activatable = near;
                    sim.world.apply(a, sim.now);
                }
            }
            sim.now += 100 * Sim.MS;
        }
    }

    @Test
    void aPlacedGateIsEnteredByItsPortalOnX1() throws Exception {
        Sim sim = new Sim(config("{\"gates\":[\"alpha\",\"beta\"]}"), home(), 10_000, 6_000);
        info(sim, 0, new int[] {1, 5, -1}, new int[] {2, 0, 3});
        portal(sim, 500, 2, 9_000, 6_000); // α's portal: α is not placed, β is
        portal(sim, 501, 3, 11_500, 6_000);
        GateModule m = new GateModule(sim.ctx);
        assertEquals(home(), m.workingMap());
        run(sim, m, 20_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest), "jumped");
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 11_500, h.y() - 6_000) < 500, "at β's portal: " + h.x() + "/" + h.y());
    }

    @Test
    void allPartsThereThePlaceIsAskedFor() throws Exception {
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"]}"), home(), 10_000, 6_000);
        info(sim, 0, new int[] {1, 34, -1});
        GateModule m = new GateModule(sim.ctx);
        run(sim, m, 2_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderSetupGateRequest r
                && r.gateId == 1), "«prepare jump» for α");
    }

    @Test
    void partsMissingItSpinsWithTheFreeEnergyOnly() throws Exception {
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"],\"build\":true}"), home(), 10_000, 6_000);
        info(sim, 50, new int[] {1, 5, -1});
        GateModule m = new GateModule(sim.ctx);
        for (int i = 0; i < 50; i++) {
            int before = sim.sent.size();
            run(sim, m, 100);
            if (sim.sent.subList(before, sim.sent.size()).stream()
                    .anyMatch(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderMultiEnergyRequest)) {
                orbit.protocol.gen.GalaxyGateBuilderMultiEnergyCommand a = new orbit.protocol.gen.GalaxyGateBuilderMultiEnergyCommand();
                a.gateMode = "alpha";
                a.samples = 49;
                a.money = 300_000;
                a.spinamount_selected = 1;
                a.gatePartIds = new ArrayList<>(List.of((short) 1, (short) 2, (short) 3, (short) 4, (short) 5, (short) 6));
                sim.world.apply(a, sim.now);
            }
        }
        assertEquals(6, sim.world.snapshot(sim.now).gates().gate(1).parts(), "the parts by the spin's answer");
        long spins = sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderMultiEnergyRequest r
                && r.gateId == 1).count();
        assertTrue(spins >= 3, "spins: " + spins);
        Sim poor = new Sim(config("{\"gates\":[\"alpha\"],\"build\":true}"), home(), 10_000, 6_000);
        info(poor, 0, new int[] {1, 5, -1});
        GateModule m2 = new GateModule(poor.ctx);
        run(poor, m2, 5_000);
        assertTrue(poor.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderMultiEnergyRequest),
                "no energy, uridium kept");
    }

    @Test
    void inTheGateItsNpcsAreFoughtWithoutAConfigRule() throws Exception {
        int alpha = MapCatalog.bundled().byName("GG α") != null ? MapCatalog.bundled().byName("GG α").id() : 51;
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"]}"), alpha, 10_000, 6_500);
        sim.lasers("ammunition_laser_lcb-10");
        sim.npc(900, "-=[ Streuner ]=- α1", 10_600, 6_500);
        GateModule m = new GateModule(sim.ctx);
        assertEquals(alpha, m.workingMap(), "in a gate: no travel");
        run(sim, m, 3_000);
        assertEquals(900, m.fightTarget());
    }

    @Test
    void theGateCompletedItGoesHomeByThePortal() throws Exception {
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"]}"), 51, 10_000, 6_500);
        info(sim, 0, new int[] {1, 0, 2});
        GateModule m = new GateModule(sim.ctx);
        run(sim, m, 1_000);
        portal(sim, 600, 2, 12_000, 6_500); // a stage portal is not taken once the gate is done
        portal(sim, 601, 1, 8_000, 6_500);
        orbit.protocol.gen.GalaxyGateBuilderGateCompleteCommand done = new orbit.protocol.gen.GalaxyGateBuilderGateCompleteCommand();
        done.gateId = 1;
        sim.world.apply(done, sim.now);
        run(sim, m, 20_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 8_000, h.y() - 6_500) < 500, "at the home portal: " + h.x() + "/" + h.y());
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof JumpRequest));
    }

    @Test
    void betweenStagesTheGatesPortalIsTaken() throws Exception {
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"]}"), 51, 10_000, 6_500);
        info(sim, 0, new int[] {1, 0, 3});
        GateModule m = new GateModule(sim.ctx);
        portal(sim, 600, 2, 12_000, 6_500);
        portal(sim, 601, 1, 8_000, 6_500);
        run(sim, m, 20_000);
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 12_000, h.y() - 6_500) < 500, "the stage portal, not home: " + h.x() + "/" + h.y());
        assertFalse(Math.hypot(h.x() - 8_000, h.y() - 6_500) < 500);
    }
}
