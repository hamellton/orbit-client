package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.AddOreCommand;
import orbit.protocol.gen.AttackHitCommand;
import orbit.protocol.gen.AttackHitNoLockCommand;
import orbit.protocol.gen.AttackLaserRunCommand;
import orbit.protocol.gen.BeaconCommand;
import orbit.protocol.gen.ClanRelationModule;
import orbit.protocol.gen.CooldownTypeModule;
import orbit.protocol.gen.JumpgateCreateCommand;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.MenuCategoryInitializationCommand;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.MenuItemCooldownGroupTimerCommand;
import orbit.protocol.gen.MenuItemInitializationCommand;
import orbit.protocol.gen.MenuItemStatusCommand;
import orbit.protocol.gen.MoveCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.OreTypeModule;
import orbit.protocol.gen.POITypeModule;
import orbit.protocol.gen.PetHeroActivationCommand;
import orbit.protocol.gen.ReloginCommand;
import orbit.protocol.gen.RemoveCollectableCommand;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipDestroyedCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.ShipRemoveCommand;
import orbit.protocol.gen.ShipSelectionCommand;
import orbit.protocol.gen.TimerStateModule;
import org.junit.jupiter.api.Test;

class WorldTest {

    private static final long S = 1_000_000_000L;
    private static final int HERO = 100;

    private static World withHero() {
        World w = new World();
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = HERO;
        si.userName = "Pilot";
        si.mapId = 9;
        si.x = 1000;
        si.y = 1000;
        si.speed = 300;
        si.hitPoints = 500;
        si.hitMax = 1000;
        w.apply(si, 0);
        return w;
    }

    private static ShipCreateCommand ship(int id, boolean npc, int x, int y) {
        ShipCreateCommand c = new ShipCreateCommand();
        c.userId = id;
        c.npc = npc;
        c.userName = npc ? "-=[ Streuner ]=-" : "Enemy";
        c.x = x;
        c.y = y;
        c.clanDiplomacy = new ClanRelationModule();
        c.clanDiplomacy.type = (short) ClanRelationModule.TYPE_AT_WAR;
        return c;
    }

    @Test
    void shipsMoveAlongMoveCommandAndDisappear() {
        World w = withHero();
        w.apply(ship(7, true, 0, 0), 0);
        MoveCommand m = new MoveCommand();
        m.userId = 7;
        m.x = 1000;
        m.y = 0;
        m.timeToTarget = 2000;
        w.apply(m, 0);

        WorldSnapshot.Ship s = w.snapshot(S).ships().get(7);
        assertEquals(500, s.x(), "half way after 1 of 2 seconds");
        assertTrue(s.moving());
        assertTrue(s.npc());
        assertEquals(ClanRelationModule.TYPE_AT_WAR, s.relation());
        assertEquals(1000, w.snapshot(5 * S).ships().get(7).x());
        assertFalse(w.snapshot(5 * S).ships().get(7).moving());

        ShipRemoveCommand rm = new ShipRemoveCommand();
        rm.userId = 7;
        w.apply(rm, 6 * S);
        assertTrue(w.snapshot(6 * S).ships().isEmpty());
    }

    @Test
    void heroFollowsOwnMoveRequestsAndOnlyJumpsOnFarBeacons() {
        World w = withHero();
        MoveRequest mr = new MoveRequest();
        mr.positionX = 1000;
        mr.positionY = 1000;
        mr.targetX = 1600;
        mr.targetY = 1000;
        w.applyOutgoing(mr, 0);
        assertEquals(1300, w.snapshot(S).hero().x(), "300 units per second");

        BeaconCommand near = new BeaconCommand();
        near.positionX = 1250;
        near.positionY = 1000;
        near.positionAheadX = 1550;
        near.positionAheadY = 1000;
        near.inRadiationZone = true;
        w.apply(near, S);
        assertEquals(1300, w.snapshot(S).hero().x(), "small differences are ignored, like the client");
        assertTrue(w.snapshot(S).hero().beacon().inRadiationZone());

        BeaconCommand far = new BeaconCommand();
        far.positionX = 9000;
        far.positionY = 9000;
        far.positionAheadX = 9000;
        far.positionAheadY = 9000;
        w.apply(far, S);
        WorldSnapshot.Hero h = w.snapshot(2 * S).hero();
        assertEquals(9000, h.x(), "a far beacon means a jump");
        assertFalse(h.moving());
    }

    @Test
    void collectablesPortalsAndZones() {
        World w = withHero();
        AddBoxCommand box = new AddBoxCommand();
        box.hash = "b1";
        box.boxType = "BONUS_BOX";
        box.x = 10;
        box.y = 20;
        w.apply(box, 0);
        AddOreCommand ore = new AddOreCommand();
        ore.hash = "o1";
        ore.oreType = new OreTypeModule();
        ore.oreType.typeValue = (short) OreTypeModule.ORETYPE_PROMETIUM;
        w.apply(ore, 0);
        JumpgateCreateCommand gate = new JumpgateCreateCommand();
        gate.gateId = 3;
        gate.x = 18500;
        gate.y = 11500;
        w.apply(gate, 0);
        MapAddPOICommand poi = new MapAddPOICommand();
        poi.poiId = "rad";
        poi.poiType = new POITypeModule();
        poi.poiType.typeValue = (short) POITypeModule.POITYPE_RADIATION;
        poi.shape = (short) MapAddPOICommand.SHAPETYPE_RECTANGLE;
        poi.inverted = true;
        poi.shapeCoordinates = List.of(0, 0, 20800, 12800);
        w.apply(poi, 0);

        WorldSnapshot s = w.snapshot(0);
        assertEquals(WorldSnapshot.CollectableKind.BOX, s.collectables().get("b1").kind());
        assertEquals(OreTypeModule.ORETYPE_PROMETIUM, s.collectables().get("o1").oreType());
        assertEquals(18500, s.portals().get(3).x());
        assertTrue(s.zones().get("rad").inverted());

        RemoveCollectableCommand rc = new RemoveCollectableCommand();
        rc.hash = "b1";
        w.apply(rc, 0);
        assertEquals(1, w.snapshot(0).collectables().size());

        w.apply(new ReloginCommand(), 0);
        s = w.snapshot(0);
        assertTrue(s.collectables().isEmpty() && s.portals().isEmpty() && s.zones().isEmpty());
        assertEquals(HERO, s.hero().id(), "the hero survives a map change");
    }

    @Test
    void legacyCommandsUpdateHeroAndShips() {
        World w = withHero();
        w.apply(ship(7, true, 0, 0), 0);
        for (String m : List.of("0|A|c|1600", "0|A|CC|2", "0|S|CFG|1", "0|n|w|3", "0|A|BKB|5", "0|n|INV|7|1",
                "0|n|LSH|7|555", "0|A|v|420", "garbage", "0|n|LSH|x")) {
            LegacyModule lm = new LegacyModule();
            lm.message = m;
            w.apply(lm, 0);
        }
        WorldSnapshot s = w.snapshot(0);
        assertEquals(1600, s.hero().cargoMax());
        assertEquals(1, s.hero().config(), "S|CFG after A|CC wins");
        assertEquals(3, s.hero().enemyWarning());
        assertEquals(420, s.hero().speed());
        assertEquals(5, s.inventory().bootyKeys().get("BKB"));
        assertTrue(s.ships().get(7).cloaked());
        assertTrue(s.ships().get(7).lockedByOther());

        LegacyModule own = new LegacyModule();
        own.message = "0|n|USH|7";
        w.apply(own, 0);
        assertFalse(w.snapshot(0).ships().get(7).lockedByOther());
        own.message = "0|n|LSH|7|" + HERO;
        w.apply(own, 0);
        assertFalse(w.snapshot(0).ships().get(7).lockedByOther(), "our own attack does not grey it out");
    }

    @Test
    void itemBarKeepsOneSelectedItemPerCategory() {
        World w = withHero();
        MenuInitializationCommand init = new MenuInitializationCommand();
        MenuCategoryInitializationCommand lasers = new MenuCategoryInitializationCommand();
        lasers.categoryId = "lasers";
        lasers.menuItems = List.of(item("ammunition_laser_lcb-10", 1000, true), item("ammunition_laser_ucb-100", 50, false));
        init.categories = List.of(lasers);
        w.apply(init, 0);
        assertEquals("ammunition_laser_lcb-10", w.snapshot(0).selected("lasers").id());

        w.apply(new MenuInitializationCommand(), 0); // slot bars only: items stay
        MenuItemStatusCommand st = item("ammunition_laser_ucb-100", 49, true).status;
        w.apply(st, 0);
        WorldSnapshot s = w.snapshot(0);
        assertEquals("ammunition_laser_ucb-100", s.selected("lasers").id());
        assertFalse(s.items().get("ammunition_laser_lcb-10").selected());
        assertEquals(49, s.items().get("ammunition_laser_ucb-100").counter());
        assertEquals("lasers", s.items().get("ammunition_laser_ucb-100").category());
    }

    @Test
    void cooldownGroupsAndRemainingTime() {
        World w = withHero();
        MenuInitializationCommand init = new MenuInitializationCommand();
        MenuCategoryInitializationCommand cat = new MenuCategoryInitializationCommand();
        cat.categoryId = "special_items";
        MenuItemInitializationCommand emp = item("ammunition_specialammo_emp-01", 10, false);
        emp.cooldownGroup = new CooldownTypeModule();
        emp.cooldownGroup.typeValue = 7;
        MenuItemInitializationCommand ish = item("equipment_extra_cpu_ish-01", 10, false);
        ish.cooldownGroup = new CooldownTypeModule();
        ish.cooldownGroup.typeValue = 7;
        MenuItemInitializationCommand other = item("ammunition_mine_acm-01", 10, false);
        cat.menuItems = List.of(emp, ish, other);
        init.categories = List.of(cat);
        w.apply(init, 0);

        MenuItemCooldownGroupTimerCommand t = new MenuItemCooldownGroupTimerCommand();
        t.cooldownType = new CooldownTypeModule();
        t.cooldownType.typeValue = 7;
        t.timerState = new TimerStateModule();
        t.timerState.timerStateValue = (short) TimerStateModule.TIMERSTATE_COOLING_DOWN;
        t.time = 30_000;
        t.maxTime = 30_000;
        w.apply(t, 0);

        WorldSnapshot s = w.snapshot(10 * S);
        assertEquals(20_000, s.items().get("ammunition_specialammo_emp-01").remainingMs(10 * S));
        assertEquals(20_000, s.items().get("equipment_extra_cpu_ish-01").remainingMs(10 * S), "same group");
        assertEquals(0, s.items().get("ammunition_mine_acm-01").remainingMs(10 * S), "other group");
        assertEquals(0, s.items().get("ammunition_specialammo_emp-01").remainingMs(40 * S));
    }

    @Test
    void areaHitsCarryMaximumValues() {
        World w = withHero();
        AttackHitNoLockCommand hit = new AttackHitNoLockCommand();
        hit.victimId = HERO;
        hit.victimHitpoints = 300;
        hit.victimMaxHitpoints = 1200;
        hit.victimShield = 10;
        hit.victimMaxShield = 100;
        w.apply(hit, 0);
        Health h = w.snapshot(0).hero().health();
        assertEquals(0.25, h.hpPercent(), 1e-9);
        assertEquals(0.1, h.shieldPercent(), 1e-9);
    }

    private static MenuItemInitializationCommand item(String id, double count, boolean selected) {
        MenuItemInitializationCommand i = new MenuItemInitializationCommand();
        i.status = new MenuItemStatusCommand();
        i.status.menuItemId = id;
        i.status.counterValue = count;
        i.status.selected = selected;
        i.status.available = true;
        return i;
    }

    @Test
    void healthSelectionAttacksPetsAndDeath() {
        World w = withHero();
        w.apply(ship(7, true, 0, 0), 0);
        ShipSelectionCommand sel = new ShipSelectionCommand();
        sel.userId = 7;
        sel.hitpoints = 800;
        sel.hitpointsMax = 800;
        sel.shield = 400;
        sel.shieldMax = 400;
        w.apply(sel, 0);
        assertEquals(7, w.snapshot(0).hero().selectedId());

        AttackLaserRunCommand laser = new AttackLaserRunCommand();
        laser.attackerId = 7;
        laser.targetId = HERO;
        w.apply(laser, 0);
        assertEquals(List.of(7), w.snapshot(0).hero().attackers(), "the NPC shoots at the hero");
        AttackLaserRunCommand mine = new AttackLaserRunCommand();
        mine.attackerId = HERO;
        mine.targetId = 7;
        w.apply(mine, 0);
        assertEquals(7, w.snapshot(0).hero().attackTargetId());
        AttackHitCommand hit = new AttackHitCommand();
        hit.victimId = 7;
        hit.victimHitpoints = 600;
        hit.victimShield = 0;
        w.apply(hit, 0);
        WorldSnapshot.Ship npc = w.snapshot(0).ships().get(7);
        assertEquals(HERO, npc.attackTargetId());
        assertEquals(0.75, npc.health().hpPercent(), 1e-9);
        assertEquals(0, npc.health().shield());

        PetHeroActivationCommand pet = new PetHeroActivationCommand();
        pet.petId = 55;
        pet.ownerId = HERO;
        pet.petName = "P.E.T.";
        w.apply(pet, 0);
        assertEquals(55, w.snapshot(0).hero().petId());
        assertEquals(WorldSnapshot.ShipKind.PET, w.snapshot(0).ships().get(55).kind());

        ShipDestroyedCommand dead = new ShipDestroyedCommand();
        dead.destroyedUserId = 7;
        w.apply(dead, 0);
        assertEquals(0, w.snapshot(0).hero().selectedId());
        assertEquals(0, w.snapshot(0).hero().attackTargetId(), "a destroyed target is no longer attacked");
        assertTrue(w.snapshot(0).hero().attackers().isEmpty());
        assertFalse(w.snapshot(0).ships().containsKey(7));

        dead.destroyedUserId = HERO;
        w.apply(dead, 0);
        assertFalse(w.snapshot(0).hero().alive());
        assertEquals(0.5, w.snapshot(0).hero().health().hpPercent(), 1e-9);
    }
}
