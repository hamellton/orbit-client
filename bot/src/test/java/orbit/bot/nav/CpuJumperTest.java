package orbit.bot.nav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.bot.act.Actions;
import orbit.protocol.Packet;
import orbit.protocol.gen.CPUInitializationCommand;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.world.World;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

/** The jump CPU against a fake server answering as ru1 did on 2026-11-11. */
class CpuJumperTest {

    private static final long MS = 1_000_000L;
    private static final String PRICES =
            "0|A|JCPU|I|-1|1;9;|225|16;|850|2;3;4;5;6;7;8;10;11;12;|100|13;14;15;17;20;|150|29;|375|22;23;24;26;27;28;|125|19;|350|21;25;";

    private final World world = new World();
    private final List<String> sent = new ArrayList<>();
    private long now = 1_000 * MS;
    private boolean answers = true;
    private int countdown = 10;

    private CpuJumper jumper(boolean cpu, int vouchers, double uridium) {
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 22;
        si.hitPoints = 1;
        si.hitMax = 1;
        world.apply(si, now);
        CPUInitializationCommand c = new CPUInitializationCommand();
        c.advancedJumpCPUEquipped = cpu;
        world.apply(c, now);
        legacy("0|A|JV|" + vouchers);
        legacy("0|LM|ST|URI|0|" + (long) uridium);
        Actions actions = new Actions(() -> world.snapshot(now), this::server, () -> new Actions.Login(1, "s"),
                Actions.Screen.DEFAULT, new Random(1));
        return new CpuJumper(() -> world.snapshot(now), actions, MapCatalog.bundled(), () -> now, new Random(1), null);
    }

    private void legacy(String m) {
        LegacyModule l = new LegacyModule();
        l.message = m;
        world.apply(l, now);
    }

    private void server(Packet p) {
        String m = ((LegacyModule) p).message;
        sent.add(m);
        if (!answers) {
            return;
        }
        now += 50 * MS;
        switch (m) {
            case "JCPU|GET" -> legacy(PRICES);
            case "JCPU|J" -> legacy("0|A|JCPU|S|" + countdown + "|26");
            default -> {
                if (m.startsWith("JCPU|S|")) {
                    legacy("0|A|JCPU|C|" + m.substring(7) + "|375|1");
                }
            }
        }
    }

    /** Ticks every 100 ms; the server moves the ship 10 s after the countdown starts. */
    private CpuJumper.Result run(CpuJumper j, int map, int budget, long limitMs) {
        long countdownAt = -1;
        for (long t = 0; t < limitMs; t += 100) {
            CpuJumper.Result r = j.tick(map, budget);
            if (r != CpuJumper.Result.RUNNING) {
                return r;
            }
            if (countdownAt < 0 && sent.contains("JCPU|J") && answers) {
                countdownAt = now;
            }
            if (countdownAt >= 0 && now - countdownAt >= 10_000 * MS) {
                ShipInitializationCommand si = new ShipInitializationCommand();
                si.userId = 1;
                si.mapId = map;
                si.hitPoints = 1;
                si.hitMax = 1;
                si.uridium = world.snapshot(now).hero().uridium(); // the real server repeats the account's uridium
                world.apply(si, now);
                countdownAt = Long.MAX_VALUE / 2;
            }
            now += 100 * MS;
        }
        return CpuJumper.Result.RUNNING;
    }

    @Test
    void aVoucherJumpGoesLikeTheClients() {
        CpuJumper j = jumper(true, 481, 0);
        assertEquals(CpuJumper.Result.DONE, run(j, 26, 0, 30_000));
        assertEquals(List.of("JCPU|GET", "JCPU|S|26", "JCPU|J"), sent);
        assertEquals(0, j.spentToday(), "a voucher, no uridium");
    }

    @Test
    void withoutVouchersUridiumIsSpentWithinTheBudget() {
        CpuJumper j = jumper(true, 0, 10_000);
        assertEquals(CpuJumper.Result.DONE, run(j, 26, 1_000, 30_000));
        assertEquals(375, j.spentToday());
        // the next one would pass the budget (375 + 375 + 375 > 1000)
        sent.clear();
        assertEquals(CpuJumper.Result.DONE, run(j, 22, 1_000, 30_000), "back to 2-6 for 375 more");
        CpuJumper.Result third = run(j, 26, 1_000, 30_000);
        assertEquals(CpuJumper.Result.FAILED, third);
        assertTrue(j.failure().contains("осталось 250"), j.failure());
        assertTrue(!j.worthTrying(26), "portals to 3-6 for a while");
    }

    @Test
    void noCpuNoPriceOrNoAnswerMeansPortals() {
        assertEquals(CpuJumper.Result.FAILED, run(jumper(false, 481, 0), 26, 0, 1_000));
        sent.clear();
        CpuJumper home = jumper(true, 481, 0);
        assertEquals(CpuJumper.Result.FAILED, run(home, 1, 0, 2_000), "1-1: -1 in the table");
        assertTrue(home.failure().contains("прыжком нельзя"), home.failure());
        sent.clear();
        CpuJumper silent = jumper(true, 481, 0);
        answers = false;
        assertEquals(CpuJumper.Result.FAILED, run(silent, 26, 0, 10_000));
        assertEquals(List.of("JCPU|GET"), sent);
    }

    @Test
    void aCountdownCalledOffIsTriedAgainSoon() {
        CpuJumper j = jumper(true, 481, 0);
        countdown = -1; // as the server seems to answer a ship under fire
        assertEquals(CpuJumper.Result.FAILED, run(j, 26, 0, 5_000), "no waiting out a countdown that is not there");
        assertTrue(j.failure().contains("отменил"), j.failure());
        now += 3 * 60_000 * MS;
        assertTrue(j.worthTrying(26), "two minutes later the jump is tried again, not after ten");
    }

    @Test
    void theJumpCpuWorksAtAPortalOrInOurStation() {
        jumper(true, 481, 0); // the hero on 2-6
        MapCatalog maps = MapCatalog.bundled();
        MapCatalog.Portal portal = maps.portals(22).getFirst();
        ShipInitializationCommand at = new ShipInitializationCommand();
        at.userId = 1;
        at.mapId = 22;
        at.factionId = 2;
        at.x = portal.x() + 100;
        at.y = portal.y();
        at.hitPoints = 1;
        at.hitMax = 1;
        world.apply(at, now);
        assertTrue(CpuJumper.placeForJump(world.snapshot(now), maps), "next to a portal");
        at.x = 7500;
        at.y = 7900; // a Cubikon spawn
        world.apply(at, now);
        assertTrue(!CpuJumper.placeForJump(world.snapshot(now), maps), "not on a spawn");
        orbit.protocol.gen.BeaconCommand b = new orbit.protocol.gen.BeaconCommand();
        b.positionX = 7500;
        b.positionY = 7900;
        b.positionAheadX = 7500;
        b.positionAheadY = 7900;
        b.inStation = true;
        world.apply(b, now);
        int home = maps.info(22).homeFaction();
        assertEquals(home == 0 || home == 2, CpuJumper.placeForJump(world.snapshot(now), maps),
                "in a station on a map of our faction (or nobody's), not in an enemy's");
    }
}
