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

    @Test
    void inTheGateAstraBotsFormationsTheFightsModeAndVeteranOnTheLastStage() throws Exception {
        // the user, 2026-11-15: «как AstraBot: Wheel на Streuner, Lordakia, Saimon, Drill на Kristallin; конец врат —
        // конфигурация 2 и Veteran»
        BotConfig base = config("""
                {"gates":["alpha"],"useEndGateConfig":true,"endGateConfig":2,"endGateFormation":"drone_formation_f-3d-vt",
                 "npcFormations":{"streuner":"drone_formation_f-3d-wl","kristallin":"drone_formation_f-3d-dr"}}""");
        BotConfig.General g = base.general();
        base = base.withGeneral(new BotConfig.General(g.workingMap(), new BotConfig.ShipMode(1, "drone_formation_f-3d-dr"),
                new BotConfig.ShipMode(1, "drone_formation_f-3d-dr"), new BotConfig.ShipMode(2, "drone_formation_f-3d-rg"),
                false, false, 0, false, false, true, true));
        Sim sim = new Sim(base, 51, 10_000, 6_500);
        info(sim, 0, new int[] {1, 0, 5});
        sim.npc(900, "-=[ Streuner ]=- α1", 12_000, 6_500);
        GateModule m = new GateModule(sim.ctx);
        BotConfig c = m.config();
        assertEquals("drone_formation_f-3d-wl", c.npc("-=[ Streuner ]=- α1").formation());
        assertFalse(c.general().runModeTravel(), "flown to in the fight's mode");
        assertFalse(c.general().runModeChase());
        assertEquals(1, c.general().offensive().config());
        orbit.protocol.gen.GalaxyGateBuilderGateWaveUpdateCommand wave = new orbit.protocol.gen.GalaxyGateBuilderGateWaveUpdateCommand();
        wave.gateId = 1;
        wave.currWave = 37;
        sim.world.apply(wave, sim.now);
        c = m.config();
        assertEquals(new BotConfig.ShipMode(2, "drone_formation_f-3d-vt"), c.general().offensive(), "the last stage");
        assertEquals(null, c.npc("-=[ Streuner ]=- α1").formation(), "the end's formation wins");
    }

    @Test
    void theSpinsGoToTheGateNearestItsPartsNotTheFirstOfTheList() throws Exception {
        // live 2026-11-15 on gb2: α done, α spun from 0/34 while β had 47/48
        Sim sim = new Sim(config("{\"gates\":[\"alpha\",\"beta\",\"gamma\"],\"build\":true}"), home(), 10_000, 6_000);
        info(sim, 50, new int[] {1, 0, -1}, new int[] {2, 47, -1}, new int[] {3, 6, -1});
        GateModule m = new GateModule(sim.ctx);
        run(sim, m, 2_000);
        var spins = sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderMultiEnergyRequest)
                .map(p -> (int) ((orbit.protocol.gen.GalaxyGateBuilderMultiEnergyRequest) p).gateId).toList();
        assertFalse(spins.isEmpty());
        assertTrue(spins.stream().allMatch(id -> id == 2), "β: " + spins);
    }

    static void kristallins(Sim sim, int cx, int cy, int spread) {
        for (int i = 0; i < 5; i++) {
            sim.npc(800 + i, "-=[ Kristallin ]=- α2" + (i % 4 + 1), cx + (i - 2) * spread, cy);
            orbit.protocol.gen.AttackLaserRunCommand shot = new orbit.protocol.gen.AttackLaserRunCommand();
            shot.attackerId = 800 + i;
            shot.targetId = 1;
            sim.world.apply(shot, sim.now);
        }
    }

    static void petHp(Sim sim, int hp, int max) {
        orbit.protocol.gen.PetHitpointsUpdateCommand u = new orbit.protocol.gen.PetHitpointsUpdateCommand();
        u.hitpointsNow = hp;
        u.hitpointsMax = max;
        sim.world.apply(u, sim.now);
    }

    @Test
    void kamikazeAsDarkBotGathersTheFlaggedOnesThenStopsAndBlowsWhenTheyAreTight() throws Exception {
        // the user, 2026-11-15: «камикадзе как у даркбота 1 в 1, и поведение корабля тоже» (SharedPlugin KamikazeHandler)
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"],\"kamikaze\":{\"enabled\":true,\"minNpcs\":5}}")
                .withPet(new BotConfig.Pet(true, "GUARD")), 51, 8_500, 6_200);
        PetCareTest.owned(sim);
        PetCareTest.launched(sim);
        petHp(sim, 1_000, 10_000); // under 19 %: the pet can blow
        GateModule m = new GateModule(sim.ctx);
        kristallins(sim, 8_500, 5_000, 250); // spread 1 000: not tight yet
        run(sim, m, 300);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_PASSIVE, m.petGear(), "gathering: the pet passive");
        assertEquals(0, m.fightTarget(), "no shooting at them");
        assertTrue(m.status().contains("камикадзе"), m.status());
        for (int i = 0; i < 5; i++) {
            orbit.protocol.gen.ShipRemoveCommand gone = new orbit.protocol.gen.ShipRemoveCommand();
            gone.userId = 800 + i;
            sim.world.apply(gone, sim.now);
        }
        kristallins(sim, 8_500, 5_000, 50); // spread 200: tight
        run(sim, m, 300);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_KAMIKAZE, m.petGear(), "tight and full hull: the blast");
        // the nearest one locked (selected) as DarkBot does before the gear, never shot at
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.ShipSelectRequest r && r.targetId >= 800
                && r.targetId < 805), "one of them locked");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.AttackLaserRequest), "no shooting");
    }

    @Test
    void aloneTheShipBringsItsPetUnderNineteenPercentWithRocketsFirst() throws Exception {
        // SharedPlugin petReadyForKamikaze: no group and the pet at full HP — lock the own pet and launch rockets at it
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"],\"kamikaze\":{\"enabled\":true,\"minNpcs\":5}}")
                .withPet(new BotConfig.Pet(true, "GUARD")), 51, 8_500, 6_200);
        PetCareTest.owned(sim);
        PetCareTest.launched(sim);
        petHp(sim, 10_000, 10_000);
        GateModule m = new GateModule(sim.ctx);
        kristallins(sim, 8_500, 5_000, 50); // tight already
        run(sim, m, 2_000);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_PASSIVE, m.petGear(), "the pet too healthy to blow");
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.AttackRocketRequest r && r.targetId == 500),
                "rockets at the own pet");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.ShipSelectRequest r && r.targetId >= 800
                && r.targetId < 805), "no NPC locked before all is ready");
        petHp(sim, 1_500, 10_000);
        run(sim, m, 300);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_KAMIKAZE, m.petGear(), "under 19 %: the blast");
    }

    @Test
    void flaggedOnesNotAimingAreFoughtAsUsualAsInDarkBot() throws Exception {
        // SharedPlugin counts only the ones aiming at the ship; no «wave of flagged ones» guess
        Sim sim = new Sim(config("{\"gates\":[\"beta\"],\"kamikaze\":{\"enabled\":true,\"minNpcs\":5}}")
                .withPet(new BotConfig.Pet(true, "GUARD")), 52, 17_000, 11_000);
        sim.lasers("ammunition_laser_lcb-10");
        PetCareTest.owned(sim);
        PetCareTest.launched(sim);
        GateModule m = new GateModule(sim.ctx);
        for (int i = 0; i < 5; i++) {
            sim.npc(800 + i, "-=[ Kristallin ]=- β2" + (i % 4 + 1), 18_000 + i * 200, 12_000);
        }
        run(sim, m, 3_000);
        assertTrue(m.fightTarget() >= 800 && m.fightTarget() < 805, "fought: " + m.fightTarget());
    }

    @Test
    void fewerThanFiveFlaggedOnesTheFightGoesOnAsUsual() throws Exception {
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"],\"kamikaze\":{\"enabled\":true,\"minNpcs\":5}}")
                .withPet(new BotConfig.Pet(true, "GUARD")), 51, 8_500, 6_200);
        sim.lasers("ammunition_laser_lcb-10");
        PetCareTest.owned(sim);
        PetCareTest.launched(sim);
        GateModule m = new GateModule(sim.ctx);
        sim.npc(900, "-=[ Kristallin ]=- α21", 8_700, 6_200);
        run(sim, m, 2_000);
        assertEquals(900, m.fightTarget());
    }

    @Test
    void twoLivesLeftOneMoreIsBoughtWhileTheUridiumLasts() throws Exception {
        // the user, 2026-11-14: «если осталось 2 жизни, то докупаем за уридиум ещё одну, если урика хватает»
        Sim sim = new Sim(config("{\"gates\":[\"alpha\"],\"buyLives\":true}"), home(), 10_000, 6_000);
        info(sim, 0, new int[] {1, 0, 2});
        GateModule m = new GateModule(sim.ctx);
        run(sim, m, 1_000);
        assertEquals(1, sim.sent.stream().filter(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderBuyLifeRequest r
                && r.gateId == 1).count());
        Sim enough = new Sim(config("{\"gates\":[\"alpha\"],\"buyLives\":true}"), home(), 10_000, 6_000);
        info(enough, 0, new int[] {1, 0, 3});
        run(enough, new GateModule(enough.ctx), 1_000);
        assertTrue(enough.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderBuyLifeRequest),
                "three: in stock");
        Sim off = new Sim(config("{\"gates\":[\"alpha\"]}"), home(), 10_000, 6_000);
        info(off, 0, new int[] {1, 0, 1});
        run(off, new GateModule(off.ctx), 1_000);
        assertTrue(off.sent.stream().noneMatch(p -> p instanceof orbit.protocol.gen.GalaxyGateBuilderBuyLifeRequest),
                "off by default");
    }

    @Test
    void spinsByDarkBotsProgressRulesOrAstraBotsFixedAmount() throws Exception {
        var s = Json.MAPPER.readTree("{\"spins100\":0.2,\"spins10\":0.6,\"spins5\":0.9}");
        assertEquals(100, GateModule.spinOption(s, 0.1)[0]);
        assertEquals(10, GateModule.spinOption(s, 0.5)[0]);
        assertEquals(5, GateModule.spinOption(s, 0.8)[0]);
        assertEquals(1, GateModule.spinOption(s, 0.95)[0]);
        assertEquals(10, GateModule.spinOption(Json.MAPPER.readTree("{\"spinAmount\":10}"), 0.1)[0]);
        var multi = Json.MAPPER.readTree("{\"useMultiAt\":2}");
        assertFalse(GateModule.useMultiplier(multi, 0));
        assertTrue(GateModule.useMultiplier(multi, 1), "the window shows x2");
        assertFalse(GateModule.useMultiplier(Json.MAPPER.readTree("{}"), 5));
        WorldSnapshotGates g = new WorldSnapshotGates(0, 300_000, 100);
        assertFalse(GateModule.canSpin(Json.MAPPER.readTree("{}"), g.v, 1), "extra energy only by default");
        assertTrue(GateModule.canSpin(Json.MAPPER.readTree("{\"extraEnergyOnly\":false,\"minUridium\":1000}"), g.v, 1));
        assertFalse(GateModule.canSpin(Json.MAPPER.readTree("{\"extraEnergyOnly\":false,\"maxSpinCost\":50}"), g.v, 1),
                "a spin dearer than maxSpinCost");
    }

    /** A builder state with only energy, uridium and a spin's price. */
    record WorldSnapshotGates(orbit.world.WorldSnapshot.Gates v) {
        WorldSnapshotGates(double energy, double money, int cost) {
            this(new orbit.world.WorldSnapshot.Gates(java.util.Map.of(), money, energy, cost, List.of(), 1, 1, 0, 0, 0, 0, 0,
                    orbit.world.WorldSnapshot.Trinity.NONE));
        }
    }
}
