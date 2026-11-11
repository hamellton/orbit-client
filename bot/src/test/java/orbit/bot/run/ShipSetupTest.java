package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.bot.act.Actions;
import orbit.config.BotConfig;
import orbit.protocol.Packet;
import orbit.protocol.gen.DroneFormationChangeCommand;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.MenuCategoryInitializationCommand;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.MenuItemInitializationCommand;
import orbit.protocol.gen.MenuItemStatusCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.world.World;
import org.junit.jupiter.api.Test;

class ShipSetupTest {

    private static final long MS = 1_000_000L;
    private static final String DRILL = "drone_formation_f-3d-dr";
    private static final String RING = "drone_formation_f-3d-rg";

    private final World world = new World();
    private final List<String> asked = new ArrayList<>();
    private long now = 1_000 * MS;
    /** Like the live server on 2026-11-11: it reports the change at once, the item bar's «selected» not at all. */
    private boolean answers = true;

    private ShipSetup setup() {
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 22;
        si.hitPoints = 100;
        si.hitMax = 100;
        world.apply(si, now);
        MenuInitializationCommand menu = new MenuInitializationCommand();
        MenuCategoryInitializationCommand cat = new MenuCategoryInitializationCommand();
        cat.categoryId = "drone_formations";
        List<MenuItemInitializationCommand> items = new ArrayList<>();
        for (String id : List.of(RING, DRILL)) {
            MenuItemInitializationCommand it = new MenuItemInitializationCommand();
            it.status = new MenuItemStatusCommand();
            it.status.menuItemId = id;
            it.status.selected = id.equals(RING);
            it.status.available = true;
            it.cooldownGroup = new orbit.protocol.gen.CooldownTypeModule();
            it.cooldownGroup.typeValue = 47; // the formations' group, as the server sends it
            items.add(it);
        }
        cat.menuItems = items;
        menu.categories = List.of(cat);
        world.apply(menu, now);
        Actions actions = new Actions(() -> world.snapshot(now), this::send, () -> new Actions.Login(1, "s"),
                Actions.Screen.DEFAULT, new Random(1));
        return new ShipSetup(() -> world.snapshot(now), actions, () -> now, new Random(1));
    }

    private void send(Packet p) {
        if (p instanceof MenuActionRequest m && m.menuItemId.startsWith("drone_formation")) {
            asked.add(m.menuItemId);
            if (answers) {
                DroneFormationChangeCommand d = new DroneFormationChangeCommand();
                d.uid = 1;
                d.selectedFormationId = m.menuItemId.equals(DRILL) ? 15 : 14;
                world.apply(d, now + 50 * MS);
            }
        }
    }

    private void run(ShipSetup ship, BotConfig.ShipMode mode, long ms) {
        for (long t = 0; t < ms; t += 100) {
            ship.want(mode);
            now += 100 * MS;
        }
    }

    @Test
    void theServersAnswerEndsTheRequests() {
        ShipSetup ship = setup();
        run(ship, new BotConfig.ShipMode(1, DRILL), 30_000);
        assertEquals(List.of(DRILL), asked, "asked once: the server's DroneFormationChangeCommand is the answer");
    }

    @Test
    void noAnswerAsksAgainOnlyAfterAWhile() {
        ShipSetup ship = setup();
        answers = false;
        run(ship, new BotConfig.ShipMode(1, DRILL), 60_000);
        // 0, 8, 24, 56 s: ever more rarely while the server stays silent (live 2026-11-11: 57 warnings otherwise)
        assertEquals(4, asked.size(), "8, 16, 32 s apart: " + asked);
    }

    @Test
    void aFormationTheShipHasIsNotAskedFor() {
        ShipSetup ship = setup();
        // a configuration switch: the server puts that configuration's formation itself (Drill = 15)
        DroneFormationChangeCommand d = new DroneFormationChangeCommand();
        d.uid = 1;
        d.selectedFormationId = 15;
        world.apply(d, now);
        run(ship, new BotConfig.ShipMode(1, DRILL), 20_000);
        assertEquals(List.of(), asked, "asking for it would get no answer");
    }

    @Test
    void formationNumbers() {
        assertEquals(11, ShipSetup.formationId("drone_formation_f-11-he"));
        assertEquals(1, ShipSetup.formationId("drone_formation_f-01-tu"));
        assertEquals(14, ShipSetup.formationId(RING));
        assertEquals(15, ShipSetup.formationId(DRILL));
        assertEquals(null, ShipSetup.formationId("drone_formation_f-3d-wl"));
    }

    @Test
    void aFormationWantedAgainLaterIsANewRequest() {
        ShipSetup ship = setup();
        answers = false;
        run(ship, new BotConfig.ShipMode(1, DRILL), 1_000);
        assertEquals(List.of(DRILL), asked);
        answers = true;
        run(ship, new BotConfig.ShipMode(1, RING), 5_000); // the situation changed: Ring now (the ship has it)
        run(ship, new BotConfig.ShipMode(1, DRILL), 2_000); // and Drill again, 6 s after the first request
        assertEquals(List.of(DRILL, DRILL), asked, "asked at once, not 8 s after the old unanswered request");
    }

    private void groupTimer(int state) {
        orbit.protocol.gen.MenuItemCooldownGroupTimerCommand c = new orbit.protocol.gen.MenuItemCooldownGroupTimerCommand();
        c.cooldownType = new orbit.protocol.gen.CooldownTypeModule();
        c.cooldownType.typeValue = 47;
        c.timerState = new orbit.protocol.gen.TimerStateModule();
        c.timerState.timerStateValue = (short) state;
        c.time = state == 0 ? 0 : 2000;
        c.maxTime = 2000;
        world.apply(c, now);
    }

    @Test
    void waitsForTheServersReadyNotForItsTwoSeconds() {
        ShipSetup ship = setup();
        groupTimer(2); // a change just happened: «2000 ms», but the server says «ready» much later
        run(ship, new BotConfig.ShipMode(1, DRILL), 20_000);
        assertEquals(List.of(), asked, "still cooling down for the server");
        groupTimer(0);
        run(ship, new BotConfig.ShipMode(1, DRILL), 2_000);
        assertEquals(List.of(DRILL), asked, "asked right after «ready»");
    }
}
