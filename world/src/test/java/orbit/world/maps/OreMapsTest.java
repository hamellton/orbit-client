package orbit.world.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class OreMapsTest {

    @Test
    void theBundledStartKnowsTheLowerMapsOres() {
        // the wikis: prometium x-1 and x-2, endurium x-1 … x-4, terbium x-2 … x-4; palladium on 5-3
        OreMaps m = OreMaps.shared();
        assertEquals(Set.of(1, 5, 9, 2, 6, 10), m.mapsWith(0));
        assertTrue(m.mapsWith(1).containsAll(Set.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12)));
        assertTrue(m.mapsWith(2).containsAll(Set.of(2, 3, 4, 6, 7, 8, 10, 11, 12)));
        assertTrue(m.mapsWith(8).contains(93));
    }

    @Test
    void whatIsSeenIsLearned() {
        OreMaps m = OreMaps.inMemory();
        assertTrue(m.learn(7, 2));
        assertTrue(!m.learn(7, 2), "known already");
        assertEquals(Set.of(7), m.mapsWith(2));
    }
}
