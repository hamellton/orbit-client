package orbit.bot.run;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.bot.act.Actions;
import orbit.bot.nav.Navigator;
import orbit.bot.nav.Traveler;
import orbit.config.BotConfig;
import orbit.protocol.Packet;
import orbit.protocol.gen.AttackLaserRunCommand;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.MenuCategoryInitializationCommand;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.MenuItemInitializationCommand;
import orbit.protocol.gen.MenuItemStatusCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.ShipSelectRequest;
import orbit.protocol.gen.ShipSelectionCommand;
import orbit.world.World;
import orbit.world.maps.MapCatalog;

/** A tiny fake server around {@link World}: moves, selections, ammunition and attacks get their answers. */
final class Sim {

    static final long MS = 1_000_000L;

    final World world = new World();
    final List<Packet> sent = new ArrayList<>();
    final List<int[]> repairs = new ArrayList<>();
    long now;
    BotConfig config;
    final BotContext ctx;

    Sim(BotConfig config, int mapId, int x, int y) {
        this.config = config;
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = mapId;
        si.factionId = 3;
        si.x = x;
        si.y = y;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        si.shield = 50_000;
        si.shieldMax = 50_000;
        world.apply(si, now);
        Random random = new Random(7);
        Navigator nav = new Navigator(() -> world.snapshot(now), this::receive, MapCatalog.bundled(), random, () -> now);
        Actions actions = new Actions(() -> world.snapshot(now), this::receive, () -> new Actions.Login(1, "s"),
                Actions.Screen.DEFAULT, random);
        Traveler traveler = new Traveler(() -> world.snapshot(now), this::receive, nav, MapCatalog.bundled(), random, () -> now);
        ShipSetup ship = new ShipSetup(() -> world.snapshot(now), actions, () -> now, random);
        ctx = new BotContext(() -> world.snapshot(now), () -> this.config, actions, nav, traveler, MapCatalog.bundled(),
                random, () -> now, (type, faction) -> repairs.add(new int[] {type, faction}), ship, null, new Stats());
    }

    /** Lasers and rockets in one item menu (the client sends the whole menu at once). */
    void menu(String laser, String rocket, String... rockets) {
        MenuInitializationCommand menu = new MenuInitializationCommand();
        menu.categories = List.of(category("lasers", laser), category("rockets", rocket, rockets));
        world.apply(menu, now);
    }

    private static MenuCategoryInitializationCommand category(String id, String selected, String... others) {
        MenuCategoryInitializationCommand cat = new MenuCategoryInitializationCommand();
        cat.categoryId = id;
        List<MenuItemInitializationCommand> items = new ArrayList<>();
        List<String> all = new ArrayList<>(List.of(others));
        all.addFirst(selected);
        for (String i : all) {
            MenuItemInitializationCommand it = new MenuItemInitializationCommand();
            it.status = new MenuItemStatusCommand();
            it.status.menuItemId = i;
            it.status.selected = i.equals(selected);
            it.status.counterValue = 10_000;
            it.status.available = true;
            items.add(it);
        }
        cat.menuItems = items;
        return cat;
    }

    void lasers(String selected, String... others) {
        MenuInitializationCommand menu = new MenuInitializationCommand();
        MenuCategoryInitializationCommand lasers = new MenuCategoryInitializationCommand();
        lasers.categoryId = "lasers";
        List<MenuItemInitializationCommand> items = new ArrayList<>();
        List<String> all = new ArrayList<>(List.of(others));
        all.addFirst(selected);
        for (String id : all) {
            MenuItemInitializationCommand it = new MenuItemInitializationCommand();
            it.status = new MenuItemStatusCommand();
            it.status.menuItemId = id;
            it.status.selected = id.equals(selected);
            it.status.counterValue = 10_000;
            it.status.available = true;
            items.add(it);
        }
        lasers.menuItems = items;
        menu.categories = List.of(lasers);
        world.apply(menu, now);
    }

    void npc(int id, String name, int x, int y) {
        ShipCreateCommand c = new ShipCreateCommand();
        c.userId = id;
        c.npc = true;
        c.userName = name;
        c.x = x;
        c.y = y;
        world.apply(c, now);
    }

    void receive(Packet p) {
        sent.add(p);
        if (p instanceof MoveRequest m) {
            world.applyOutgoing(m, now);
        } else if (p instanceof ShipSelectRequest s) {
            ShipSelectionCommand c = new ShipSelectionCommand();
            c.userId = s.targetId;
            c.hitpoints = 1_000_000;
            c.hitpointsMax = 1_000_000;
            c.shield = 500_000;
            c.shieldMax = 500_000;
            world.apply(c, now);
        } else if (p instanceof MenuActionRequest m && m.menuItemId.startsWith("ammunition_rocket")) {
            MenuItemStatusCommand st = new MenuItemStatusCommand();
            st.menuItemId = m.menuItemId;
            st.selected = true;
            st.counterValue = 10_000;
            st.available = true;
            world.apply(st, now);
        } else if (p instanceof MenuActionRequest m && m.menuItemId.startsWith("ammunition_laser")) {
            MenuItemStatusCommand st = new MenuItemStatusCommand();
            st.menuItemId = m.menuItemId;
            st.selected = true;
            st.counterValue = 10_000;
            st.available = true;
            world.apply(st, now);
            int target = world.snapshot(now).hero().selectedId();
            if (target != 0) {
                AttackLaserRunCommand run = new AttackLaserRunCommand();
                run.attackerId = 1;
                run.targetId = target;
                world.apply(run, now);
            }
        }
    }

    WorldSnapshotView hero() {
        var h = world.snapshot(now).hero();
        return new WorldSnapshotView(h.x(), h.y(), h.mapId(), h.selectedId(), h.attackTargetId());
    }

    record WorldSnapshotView(int x, int y, int map, int selected, int attacking) {
    }

    void run(Runnable tick, long ms) {
        for (long t = 0; t < ms; t += 100) {
            tick.run();
            now += 100 * MS;
        }
    }
}
