package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.bot.run.Stats;
import orbit.protocol.gen.LegacyModule;
import orbit.world.World;
import org.junit.jupiter.api.Test;

class SessionSummaryTest {

    static void legacy(World w, String m) {
        LegacyModule l = new LegacyModule();
        l.message = m;
        w.apply(l, 0);
    }

    @Test
    void countsTheRewardLog() {
        World w = new World();
        legacy(w, "0|LM|ST|CRE|12800|5325855");
        legacy(w, "0|LM|ST|CRE|12800|5338655");
        legacy(w, "0|LM|ST|URI|16|6200");
        legacy(w, "0|LM|ST|EP|6400|7110637|11");
        legacy(w, "0|LM|ST|HON|35|2164424");
        legacy(w, "0|LM|ST|LOT|resource_collectable_luminium|5");
        legacy(w, "0|LM|ST|LOT|resource_collectable_luminium|15");
        legacy(w, "0|LM|ST|BAT|ammunition_laser_ucb-100|101");
        legacy(w, "0|LM|ST|XEN|20");
        var r = w.snapshot(0).rewards();
        assertEquals(25_600, r.credits());
        assertEquals(16, r.uridium());
        assertEquals(6_400, r.experience());
        assertEquals(35, r.honor());
        assertEquals(20, r.items().get("resource_collectable_luminium"));
        assertEquals(101, r.items().get("ammunition_laser_ucb-100"));
        assertEquals(5_338_655, w.snapshot(0).hero().credits(), "totals still follow");

        Stats s = new Stats();
        s.kills.set(3);
        s.cubeFought(77);
        s.cubeFought(77);
        s.picked("палладий");
        s.picked("палладий");
        s.picked("бонус-бокс");
        var lines = SessionSummary.lines(r, s, 0.5);
        assertTrue(lines.get(1).startsWith("Кредиты: 25 600") && lines.get(1).contains("51 200/ч"), lines.get(1));
        assertTrue(lines.contains("Кубы: бои 1, добито 0"), lines.toString());
        assertTrue(lines.contains("Убито NPC 3  (6/ч)"), lines.toString());
        assertTrue(lines.contains("Подобрано: палладий 2  (4/ч), бонус-бокс 1  (2/ч)"), lines.toString());
        assertTrue(SessionSummary.lines(r, new Stats(), 0.5).stream().noneMatch(l -> l.startsWith("Кубы")),
                "no cube line without cubes");
        assertTrue(lines.contains("В трюме люминиум: 20  (40/ч)"), lines.toString());
        assertTrue(lines.contains("UCB-100: 101  (202/ч)"), lines.toString());
        assertTrue(lines.contains("Доп. энергия: 20  (40/ч)"), lines.toString()); // XEN is extra energy, not xenomit
    }
}
