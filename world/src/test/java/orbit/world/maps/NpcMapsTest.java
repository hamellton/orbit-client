package orbit.world.maps;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class NpcMapsTest {

    @Test
    void anNpcSeenOnAMapIsKnownThereOnce() {
        NpcMaps m = NpcMaps.inMemory();
        assertTrue(m.learn(26, "-=[ Protegit ]=-"));
        assertFalse(m.learn(26, "-=[ Protegit ]=-"), "already known");
        assertTrue(m.on(26).contains("-=[ Protegit ]=-"));
        assertTrue(m.on(18).isEmpty());
    }

    @Test
    void theBundledStartKnowsTheX6Cubikon() {
        // from the recorded sessions: 3-6 (26) had Cubikons, Protegits, Kristallins
        assertTrue(NpcMaps.shared().on(26).contains("-=[ Cubikon ]=-"), NpcMaps.shared().on(26).toString());
    }
}
