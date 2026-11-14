package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.BehemothModule;
import orbit.bot.mod.SpawnMemory;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.ShipSelectRequest;
import org.junit.jupiter.api.Test;

class BehemothModuleTest {

    static final String BEHEMOTH = "\\\\ Mindfire Behemoth //";

    static BotConfig config(int changeMapSeconds) throws Exception {
        return BotConfig.empty()
                .withModule(BotConfig.Module.BEHEMOTH)
                .withGeneral(new BotConfig.General(308, null, null, null, false, false, 300, false, false))
                .withLoot(new BotConfig.Loot("ammunition_laser_ucb-100", null, 0, 0, null, true, 0))
                .withNpcs(Map.of(
                        BEHEMOTH, new BotConfig.NpcRule(590, -100, true, Set.of("IGNORE_ATTACKED", "HP_LINK"),
                                Set.of(306, 307, 308), "ammunition_laser_a-bl"),
                        "\\\\ Impulse II //", new BotConfig.NpcRule(590, 0, false, Set.of("PASSIVE"), Set.of(306, 307, 308), null)))
                .withModuleSettings("behemoth", Json.MAPPER.readTree("""
                        {"maps": [306, 307, 308], "changeMapSeconds": %d, "collectBox": true, "collectBoxConfig": 2,
                         "collectBoxFormation": "Veteran", "defenceMode": true, "killOthers": false}"""
                        .formatted(changeMapSeconds)));
    }

    @Test
    void waitsNearTheSpawnOfTheMapItIsOn() throws Exception {
        Sim sim = new Sim(config(30), 308, 10_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        assertEquals(308, m.workingMap(), "nothing known anywhere: the map it is on, no flight");
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 300, "at 3-BL's wait point: " + h);
        assertTrue(m.status().startsWith("Жду бегемота"), m.status());
    }

    @Test
    void noBehemothForTheWaitThenAnotherMap() throws Exception {
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 20_000);
        assertEquals(308, m.workingMap(), "still waiting");
        sim.run(m::tick, 20_000);
        assertTrue(m.workingMap() != 308, "30 s and none: another BL map, " + m.workingMap());
    }

    @Test
    void goesWhereTheBehemothIsDueSoonest() throws Exception {
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        long now = System.currentTimeMillis();
        mem.killed(308, 4_700, 3_900, now - 60_000); // here: back in 14 min
        mem.killed(306, 19_400, 5_800, now - 14 * 60_000); // 1-BL: back in a minute
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        assertEquals(306, m.workingMap());
    }

    @Test
    void fightsTheBehemothWhoeverOwnsItThenItsBoxThenTheNextMap() throws Exception {
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100", "ammunition_laser_a-bl");
        SpawnMemory mem = SpawnMemory.inMemory();
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        m.workingMap();
        sim.run(m::tick, 1_000);
        sim.npc(40, "\\\\ Impulse II //", 5_900, 2_700);
        sim.npc(77, "\\\\ Mindfire Behemoth //", 4_700, 3_900);
        sim.run(m::tick, 15_000);
        assertEquals(77, sim.hero().selected(), m.status());
        assertTrue(m.busy());
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK, m.petGear(), "HP Link by its row");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest s && s.targetId == 40), "Impulse never clicked");
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.run(m::tick, 2_000);
        assertTrue(m.status().contains("ящик"), m.status());
        assertEquals(1, mem.spawns(308).size());
        assertTrue(mem.spawns(308).getFirst().lastKill() > 0, "its death remembered");
        sim.run(m::tick, 30_000);
        assertTrue(m.workingMap() != 308, "here it is back in 15 min: another map first, " + m.workingMap());
    }

    @Test
    void thePetIsInUntilTheBehemothThenTheBeaconAndHpLinkWhenTheHullIsLow() throws Exception {
        // AstraBot: petOnlyOnBehemoth, usePetCombatBeacon, PET hpLinkMinPercent 65 (the user, 2026-11-13)
        BotConfig c = config(300);
        var s = c.moduleSettings("behemoth");
        s.put("petOnlyOnBehemoth", true);
        s.put("usePetCombatBeacon", true);
        s.put("hpLinkPercent", 65);
        Sim sim = new Sim(c.withModuleSettings("behemoth", s), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_a-bl");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 1_000);
        assertEquals(Boolean.FALSE, m.petOut(), "no behemoth: in");
        sim.npc(77, "\\\\ Mindfire Behemoth //", 5_000, 3_000);
        sim.run(m::tick, 3_000);
        assertEquals(Boolean.TRUE, m.petOut());
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_COMBAT_BEACON, m.petGear(), "full hull: the beacon");
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 77;
        hit.victimId = 1;
        hit.victimHitpoints = sim.world.snapshot(sim.now).hero().health().hpMax() * 0.5;
        sim.world.apply(hit, sim.now);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_HP_LINK, m.petGear(), "below 65 %: HP Link");
    }

    /** A player of another faction (ours is 3). */
    static void enemy(Sim sim, int id, String name, int x, int y) {
        orbit.protocol.gen.ShipCreateCommand c = new orbit.protocol.gen.ShipCreateCommand();
        c.userId = id;
        c.userName = name;
        c.factionId = 1;
        c.x = x;
        c.y = y;
        sim.world.apply(c, sim.now);
    }

    static void shoots(Sim sim, int attacker, int target) {
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = attacker;
        run.targetId = target;
        sim.world.apply(run, sim.now);
    }

    static BotConfig with(BotConfig c, String json) throws Exception {
        var s = c.moduleSettings("behemoth");
        Json.MAPPER.readTree(json).properties().forEach(e -> s.set(e.getKey(), e.getValue()));
        return c.withModuleSettings("behemoth", s);
    }

    @Test
    void aTrainByTheBehemothIsNotRunFromAndTheShipStandsOnTheFarSide() throws Exception {
        // CARNAGE's BLCycle: their attacks ignored, avoidEnemyOrbit (the user, 2026-11-13)
        Sim sim = new Sim(with(config(300), "{\"trainMode\": true, \"avoidEnemyOrbit\": true}"), 308, 8_000, 5_700);
        sim.lasers("ammunition_laser_a-bl");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.npc(77, BEHEMOTH, 8_000, 5_000);
        for (int i = 0; i < 3; i++) {
            enemy(sim, 900 + i, "Train" + i, 7_000, 4_900 + i * 100);
            shoots(sim, 900 + i, 1);
        }
        sim.run(m::tick, 20_000);
        var h = sim.hero();
        assertEquals(77, h.selected(), m.status());
        assertTrue(h.x() > 8_300, "east of the behemoth, away from the train in the west: " + h);
        assertTrue(m.status().contains("другой стороны"), m.status());
    }

    @Test
    void inTheTrainModeTheBehemothsFightHoldsItsGroundAgainstTheOffendersCircles() throws Exception {
        // live 2026-11-13 on the VPS: «DoWarBoy рядом — ухожу на другую сторону» in the behemoth's fight, the train
        // config says to stay — the runner leaves the offenders' circles alone while the module holds its ground
        Sim sim = new Sim(with(config(300), "{\"trainMode\": true}"), 308, 8_000, 5_600);
        sim.lasers("ammunition_laser_a-bl");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 1_000);
        assertTrue(!m.holdsGround(), "no behemoth: the usual keeping off");
        sim.npc(77, BEHEMOTH, 8_000, 5_000);
        sim.run(m::tick, 2_000);
        assertTrue(m.holdsGround(), "the behemoth's fight in the train mode");

        Sim plain = new Sim(config(300), 308, 8_000, 5_600);
        BehemothModule p = new BehemothModule(plain.ctx, SpawnMemory.inMemory());
        p.workingMap();
        plain.npc(77, BEHEMOTH, 8_000, 5_000);
        plain.run(p::tick, 2_000);
        assertTrue(!p.holdsGround(), "without the train mode: as before");
    }

    @Test
    void moreEnemiesThanTheLimitSendTheShipToAnotherMap() throws Exception {
        Sim sim = new Sim(with(config(300), "{\"trainMode\": true, \"trainEnemyLimit\": 2}"), 308, 8_000, 5_700);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.npc(77, BEHEMOTH, 8_000, 5_000);
        for (int i = 0; i < 3; i++) {
            enemy(sim, 900 + i, "Train" + i, 7_500, 5_000 + i * 100);
        }
        sim.run(m::tick, 2_000);
        assertTrue(m.workingMap() != 308, "three against a limit of two: another map, " + m.workingMap());
    }

    @Test
    void diesTooOftenByTheBehemothThenAnotherMap() throws Exception {
        // CARNAGE's DEATH_COUNT
        Sim sim = new Sim(with(config(300), "{\"trainMode\": true, \"deathsPerSpawn\": 2}"), 308, 8_000, 5_700);
        sim.lasers("ammunition_laser_a-bl");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.npc(77, BEHEMOTH, 8_000, 5_000);
        sim.run(m::tick, 2_000);
        sim.ctx.stats().deaths.incrementAndGet();
        m.observe();
        assertEquals(308, m.workingMap(), "one death: on");
        sim.run(m::tick, 2_000);
        sim.ctx.stats().deaths.incrementAndGet();
        m.observe();
        assertTrue(m.workingMap() != 308, "two: the map is skipped, " + m.workingMap());
    }

    @Test
    void playersAreLeftAloneUnlessTheConfigSaysAndThenAShooterIsAnswered() throws Exception {
        Sim off = new Sim(config(300), 308, 5_700, 2_600);
        BehemothModule quiet = new BehemothModule(off.ctx, SpawnMemory.inMemory());
        quiet.workingMap();
        enemy(off, 900, "Shooter", 6_000, 2_700);
        shoots(off, 900, 1);
        off.run(quiet::tick, 4_000);
        assertTrue(off.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest s && s.targetId == 900), "off: left alone");

        Sim sim = new Sim(with(config(300), "{\"respondPlayers\": true, \"respondRadius\": 600}"), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        enemy(sim, 900, "Shooter", 6_000, 2_700);
        enemy(sim, 901, "Passer", 5_900, 2_500);
        shoots(sim, 900, 1);
        sim.run(m::tick, 4_000);
        assertEquals(900, sim.hero().selected(), m.status());
    }

    @Test
    void huntingWaitsForTheBehemothUnlessEnemiesComeFirst() throws Exception {
        String hunt = "{\"huntEnemies\": true, \"huntRadius\": 1500}";
        Sim sim = new Sim(with(config(300), hunt), 308, 8_000, 5_700);
        sim.lasers("ammunition_laser_a-bl");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.npc(77, BEHEMOTH, 8_000, 5_000);
        enemy(sim, 900, "Enemy", 8_300, 5_800);
        sim.run(m::tick, 5_000);
        assertEquals(77, sim.hero().selected(), "the behemoth first");

        Sim first = new Sim(with(config(300), "{\"huntEnemies\": true, \"enemyFocus\": \"ENEMY\"}"), 308, 8_000, 5_700);
        BehemothModule e = new BehemothModule(first.ctx, SpawnMemory.inMemory());
        e.workingMap();
        first.npc(77, BEHEMOTH, 8_000, 5_000);
        enemy(first, 900, "Enemy", 8_300, 5_800);
        first.run(e::tick, 5_000);
        assertEquals(900, first.hero().selected(), "enemies first");
    }

    static void groupWithLeader(Sim sim, int leaderMap, int lx, int ly) {
        orbit.protocol.gen.GroupInitializationCommand g = new orbit.protocol.gen.GroupInitializationCommand();
        g.id = 9;
        g.leaderId = 50;
        for (int id : new int[] {1, 50}) {
            orbit.protocol.gen.GroupMemberInitializationCommand mm = new orbit.protocol.gen.GroupMemberInitializationCommand();
            mm.id = id;
            mm.name = id == 1 ? "me" : "Boss";
            mm.location = new orbit.protocol.gen.LocationModule();
            mm.location.mapId = id == 1 ? 308 : leaderMap;
            mm.location.x = id == 1 ? 5_700 : lx;
            mm.location.y = id == 1 ? 2_600 : ly;
            g.members.add(mm);
        }
        sim.world.apply(g, sim.now);
    }

    @Test
    void followsTheGroupLeaderToHisMapAndStaysCloseOnOurs() throws Exception {
        // AstraBot's followLeader / leaderId
        Sim sim = new Sim(with(config(300), "{\"followLeader\": true, \"leader\": \"\", \"followDistance\": 400}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        groupWithLeader(sim, 307, 6_000, 6_000);
        m.workingMap();
        sim.run(m::tick, 500);
        assertEquals(307, m.workingMap(), "the leader is on 2-BL");

        Sim here = new Sim(with(config(300), "{\"followLeader\": true, \"leader\": \"Boss\"}"), 308, 5_700, 2_600);
        BehemothModule f = new BehemothModule(here.ctx, SpawnMemory.inMemory());
        groupWithLeader(here, 308, 9_000, 6_000);
        f.workingMap();
        here.run(f::tick, 20_000);
        var h = here.hero();
        assertTrue(Math.hypot(h.x() - 9_000, h.y() - 6_000) <= 450, "by the leader: " + h);
        assertTrue(f.status().contains("за лидером"), f.status());
    }

    @Test
    void arrivingOnTimeWaitsOnItsOwnMapUntilTheFlightLeavesJustEnough() throws Exception {
        // CARNAGE's BLCycle: «due in 129s, travel 130s, lead 0s»
        Sim sim = new Sim(with(config(30), "{\"arriveOnTime\": true}"), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        long now = System.currentTimeMillis();
        mem.killed(308, 4_700, 3_900, now - 60_000); // here: in 14 min
        mem.killed(306, 19_400, 5_800, now - 30_000); // 1-BL: in 14.5 min
        mem.killed(307, 6_900, 5_000, now - 11 * 60_000); // 2-BL: in 4 min — the next
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        assertEquals(308, m.workingMap(), "4 min to go, the flight 90 s: waits here");
        sim.run(m::tick, 10_000);
        assertTrue(m.status().startsWith("Жду вылета на 2-BL"), m.status());
        sim.run(m::tick, 140_000);
        assertEquals(307, m.workingMap(), "time to go");
    }

    @Test
    void aBehemothNobodySawComeIsTakenAsKilledAndTheNextOneIsDueARespawnOn() throws Exception {
        Sim sim = new Sim(with(config(30), "{\"arriveOnTime\": true}"), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        long now = System.currentTimeMillis();
        mem.killed(307, 6_900, 5_000, now - 20 * 60_000); // due 5 min ago, not watched
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        long due = m.dueAt(307, m.wallMs());
        assertTrue(due - m.wallMs() > 9 * 60_000 && due - m.wallMs() <= 10 * 60_000, "rolled on: " + (due - m.wallMs()));
        Sim plain = new Sim(config(30), 308, 5_700, 2_600);
        BehemothModule p = new BehemothModule(plain.ctx, mem);
        assertTrue(p.dueAt(307, p.wallMs()) <= p.wallMs(), "without the setting: as before, a time already past");
    }

    static void uridium(Sim sim, double u) {
        orbit.protocol.gen.AttributeCreditsUpdateCommand c = new orbit.protocol.gen.AttributeCreditsUpdateCommand();
        c.credits = 1_000_000;
        c.uridium = (int) u;
        sim.world.apply(c, sim.now);
    }

    @Test
    void theUridiumOfAKillCountsABehemothThatLeftTheViewAsDead() throws Exception {
        // CARNAGE's BLCycle «behemoth_uri_reward» > 5 000
        Sim sim = new Sim(config(300), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        m.workingMap();
        uridium(sim, 100_000);
        sim.run(m::tick, 500);
        sim.npc(77, BEHEMOTH, 9_500, 2_600); // far: its going is no proof of death
        sim.run(m::tick, 500);
        orbit.protocol.gen.ShipRemoveCommand gone = new orbit.protocol.gen.ShipRemoveCommand();
        gone.userId = 77;
        sim.world.apply(gone, sim.now);
        sim.run(m::tick, 1_000);
        assertTrue(mem.spawns(308).getFirst().lastKill() == 0, "out of view only");
        uridium(sim, 105_700);
        sim.run(m::tick, 500);
        assertTrue(mem.spawns(308).getFirst().lastKill() > 0, "the reward came: dead");
        assertEquals(1, sim.ctx.stats().kills.get());
    }

    @Test
    void cloakedWhileWaitingInTheCloakFormation() throws Exception {
        Sim sim = new Sim(with(config(300), "{\"useCloak\": true, \"cloakFormation\": \"drone_formation_f-11-he\"}"),
                308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100", "equipment_extra_cpu_cl04k-xl");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 3_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof orbit.protocol.gen.MenuActionRequest a
                && a.menuItemId.equals("equipment_extra_cpu_cl04k-xl")), "cloaked");
        assertEquals("drone_formation_f-11-he", sim.ctx.ship().wanted().formation(), "Heart");
    }

    @Test
    void thePetsBeaconByTheSpawnJustBeforeTheBehemoth() throws Exception {
        Sim sim = new Sim(with(config(300), "{\"beaconBeforeSeconds\": 10}"), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        mem.killed(308, 4_700, 3_900, System.currentTimeMillis() - 15 * 60_000 + 30_000); // due in 30 s
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        m.workingMap();
        sim.run(m::tick, 5_000);
        assertEquals(-1, m.petGear(), "25 s to go: not yet");
        sim.run(m::tick, 30_000);
        assertEquals(orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_COMBAT_BEACON, m.petGear());
        assertEquals(Boolean.TRUE, m.petOut());
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 4_700, h.y() - 3_900) <= 450, "by the spawn: " + h);
    }

    @Test
    void fightsByTheWaitPointDoNotStartTheWaitAgain() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: Impulse II by the wait point shot at the ship, each fight back started the
        // 30 s wait afresh — the bot would never have left the map
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 1_000);
        for (int i = 0; i < 3; i++) {
            sim.npc(40 + i, "\\\\ Impulse II //", 6_400, 2_600);
            shoots(sim, 40 + i, 1);
            sim.run(m::tick, 6_000);
            orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
            d.destroyedUserId = 40 + i;
            sim.world.apply(d, sim.now);
            sim.run(m::tick, 4_000);
        }
        sim.run(m::tick, 5_000);
        assertTrue(m.workingMap() != 308, "36 s on the map, three fights: on to another map, " + m.workingMap());
    }

    static void arrive(Sim sim, int map, int x, int y) {
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = map;
        si.factionId = 3;
        si.x = x;
        si.y = y;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
    }

    @Test
    void aMapNobodyLookedAtComesBeforeOneJustFoundEmpty() throws Exception {
        // live 2026-11-13 on the VPS: 1-BL empty, 2-BL empty — back to 1-BL, 3-BL never looked at
        Sim sim = new Sim(config(30), 306, 19_100, 7_900);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        assertEquals(306, m.workingMap());
        sim.run(m::tick, 40_000);
        assertEquals(307, m.workingMap(), "1-BL empty: the next");
        sim.now += 390_000 * Sim.MS; // as live: 6.5 min later the 2-BL wait runs out
        arrive(sim, 307, 5_200, 5_700);
        sim.run(m::tick, 40_000);
        assertEquals(308, m.workingMap(), "2-BL empty: 3-BL, not back to 1-BL");
    }

    @Test
    void fromTheBaseTheNearestBlMapFirst() throws Exception {
        // live 2026-11-13 on the VPS: logged in on 2-8, the ship went to 1-BL through 2-BL
        Sim sim = new Sim(config(30), 28, 11_200, 11_100); // our company's x-8: 3-8
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        assertEquals(308, m.workingMap(), "3-8 → 3-BL is one jump");
    }

    /** A pet out with the enemy locator. */
    static void locatorPet(Sim sim) {
        orbit.protocol.gen.PetInitializationCommand init = new orbit.protocol.gen.PetInitializationCommand();
        init.hasPet = true;
        init.hasFuel = true;
        init.petIsAlive = true;
        sim.world.apply(init, sim.now);
        orbit.protocol.gen.PetHeroActivationCommand a = new orbit.protocol.gen.PetHeroActivationCommand();
        a.ownerId = 1;
        a.petId = 500;
        a.petName = "P";
        a.x = sim.hero().x();
        a.y = sim.hero().y();
        sim.world.apply(a, sim.now);
        orbit.protocol.gen.PetGearAddCommand add = new orbit.protocol.gen.PetGearAddCommand();
        add.gearType = new orbit.protocol.gen.PetGearTypeModule();
        add.gearType.typeValue = (short) orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR;
        add.enabled = true;
        sim.world.apply(add, sim.now);
    }

    static void locator(Sim sim, int x, int y) {
        orbit.protocol.gen.PetGearLocatorUpdate u = new orbit.protocol.gen.PetGearLocatorUpdate();
        u.x = x;
        u.y = y;
        sim.world.apply(u, sim.now);
    }

    @Test
    void theLocatorsPointIsFlownTo() throws Exception {
        Sim sim = new Sim(with(config(300), "{\"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 6_000);
        locator(sim, 15_000, 9_000);
        sim.run(m::tick, 1_000);
        assertTrue(m.status().contains("по пеленгатору"), m.status());
        assertEquals(15_000, (int) sim.ctx.nav().destination().x());
    }

    @Test
    void npcsShootingOneAfterAnotherDoNotKeepTheShipPastTheWait() throws Exception {
        // live 2026-11-13 on the VPS, 3-BL: Attend IX one after another, the ship stayed over two minutes
        Sim sim = new Sim(config(30), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 1_000);
        for (int i = 0; i < 10; i++) {
            sim.npc(40 + i, "\\\\ Impulse II //", 6_300, 2_600 + i * 50);
            shoots(sim, 40 + i, 1);
            sim.run(m::tick, 5_000);
        }
        assertTrue(m.workingMap() != 308, "51 s, ten shooting one after another: on to another map, " + m.workingMap());
    }

    @Test
    void theWaitCountsFromComingToTheMapEvenIfTheWaitPointIsNeverReached() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: fights and players kept the ship off the point — the wait never began
        Sim sim = new Sim(config(30), 308, 9_000, 6_000);
        sim.lasers("ammunition_laser_ucb-100");
        sim.holdShip = true; // it never gets to the point
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 60_000);
        assertEquals(308, m.workingMap(), "a minute more for a way longer than reckoned");
        sim.run(m::tick, 60_000);
        assertTrue(m.workingMap() != 308, "the wait ran out though the point was not reached, " + m.workingMap());
    }

    @Test
    void anNpcShootingAtUsWhileWaitingIsFoughtBack() throws Exception {
        Sim sim = new Sim(config(300), 308, 5_700, 2_600);
        sim.lasers("ammunition_laser_ucb-100");
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.npc(40, "\\\\ Impulse II //", 6_000, 2_700);
        sim.run(m::tick, 3_000);
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof ShipSelectRequest s && s.targetId == 40), "quiet: left alone");
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = 40;
        run.targetId = 1;
        sim.world.apply(run, sim.now);
        sim.run(m::tick, 3_000);
        assertEquals(40, sim.hero().selected(), m.status());
        sim.run(m::tick, 6_000);
        var h = sim.hero();
        // live 2026-11-13 on the VPS: an Attend kept off at its radius took the ship off the point — now shot back in place
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 300, "still at the wait point: " + h);
    }

    @Test
    void enemiesByTheWaitPointMakeTheShipWaitOnTheFreeSideOfTheSpawn() throws Exception {
        // the user, 2026-11-13: «стоять в точке рядом с бегемотом со стороны где нет никого»
        Sim sim = new Sim(with(config(300), "{\"freeSide\": true, \"freeSideRadius\": 2000}"), 308, 4_000, 8_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        for (int i = 0; i < 4; i++) {
            enemy(sim, 900 + i, "Train" + i, 5_700 + i * 100, 2_600);
        }
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) > 3_000, "away from the train at the wait point: " + h);
        double fromSpawn = Math.hypot(h.x() - 4_700, h.y() - 3_900);
        assertTrue(fromSpawn > 1_600 && fromSpawn < 2_400, "on the ring round the spawn: " + h + ", " + (int) fromSpawn);
        assertTrue(m.status().startsWith("Жду бегемота"), m.status());
    }

    @Test
    void withoutEnemiesTheFreeSideIsTheWaitPoint() throws Exception {
        Sim sim = new Sim(with(config(300), "{\"freeSide\": true}"), 308, 4_000, 8_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 300, "at the wait point: " + h);
    }

    @Test
    void whileWaitingTheWayToTheWaitPointKeepsOffTheSpawn() throws Exception {
        // live 2026-11-13 on the VPS, 3-BL: from the portal 15/122 to the wait point 57/26 the way ran past the spawn 47/39,
        // where a train stood — killed twice
        Sim sim = new Sim(with(config(300), "{\"avoidSpawn\": true, \"avoidSpawnRadius\": 1500}"), 308, 1_500, 12_200);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        sim.ctx.nav().setAvoidedPlayers(m::avoidCircles);
        m.workingMap();
        double[] nearest = {Double.MAX_VALUE};
        sim.run(() -> {
            m.tick();
            var h = sim.hero();
            nearest[0] = Math.min(nearest[0], Math.hypot(h.x() - 4_700, h.y() - 3_900));
        }, 40_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 300, "at the wait point: " + h);
        var circles = m.avoidCircles();
        assertEquals(1, circles.size());
        double r = circles.getFirst()[2];
        assertTrue(r < 1_500 && r > 1_200, "smaller than asked: the wait point is 1 640 from the spawn, " + r);
        assertTrue(nearest[0] >= r - 150, "never through the spawn's circle: " + (int) nearest[0]);
        sim.npc(77, BEHEMOTH, 4_700, 3_900);
        sim.run(m::tick, 500);
        assertTrue(m.avoidCircles().isEmpty(), "the behemoth there: no circle");
    }

    @Test
    void aLongerWayThanStraightStillGivesTheWholeWaitAtThePoint() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: a detour round the server's zones ate the wait, the ship left the point in 0.5 s
        Sim sim = new Sim(config(2), 308, 1_500, 12_200);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        // a big circle across the straight way: the detour takes far longer than the straight line
        sim.ctx.nav().setAvoidedPlayers(() -> java.util.List.of(new double[] {3_600, 7_400, 2_600}));
        m.workingMap();
        long[] at = {0};
        for (int i = 0; i < 600 && at[0] == 0; i++) {
            sim.run(m::tick, 100);
            var h = sim.hero();
            if (Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 250) {
                at[0] = sim.now;
            }
        }
        assertTrue(at[0] > 0, "reached the point");
        sim.run(m::tick, 1_000);
        assertEquals(308, m.workingMap(), "waiting at the point: " + m.status());
        sim.run(m::tick, 2_000);
        assertTrue(m.workingMap() != 308, "then the next map");
    }

    @Test
    void enemiesFarAwayDoNotMoveTheShipOffTheWaitPoint() throws Exception {
        // live 2026-11-13 on the VPS, 1-BL: «Чужие у точки ожидания (12033 от ближайшего) — встаю со свободной стороны»
        Sim sim = new Sim(with(config(300), "{\"freeSide\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        enemy(sim, 900, "FarAway", 18_000, 11_000);
        sim.run(m::tick, 20_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_700, h.y() - 2_600) <= 300, "still at the wait point: " + h);
    }

    @Test
    void theShipWaitsAwayFromTheWaysPortalToSpawnTheTrainsFly() throws Exception {
        // the user, 2026-11-13: «точку недалеко от бегемота, но не со стороны порта, чтобы поезд не задел»
        Sim sim = new Sim(with(config(300), "{\"awayFromPortals\": true, \"freeSideRadius\": 2000}"), 308, 4_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        // 3-BL: the spawn 47/39, portals 15/122, 194/118, 140/31 — the free corner is to the north-west
        assertTrue(h.x() < 4_700 && h.y() < 3_900, "north-west of the spawn: " + h);
        assertTrue(Math.abs(Math.hypot(h.x() - 4_700, h.y() - 3_900) - 2_000) < 400, "on the ring: " + h);
        for (var q : sim.ctx.maps().portals(308)) {
            double d = orbit.bot.nav.Navigator.distanceToSegment(h.x(), h.y(), q.x(), q.y(), 4_700, 3_900);
            assertTrue(d > 1_500, "away from the way from " + q + ": " + (int) d);
        }
    }

    @Test
    void theFreeSideKeepsOffTheTrainsWays() throws Exception {
        Sim sim = new Sim(with(config(300), "{\"awayFromPortals\": true, \"freeSide\": true}"), 308, 4_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 30_000);
        var at = sim.hero();
        enemy(sim, 900, "Train", at.x(), at.y());
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - at.x(), h.y() - at.y()) > 1_500, "off the enemy: " + h);
        for (var q : sim.ctx.maps().portals(308)) {
            double d = orbit.bot.nav.Navigator.distanceToSegment(h.x(), h.y(), q.x(), q.y(), 4_700, 3_900);
            assertTrue(d >= 900, "still off the way from " + q + ": " + (int) d);
        }
    }

    @Test
    void byDefaultTheLocatorsNothingDoesNotCutTheWaitShort() throws Exception {
        // the user, 2026-11-13: «долетает до точки и не ждёт, а в конфиге стоит, чтобы ждал»
        Sim sim = new Sim(with(config(30), "{\"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 6_000);
        locator(sim, -1, -1);
        sim.run(m::tick, 5_000);
        locator(sim, -1, -1);
        sim.run(m::tick, 5_000);
        assertEquals(308, m.workingMap(), "the config's 30 s: still waiting");
        sim.run(m::tick, 20_000);
        assertTrue(m.workingMap() != 308, "then the next map");
    }

    @Test
    void byTheLocatorsPointTheShipStaysInsteadOfGoingBackToTheWaitPoint() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: the locator showed the behemoth at its spawn, the wait took the ship back
        Sim sim = new Sim(with(config(300), "{\"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 6_000);
        for (int i = 0; i < 20; i++) {
            locator(sim, 9_000, 6_000);
            sim.run(m::tick, 2_000);
        }
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 9_000, h.y() - 6_000) < 900, "by the locator's point: " + h);
        assertTrue(m.status().contains("пеленгатора"), m.status());
    }

    @Test
    void aTrainCampingTheSpawnWithTheBehemothUnseenSetsItsTimer() throws Exception {
        // the user, 2026-11-13: «поезд полетел на 3-BL убивать бегемота, а мы улетели» — the bot had no timers
        Sim sim = new Sim(with(config(300), "{\"trainTimer\": true}"), 308, 5_700, 2_600);
        SpawnMemory mem = SpawnMemory.inMemory();
        BehemothModule m = new BehemothModule(sim.ctx, mem);
        m.workingMap();
        for (int i = 0; i < 4; i++) {
            enemy(sim, 900 + i, "Train" + i, 4_700 + i * 100, 3_900);
        }
        sim.run(m::tick, 10_000);
        assertEquals(0, m.dueAt(308, m.wallMs()), "10 s: not yet");
        sim.run(m::tick, 15_000);
        long due = m.dueAt(308, m.wallMs());
        assertTrue(due - m.wallMs() > 13 * 60_000, "killed by them: the next in about 15 min, " + (due - m.wallMs()));
    }

    @Test
    void aTrainComingToTheSpawnKeepsTheShipWaiting() throws Exception {
        // the user, 2026-11-13: «не улетать, если поезд идёт к нам»
        Sim sim = new Sim(with(config(5), "{\"waitForTrain\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        for (int i = 0; i < 3; i++) {
            enemy(sim, 900 + i, "Train" + i, 7_500 + i * 100, 5_500);
        }
        sim.run(m::tick, 20_000);
        assertEquals(308, m.workingMap(), "the wait is over, a train comes: waiting on");
        for (int i = 0; i < 3; i++) {
            orbit.protocol.gen.ShipRemoveCommand r = new orbit.protocol.gen.ShipRemoveCommand();
            r.userId = 900 + i;
            sim.world.apply(r, sim.now);
        }
        sim.run(m::tick, 2_000);
        assertTrue(m.workingMap() != 308, "gone: on to the next map");
    }

    @Test
    void theSpawnIsKeptOffOnTheWayToTheNextMapToo() throws Exception {
        // live 2026-11-13 on the VPS, 3-BL: from the wait point 36/21 to the 2-BL portal the way ran past the spawn
        Sim sim = new Sim(with(config(2), "{\"avoidSpawn\": true, \"awayFromPortals\": true}"), 308, 3_600, 2_100);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 20_000);
        assertTrue(m.workingMap() != 308, "the wait is over: off to another map");
        m.pause();
        assertEquals(1, m.avoidCircles().size(), "still keeping off the spawn on the way to the portal");
    }

    @Test
    void enemiesInSightAreFlownRoundAndKeepTheShipAtItsPoint() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: off to the portal straight at a train coming from it — killed in 2 s
        Sim sim = new Sim(with(config(2), "{\"avoidEnemies\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        enemy(sim, 900, "Coming", 9_000, 4_000);
        sim.run(m::tick, 10_000);
        assertEquals(308, m.workingMap(), "an enemy 3 600 away: not setting off");
        assertTrue(m.avoidCircles().stream().anyMatch(c -> c[0] == 9_000 && c[1] == 4_000), "his circle flown round");
        orbit.protocol.gen.ShipRemoveCommand r = new orbit.protocol.gen.ShipRemoveCommand();
        r.userId = 900;
        sim.world.apply(r, sim.now);
        sim.run(m::tick, 1_000);
        assertTrue(m.workingMap() != 308, "gone: off");
    }

    /** A preferred zone of one cell column on 3-BL: x 18 900–19 600, y 600–2 400 (resolution 30). */
    static BotConfig.ZoneGrid cornerZone() {
        java.util.List<String> rows = new java.util.ArrayList<>();
        for (int y = 0; y < 30; y++) {
            StringBuilder r = new StringBuilder(".".repeat(30));
            if (y >= 2 && y <= 5) {
                r.setCharAt(27, '#');
            }
            rows.add(r.toString());
        }
        return new BotConfig.ZoneGrid(30, rows);
    }

    @Test
    void theShipWaitsInTheConfigsPreferredZoneAsAstraBotDoes() throws Exception {
        // AstraBot's «Moving to behe zone»: its behemoth profile waits in the preferred zone, far from the spawn
        BotConfig c = with(config(300), "{\"waitInZone\": true}")
                .withZones(Map.of(308, new BotConfig.Zones(cornerZone(), null)));
        Sim sim = new Sim(c, 308, 15_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 30_000);
        var h = sim.hero();
        assertTrue(h.x() > 18_600 && h.x() < 19_900 && h.y() > 500 && h.y() < 2_600, "in the zone: " + h);
    }

    @Test
    void theTrainsWaysAreFlownRoundButTheirEndsLeftOpen() throws Exception {
        // the user, 2026-11-13: «у них самый краткий полёт от порта к бегемоту … полёт по другой траектории»
        Sim sim = new Sim(with(config(300), "{\"avoidTrainPaths\": true}"), 308, 15_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        var circles = m.avoidCircles();
        assertTrue(circles.size() > 10, "circles along the ways: " + circles.size());
        for (var q : sim.ctx.maps().portals(308)) {
            for (double[] c : circles) {
                assertTrue(Math.hypot(c[0] - q.x(), c[1] - q.y()) > c[2], "the portal " + q + " is open");
                assertTrue(Math.hypot(c[0] - 4_700, c[1] - 3_900) > c[2], "the spawn is open");
            }
        }
    }

    @Test
    void inTheZoneModeAFreerPlaceIsLookedForInTheZoneNotByTheSpawn() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: from the zone by the edge the free side took the ship to the spawn's ring
        BotConfig c = with(config(300), "{\"waitInZone\": true, \"freeSide\": true}")
                .withZones(Map.of(308, new BotConfig.Zones(cornerZone(), null)));
        Sim sim = new Sim(c, 308, 15_000, 6_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 30_000);
        var at = sim.hero();
        enemy(sim, 900, "Train", at.x(), at.y() + 300);
        sim.run(m::tick, 20_000);
        var h = sim.hero();
        assertTrue(h.x() > 18_600 && h.x() < 19_900 && h.y() > 500 && h.y() < 2_600, "still in the zone: " + h);
    }

    static void targets(Sim sim, Integer... npcs) {
        orbit.protocol.gen.PetLocatorGearInitializationCommand c = new orbit.protocol.gen.PetLocatorGearInitializationCommand();
        c.locatorType = new orbit.protocol.gen.PetGearTypeModule();
        c.possibleTargetValues = new java.util.ArrayList<>(java.util.List.of(npcs));
        sim.world.apply(c, sim.now);
    }

    @Test
    void theBehemothMissingFromTheLocatorsListSendsTheShipOn() throws Exception {
        // the user, 2026-11-13: «если его даже выбрать нельзя, то его нет на карте»
        Sim sim = new Sim(with(config(30), "{\"useLocator\": true, \"locatorList\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 3_000);
        targets(sim, 175, 176);
        sim.run(m::tick, 2_000);
        assertEquals(308, m.workingMap(), "the list just came: read after a few seconds on the map");
        sim.run(m::tick, 2_000);
        assertTrue(m.workingMap() != 308, "not in the list: on at once");
    }

    @Test
    void theBehemothInTheLocatorsListKeepsTheShipWaiting() throws Exception {
        Sim sim = new Sim(with(config(8), "{\"useLocator\": true, \"locatorList\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 3_000);
        targets(sim, 175, BehemothModule.BEHEMOTH_NPC, 176);
        sim.run(m::tick, 30_000);
        assertEquals(308, m.workingMap(), "listed: it is on the map, the wait goes on");
    }

    @Test
    void aWaitPointAmongEnemiesIsNotFlownToStraightThroughThem() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: «нет пути к 87/42 … иду напрямик» between two trains
        Sim sim = new Sim(with(config(300), "{\"avoidEnemies\": true}"), 308, 12_000, 8_000);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        sim.ctx.nav().setAvoidedPlayers(m::avoidCircles);
        m.workingMap();
        enemy(sim, 900, "Camper", 5_800, 2_700);
        sim.run(m::tick, 20_000);
        var h = sim.hero();
        assertTrue(Math.hypot(h.x() - 5_800, h.y() - 2_700) > 1_900, "kept off the enemy at the point: " + h);
        assertTrue(m.status().contains("среди врагов"), m.status());
    }

    @Test
    void theFirstMapIsWaitedOnUntilTheFirstBehemoth() throws Exception {
        // the user, 2026-11-13: «на первой же карте простоять всё время, пока не дождёмся бегемота, дождались — и по кругу»
        Sim sim = new Sim(with(config(30), "{\"waitFirst\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 5 * 60_000);
        assertEquals(308, m.workingMap(), "5 min, none yet: still here");
        sim.npc(77, BEHEMOTH, 4_700, 3_900);
        sim.run(m::tick, 2_000);
        orbit.protocol.gen.ShipDestroyedCommand d = new orbit.protocol.gen.ShipDestroyedCommand();
        d.destroyedUserId = 77;
        sim.world.apply(d, sim.now);
        sim.run(m::tick, 60_000);
        assertTrue(m.workingMap() != 308, "it came and died: on round the maps");
    }

    @Test
    void theFirstMapIsLeftAfterARespawnWithNone() throws Exception {
        Sim sim = new Sim(with(config(30), "{\"waitFirst\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        sim.run(m::tick, 16 * 60_000);
        assertEquals(308, m.workingMap(), "within a respawn and the spare");
        sim.run(m::tick, 2 * 60_000);
        assertTrue(m.workingMap() != 308, "none for 17 min: on");
    }

    @Test
    void theLocatorsFlickerDoesNotTurnTheShipBack() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: a point, «nothing» 9 s later, the point again — the ship turned back each time
        Sim sim = new Sim(with(config(300), "{\"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 6_000);
        locator(sim, 15_000, 9_000);
        sim.run(m::tick, 2_000);
        locator(sim, -1, -1);
        sim.run(m::tick, 3_000);
        assertTrue(m.status().contains("пеленгатор"), "still after the behemoth: " + m.status());
        assertEquals(15_000, (int) sim.ctx.nav().destination().x());
        sim.run(m::tick, 10_000);
        assertTrue(!m.status().contains("по пеленгатору"), "no point for over 10 s: given up, " + m.status());
    }

    @Test
    void theBehemothLeavingTheLocatorsListIsTakenAsItsDeath() throws Exception {
        // BLCycle times the respawn from the locator losing it; live 2026-11-13: 178 listed 23:58:18, gone 23:59:15
        Sim sim = new Sim(with(config(300), "{\"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 7_000);
        targets(sim, 178, 175);
        sim.run(m::tick, 2_000);
        targets(sim, 175);
        sim.run(m::tick, 2_000);
        long due = m.dueAt(308, m.wallMs()) - m.wallMs();
        assertTrue(due > 13 * 60_000 && due < 16 * 60_000, "the next in about 15 min: " + due);
    }

    @Test
    void aBehemothFoundGoneLaterDiedSoonAfterItWasLastListed() throws Exception {
        // live 2026-11-14: gone 00:19:18, noticed 00:21:30 — the timer was two minutes late
        Sim sim = new Sim(with(config(300), "{\"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 7_000);
        targets(sim, 178, 175);
        sim.run(m::tick, 2_000);
        sim.run(() -> { }, 120_000); // no ticks meanwhile: dead or away
        targets(sim, 175);
        sim.run(m::tick, 1_000);
        long due = m.dueAt(308, m.wallMs()) - m.wallMs();
        assertTrue(due < 13 * 60_000 + 15_000, "dead about two minutes ago: " + due);
    }

    @Test
    void aTrainWaitingAtTheSpawnIsNoKillWhileTheLocatorsListHasNotHadIt() throws Exception {
        // live 2026-11-13 on the VPS, 2-BL: 13 by the spawn 20 s, 178 never in the list — a false kill
        Sim sim = new Sim(with(config(300), "{\"trainTimer\": true, \"useLocator\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        locatorPet(sim);
        m.workingMap();
        sim.run(m::tick, 3_000);
        targets(sim, 175, 176);
        for (int i = 0; i < 4; i++) {
            enemy(sim, 900 + i, "Train" + i, 4_700 + i * 100, 3_900);
        }
        sim.run(m::tick, 30_000);
        assertEquals(0, m.dueAt(308, m.wallMs()), "no kill taken: the train waits for it");
    }

    @Test
    void aTrainLeavingThroughAPortalIsFollowedAfterAWhile() throws Exception {
        // the user, 2026-11-13: «после поезда туда не полетел, а туда поехал поезд, потому что там сейчас бегемот появится»
        Sim sim = new Sim(with(config(300), "{\"followTrain\": true}"), 308, 5_700, 2_600);
        BehemothModule m = new BehemothModule(sim.ctx, SpawnMemory.inMemory());
        m.workingMap();
        var portal = sim.ctx.maps().portals(308).stream().filter(q -> q.toId() == 307).findFirst().orElseThrow();
        sim.run(m::tick, 6_000);
        for (int i = 0; i < 4; i++) {
            enemy(sim, 900 + i, "Train" + i, portal.x() + 100 * i, portal.y());
        }
        sim.run(m::tick, 2_000);
        for (int i = 0; i < 4; i++) {
            orbit.protocol.gen.ShipRemoveCommand r = new orbit.protocol.gen.ShipRemoveCommand();
            r.userId = 900 + i;
            sim.world.apply(r, sim.now);
            sim.run(m::tick, 1_000);
        }
        assertEquals(308, m.workingMap(), "not on their heels");
        sim.run(m::tick, 25_000);
        assertEquals(307, m.workingMap(), "after them to 2-BL");
    }
}
