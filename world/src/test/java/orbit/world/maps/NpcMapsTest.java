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

    @org.junit.jupiter.api.Test
    void anNpcSeenWhereItDoesNotLiveIsLeftOut() throws Exception {
        // the user 2026-11-12: Boss Kristallon lives on x-7 and 4-5; 2-6's sighting came with a special event
        NpcMaps m = NpcMaps.inMemory();
        m.read(new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                "{\"only\":{\"..::{ Boss Kristallon }::..\":[23,29]}}"));
        m.learn(22, "..::{ Boss Kristallon }::..");
        m.learn(22, "-=[ Kristallon ]=-");
        m.learn(23, "..::{ Boss Kristallon }::..");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("-=[ Kristallon ]=-"), m.on(22));
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of("..::{ Boss Kristallon }::.."), m.on(23));
        // the bundled table says so
        org.junit.jupiter.api.Assertions.assertFalse(NpcMaps.shared().on(22).contains("..::{ Boss Kristallon }::.."));
        org.junit.jupiter.api.Assertions.assertTrue(NpcMaps.shared().on(29).contains("( UberKristallin )"));
    }
}
