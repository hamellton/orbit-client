package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import org.junit.jupiter.api.Test;

class StatsTest {

    @Test
    void sinceAResetCountsFromIt() {
        Stats s = new Stats();
        s.kills.set(10);
        s.cubesKilled.set(3);
        s.cubeFought(1);
        s.cubeFought(2);
        s.picked("палладий");
        Stats base = s.copy();
        s.kills.incrementAndGet();
        s.cubeFought(3);
        s.picked("палладий");
        s.picked("бонус-бокс");
        Stats d = s.since(base);
        assertEquals(1, d.kills.get());
        assertEquals(0, d.cubesKilled.get());
        assertEquals(1, d.cubesFought());
        assertEquals(Map.of("палладий", 1, "бонус-бокс", 1), d.pickedSoFar());
        assertEquals(11, s.kills.get(), "the bot's own counters go on");
    }

    @Test
    void rewardsSinceABase() {
        var base = new orbit.world.WorldSnapshot.Rewards(100, 10, 1, 1_000, Map.of("ammunition_laser_mcb-25", 50.0));
        var now = new orbit.world.WorldSnapshot.Rewards(150, 12, 1, 1_500, Map.of("ammunition_laser_mcb-25", 70.0,
                "log_disk", 1.0));
        var d = now.minus(base);
        assertEquals(50, d.credits());
        assertEquals(2, d.uridium());
        assertEquals(0, d.honor());
        assertEquals(Map.of("ammunition_laser_mcb-25", 20.0, "log_disk", 1.0), d.items());
    }
}
