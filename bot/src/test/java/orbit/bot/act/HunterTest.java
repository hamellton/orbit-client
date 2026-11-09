package orbit.bot.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import orbit.bot.nav.Navigator;
import orbit.protocol.Packet;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.AttackLaserRunCommand;
import orbit.protocol.gen.CollectBoxRequest;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.MenuCategoryInitializationCommand;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.MenuItemInitializationCommand;
import orbit.protocol.gen.MenuItemStatusCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.RemoveCollectableCommand;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipDestroyedCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.ShipSelectRequest;
import orbit.protocol.gen.ShipSelectionCommand;
import orbit.world.World;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class HunterTest {

    private static final long MS = 1_000_000L;
    private final World world = new World();
    private long now;
    private long selectedAt = -1;
    private long pressedAt = -1;
    private long killAt = -1;

    private void receive(Packet p) {
        if (p instanceof MoveRequest m) {
            world.applyOutgoing(m, now);
        } else if (p instanceof ShipSelectRequest s) {
            ShipSelectionCommand c = new ShipSelectionCommand();
            c.userId = s.targetId;
            c.hitpoints = 800;
            c.hitpointsMax = 800;
            world.apply(c, now);
            selectedAt = now;
        } else if (p instanceof MenuActionRequest m && m.menuItemId.startsWith("ammunition_laser")) {
            AttackLaserRunCommand run = new AttackLaserRunCommand();
            run.attackerId = 1;
            run.targetId = 7;
            world.apply(run, now);
            pressedAt = now;
            killAt = now + 3_000 * MS;
        } else if (p instanceof CollectBoxRequest c) {
            var h = world.snapshot(now).hero();
            if (Math.hypot(h.x() - c.boxX, h.y() - c.boxY) < 60) {
                RemoveCollectableCommand rc = new RemoveCollectableCommand();
                rc.hash = c.itemHash;
                rc.collected = true;
                world.apply(rc, now);
            }
        }
    }

    @Test
    void roamsTheMapWhenNoTargetIsInSight() {
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 26;
        si.x = 2000;
        si.y = 2000;
        si.speed = 400;
        world.apply(si, now);
        Random random = new Random(4);
        Navigator nav = new Navigator(() -> world.snapshot(now), this::receive, MapCatalog.bundled(), random, () -> now);
        Actions actions = new Actions(() -> world.snapshot(now), this::receive, () -> new Actions.Login(1, "s"),
                Actions.Screen.DEFAULT, random);
        Hunter hunter = new Hunter(() -> world.snapshot(now), actions, nav, random, () -> now, s -> true, 1);
        for (int t = 0; t < 50; t++) {
            hunter.tick();
            now += 100 * MS;
        }
        assertTrue(world.snapshot(now).hero().moving(), "flies somewhere to find NPCs");
        var h = world.snapshot(now).hero();
        assertTrue(h.targetX() > 2000 && h.targetX() < 19000 && h.targetY() > 1300 && h.targetY() < 11800,
                "inside the map, away from the border");
    }

    @Test
    void killsAnNpcAndPicksUpItsBox() {
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 9;
        si.x = 10000;
        si.y = 6000;
        si.speed = 400;
        si.hitPoints = 1000;
        si.hitMax = 1000;
        world.apply(si, now);
        MenuInitializationCommand menu = new MenuInitializationCommand();
        MenuCategoryInitializationCommand lasers = new MenuCategoryInitializationCommand();
        lasers.categoryId = "lasers";
        MenuItemInitializationCommand ucb = new MenuItemInitializationCommand();
        ucb.status = new MenuItemStatusCommand();
        ucb.status.menuItemId = "ammunition_laser_ucb-100";
        ucb.status.selected = true;
        ucb.status.counterValue = 1000;
        ucb.status.available = true;
        lasers.menuItems = java.util.List.of(ucb);
        menu.categories = java.util.List.of(lasers);
        world.apply(menu, now);
        ShipCreateCommand npc = new ShipCreateCommand();
        npc.userId = 7;
        npc.npc = true;
        npc.userName = "-=[ Streuner ]=-";
        npc.x = 11500;
        npc.y = 6000;
        world.apply(npc, now);

        Random random = new Random(2);
        Navigator nav = new Navigator(() -> world.snapshot(now), this::receive, MapCatalog.bundled(), random, () -> now);
        Actions actions = new Actions(() -> world.snapshot(now), this::receive, () -> new Actions.Login(1, "s"),
                Actions.Screen.DEFAULT, random);
        Hunter hunter = new Hunter(() -> world.snapshot(now), actions, nav, random, () -> now, s -> true, 1);
        for (long t = 0; t < 60_000 && hunter.state() == Hunter.State.HUNTING; t += 100) {
            if (killAt > 0 && now >= killAt) {
                ShipDestroyedCommand d = new ShipDestroyedCommand();
                d.destroyedUserId = 7;
                world.apply(d, now);
                AddBoxCommand box = new AddBoxCommand();
                box.hash = "cargo";
                box.boxType = "FROM_SHIP";
                box.x = 11500;
                box.y = 6000;
                world.apply(box, now);
                killAt = -1;
            }
            hunter.tick();
            now += 100 * MS;
        }
        assertEquals(Hunter.State.DONE, hunter.state());
        assertEquals(1, hunter.kills());
        assertEquals(1, hunter.boxes());
        assertTrue(pressedAt - selectedAt >= 800 * MS, "the ammo key comes after a reaction pause");
        var h = world.snapshot(now).hero();
        assertTrue(Math.hypot(h.x() - 11500, h.y() - 6000) < 60, "flew onto the box");
    }
}
