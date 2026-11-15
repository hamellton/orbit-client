package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class GateBoundsTest {

    @AfterEach
    void clear() {
        GateBounds.clear();
    }

    @Test
    void radiationInACornerMovesBothNearEdgesIn() {
        assertTrue(GateBounds.inside(52, 20_900, 13_000), "nothing known: the whole map");
        GateBounds.radiation(52, 20_500, 12_700, 21_000, 13_100);
        assertFalse(GateBounds.inside(52, 20_600, 12_000));
        assertFalse(GateBounds.inside(52, 15_000, 12_800));
        assertTrue(GateBounds.inside(52, 20_000, 12_000));
        assertTrue(GateBounds.inside(52, 500, 500), "the far edges untouched");
    }

    @Test
    void radiationByOneSideMovesOnlyThatEdge() {
        GateBounds.radiation(52, 20_700, 6_500, 21_000, 13_100);
        assertFalse(GateBounds.inside(52, 20_650, 6_500));
        assertTrue(GateBounds.inside(52, 20_000, 12_900), "the bottom edge untouched");
    }
}
