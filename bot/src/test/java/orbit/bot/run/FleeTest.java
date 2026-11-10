package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import orbit.config.BotConfig;
import orbit.protocol.gen.AttackLaserRunCommand;
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.ShipCreateCommand;
import org.junit.jupiter.api.Test;

class FleeTest {

    static BotConfig config(BotConfig.FleeMode mode, Set<String> players, Set<String> never) {
        return BotConfig.empty().withFlee(new BotConfig.Flee(mode, players, never, 2_000, 30, true, 60));
    }

    /** The hero is of faction 3 (Sim); a player of another faction close by. */
    static void player(Sim sim, int id, String name, String clan, int faction, int x, int y) {
        ShipCreateCommand c = new ShipCreateCommand();
        c.userId = id;
        c.npc = false;
        c.userName = name;
        c.clanTag = clan;
        c.factionId = faction;
        c.x = x;
        c.y = y;
        sim.world.apply(c, sim.now);
    }

    static void shoots(Sim sim, int id) {
        AttackLaserRunCommand a = new AttackLaserRunCommand();
        a.attackerId = id;
        a.targetId = 1;
        sim.world.apply(a, sim.now);
    }

    static boolean ran(Sim sim, Flee f, long ms) {
        boolean any = false;
        for (long t = 0; t < ms; t += 100) {
            any |= f.tick();
            sim.now += 100 * Sim.MS;
        }
        return any;
    }

    @Test
    void attackersModeIgnoresPeacefulEnemies() {
        Sim sim = new Sim(config(BotConfig.FleeMode.ATTACKERS, null, null), 26, 10_000, 6_000);
        player(sim, 500, "Hunter", "BAD", 1, 11_000, 6_000);
        Flee f = new Flee(sim.ctx);
        assertFalse(ran(sim, f, 2_000), "an enemy that does not shoot is ignored");
        shoots(sim, 500);
        assertTrue(ran(sim, f, 2_000), "it shoots: run");
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof MoveRequest), "flies to a portal");
    }

    @Test
    void enemiesInSightButNotFriendsOrTheOwnFaction() {
        Sim sim = new Sim(config(BotConfig.FleeMode.ENEMIES_IN_SIGHT, null, Set.of("[FRIENDS]")), 26, 10_000, 6_000);
        player(sim, 501, "Same", "", 3, 10_500, 6_000);        // own faction
        player(sim, 502, "Buddy", "FRIENDS", 1, 10_500, 6_000); // never run from
        Flee f = new Flee(sim.ctx);
        assertFalse(ran(sim, f, 2_000));
        player(sim, 503, "Stranger", "X", 2, 11_500, 6_000);
        assertTrue(ran(sim, f, 2_000));
    }

    @Test
    void listModeRunsOnlyFromTheListed() {
        Sim sim = new Sim(config(BotConfig.FleeMode.LIST, Set.of("theKing", "[RS]"), null), 26, 10_000, 6_000);
        player(sim, 504, "Other", "X", 1, 10_500, 6_000);
        Flee f = new Flee(sim.ctx);
        assertFalse(ran(sim, f, 2_000), "not on the list");
        player(sim, 505, "THEKING", "", 1, 11_000, 6_000);
        assertTrue(ran(sim, f, 2_000), "the name, any case");
        Sim sim2 = new Sim(config(BotConfig.FleeMode.LIST, Set.of("[RS]"), null), 26, 10_000, 6_000);
        player(sim2, 506, "Anyone", "RS", 1, 11_000, 6_000);
        assertTrue(ran(sim2, new Flee(sim2.ctx), 2_000), "the clan tag");
    }

    @Test
    void jumpsWhenTheEnemyFollowsToThePortal() {
        Sim sim = new Sim(config(BotConfig.FleeMode.ENEMIES_IN_SIGHT, null, null), 26, 2_000, 2_000);
        var portal = orbit.world.maps.MapCatalog.bundled().portals(26).getFirst();
        Sim at = new Sim(config(BotConfig.FleeMode.ENEMIES_IN_SIGHT, null, null), 26, portal.x() + 50, portal.y());
        player(at, 507, "Chaser", "", 1, portal.x() + 600, portal.y());
        Flee f = new Flee(at.ctx);
        ran(at, f, 3_000);
        assertTrue(at.sent.stream().anyMatch(p -> p instanceof JumpRequest), "jumped");
        assertTrue(sim.sent.isEmpty());
    }

    @Test
    void offIgnoresEveryone() {
        Sim sim = new Sim(config(BotConfig.FleeMode.OFF, null, null), 26, 10_000, 6_000);
        player(sim, 508, "Killer", "", 1, 10_300, 6_000);
        shoots(sim, 508);
        assertFalse(ran(sim, new Flee(sim.ctx), 2_000));
    }
}
