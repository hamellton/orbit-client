package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.protocol.gen.AttackHitCommand;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

class FightLogDamageTest {

    private static void hit(World w, int attacker, int victim, int damage) {
        AttackHitCommand h = new AttackHitCommand();
        h.attackerId = attacker;
        h.victimId = victim;
        h.damage = damage;
        h.victimHitpoints = 1;
        w.apply(h, 0);
    }

    @Test
    void theFightsLineTellsOurDamageItsShareAndWhoHitUs() {
        // the user, 2026-11-14: «можем отслеживать, сколько урона наносим и кому?»
        World w = new World();
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 307;
        si.x = 5_000;
        si.y = 5_000;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        w.apply(si, 0);
        for (int id : new int[] {77, 900}) {
            ShipCreateCommand c = new ShipCreateCommand();
            c.userId = id;
            c.npc = id == 77;
            c.userName = id == 77 ? "\\\\ Mindfire Behemoth //" : "Train";
            c.x = 5_500;
            c.y = 5_000;
            w.apply(c, 0);
        }
        hit(w, 900, 77, 9_000); // before the fight: not counted
        WorldSnapshot s0 = w.snapshot(0);
        FightLog f = new FightLog();
        f.begin(s0.ships().get(77), s0.hero(), 0);
        f.sample(s0, s0.ships().get(77), 600, "кружу", 0);
        hit(w, 1, 77, 1_000);
        hit(w, 900, 77, 3_000);
        hit(w, 900, 1, 2_500);
        WorldSnapshot s1 = w.snapshot(0);
        f.sample(s1, s1.ships().get(77), 600, "кружу", 1_000_000_000L);
        String line = f.damageLine();
        assertTrue(line.startsWith("наш урон 1 000"), line);
        assertTrue(line.contains("25% всего урона по ней (4 000)"), line);
        assertTrue(line.contains("урон по нам: Train 2 500"), line);
    }
}
