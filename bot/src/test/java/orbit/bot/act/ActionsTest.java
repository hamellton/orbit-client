package orbit.bot.act;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.protocol.Packet;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.AddOreCommand;
import orbit.protocol.gen.AttackLaserRequest;
import orbit.protocol.gen.AttackRocketRequest;
import orbit.protocol.gen.CollectBoxRequest;
import orbit.protocol.gen.HarvestRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.ShipSelectRequest;
import orbit.protocol.gen.SlotBarInitializationCommand;
import orbit.protocol.gen.SlotInfoCommand;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

class ActionsTest {

    private final World world = new World();
    private final List<Packet> sent = new ArrayList<>();
    private final Random random = new Random(11);

    private Actions actions() {
        return new Actions(() -> world.snapshot(0), sent::add, () -> new Actions.Login(42, "s3ss10n"),
                Actions.Screen.DEFAULT, new Random(1));
    }

    private void hero(int x, int y) {
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 42;
        si.mapId = 9;
        si.x = x;
        si.y = y;
        world.apply(si, 0);
    }

    private WorldSnapshot.Ship npc(int id, int x, int y) {
        ShipCreateCommand c = new ShipCreateCommand();
        c.userId = id;
        c.npc = true;
        c.x = x;
        c.y = y;
        world.apply(c, 0);
        return world.snapshot(0).ships().get(id);
    }

    @Test
    void checksumsAlwaysHoldLikeTheClient() {
        Actions a = actions();
        for (int i = 0; i < 3_000; i++) {
            hero(1000 + random.nextInt(19000), 1000 + random.nextInt(11000));
            WorldSnapshot.Hero h = world.snapshot(0).hero();
            WorldSnapshot.Ship t = npc(7, h.x() - 600 + random.nextInt(1200), h.y() - 400 + random.nextInt(800));
            a.attackLaser(t);
            a.attackRocket(t);
            a.select(t);
            AttackLaserRequest l = (AttackLaserRequest) sent.get(sent.size() - 3);
            AttackRocketRequest r = (AttackRocketRequest) sent.get(sent.size() - 2);
            ShipSelectRequest s = (ShipSelectRequest) sent.getLast();
            assertNotEquals(0, (l.targetX + l.targetY) % 3);
            assertTrue(l.targetY == t.y() && Math.abs(l.targetX - t.x()) <= 1, "laser moves x");
            assertNotEquals(0, (r.targetX + r.targetY) % 3);
            assertTrue(r.targetX == t.x() && Math.abs(r.targetY - t.y()) <= 1, "rocket moves y");
            assertEquals(0, (s.targetX + s.targetY + s.posY + s.posX) % 8);
            assertTrue(Math.hypot(s.posX - s.clickX, s.posY - s.clickY) <= Actions.CLICK_RADIUS, "the mouse hits the ship");
            assertEquals(Actions.CLICK_RADIUS, s.radius);
        }
    }

    @Test
    void selectionUsesTheShipsScreenPosition() {
        hero(10000, 6000);
        WorldSnapshot.Ship t = npc(7, 10300, 5800);
        actions().select(t);
        ShipSelectRequest s = (ShipSelectRequest) sent.getFirst();
        assertEquals(960 + 300, s.clickX, "screen centre plus the offset from the hero");
        assertEquals(511 - 200, s.clickY);
        assertEquals(10300, s.targetX);
    }

    @Test
    void boxesAndOre() {
        hero(16160, 8932);
        AddBoxCommand box = new AddBoxCommand();
        box.hash = "4ol0si";
        box.x = 16160;
        box.y = 8932;
        world.apply(box, 0);
        AddOreCommand ore = new AddOreCommand();
        ore.hash = "ore1";
        world.apply(ore, 0);
        Actions a = actions();
        a.collect(world.snapshot(0).collectables().get("4ol0si"));
        a.collect(world.snapshot(0).collectables().get("ore1"));
        CollectBoxRequest c = (CollectBoxRequest) sent.getFirst();
        // the recorded pickup: hero on the box, sum not divisible by 3, sent unchanged
        assertEquals(16160, c.posX);
        assertEquals(8932, c.posY);
        assertEquals("ore1", ((HarvestRequest) sent.get(1)).itemHash);

        for (int i = 0; i < 1_000; i++) {
            hero(random.nextInt(20000), random.nextInt(13000)); // a new ship clears the map
            box.y = random.nextInt(13000);
            world.apply(box, 0);
            a.collect(world.snapshot(0).collectables().get("4ol0si"));
            CollectBoxRequest r = (CollectBoxRequest) sent.getLast();
            assertNotEquals(0, (r.boxY + r.posX + r.posY) % 3);
        }
    }

    @Test
    void itemsComeFromTheSlotBarWhenTheyAreThere() {
        hero(1000, 1000);
        MenuInitializationCommand menu = new MenuInitializationCommand();
        SlotBarInitializationCommand bar = new SlotBarInitializationCommand();
        SlotInfoCommand slot = new SlotInfoCommand();
        slot.slotId = 1;
        slot.menuItemId = "ammunition_laser_ucb-100";
        bar.slots = List.of(slot);
        menu.slotBars = List.of(bar);
        world.apply(menu, 0);
        Actions a = actions();
        a.useItem("ammunition_laser_ucb-100");
        a.useItem("drone_formation_f-11-he");
        MenuActionRequest key = (MenuActionRequest) sent.get(0);
        MenuActionRequest click = (MenuActionRequest) sent.get(1);
        assertEquals(MenuActionRequest.SOURCETYPE_SLOT_BAR, key.sourceType, "as recorded: key 1");
        assertEquals(MenuActionRequest.ACTIONTYPE_ACTIVATE, key.actionType);
        assertEquals(MenuActionRequest.SOURCETYPE_ITEM_BAR, click.sourceType);
    }

    @Test
    void configSwitchRepeatsTheLogin() {
        hero(1000, 1000);
        actions().switchConfig(2);
        assertEquals("S|CFG|2|42|s3ss10n", ((LegacyModule) sent.getFirst()).message);
    }

    @Test
    void screenSizeFromTheReadyString() {
        assertEquals(new Actions.Screen(1920, 1022),
                Actions.Screen.fromReadyString("2D 1920x1022 .root1.instance469.MainClientApplication0"));
        assertEquals(Actions.Screen.DEFAULT, Actions.Screen.fromReadyString("garbage"));
    }
}
