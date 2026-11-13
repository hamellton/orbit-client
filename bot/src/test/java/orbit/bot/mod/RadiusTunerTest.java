package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RadiusTunerTest {

    @AfterEach
    void forget() {
        RadiusTuner.forget();
    }

    @Test
    void fartherWhileTheTargetHitsBackNearerOnceTheLasersMiss() {
        RadiusTuner.testing = true;
        String k = "-=[ Tuned ]=-";
        assertEquals(560, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 560, 10, 0.6, true);      // hit back most of the fight
        assertEquals(590, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 590, 10, 0.1, true);      // hardly: stays
        assertEquals(590, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 590, 10, 0.5, true);
        assertEquals(620, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 620, 10, 0.5, false);     // the lasers no longer reach
        assertEquals(580, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 580, 10, 0.9, true);      // not past the limit found
        assertEquals(580, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 700, 10, 0.9, true);      // fought at another distance: nothing learned
        assertEquals(580, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 580, 3, 0.9, false);      // too short to blame the range
        assertEquals(580, RadiusTuner.radius(k, 560));
    }

    @Test
    void shortFightsAreSummed() {
        RadiusTuner.testing = true;
        String k = "-=[ Quick ]=-";
        RadiusTuner.learn(k, 560, 560, 4, 0.5, true);
        RadiusTuner.learn(k, 560, 560, 4, 0.5, true);
        assertEquals(560, RadiusTuner.radius(k, 560), "not yet enough");
        RadiusTuner.learn(k, 560, 560, 4, 0.4, true);
        assertEquals(590, RadiusTuner.radius(k, 560), "12 s, hit 47 % of it");
        RadiusTuner.learn(k, 560, 590, 5, 0.1, true);
        RadiusTuner.learn(k, 560, 590, 5, 0.1, true);
        RadiusTuner.learn(k, 560, 590, 5, 0.1, true);
        assertEquals(590, RadiusTuner.radius(k, 560), "hardly hit: stays");
    }

    @Test
    void aNewBaseStartsAgainFromItTheLimitKept() {
        // the user, 2026-11-13: an own radius in the list is where the learning starts, it goes on
        RadiusTuner.testing = true;
        String k = "-=[ Based ]=-";
        RadiusTuner.learn(k, 560, 560, 10, 0.6, true);
        assertEquals(590, RadiusTuner.radius(k, 560));
        RadiusTuner.learn(k, 560, 590, 10, 0.6, false);  // the lasers no longer reach: limit 550
        assertEquals(550, RadiusTuner.radius(k, 560));
        assertEquals(500, RadiusTuner.radius(k, 500), "the user set 500: from there");
        RadiusTuner.learn(k, 500, 500, 10, 0.6, true);
        assertEquals(530, RadiusTuner.radius(k, 500));
        RadiusTuner.learn(k, 500, 530, 10, 0.6, true);
        assertEquals(530, RadiusTuner.radius(k, 500), "not past the limit found before");
        assertEquals(550, RadiusTuner.radius(k, 700), "a base past the limit: the limit");
    }
}
