package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ZoneFrameTest {

    @Test
    void aClickFlipsOneCellBackAndForth() {
        boolean[][] l = new boolean[4][4];
        ZonesSection.frame(l, 1, 1, 1, 1, false, true);
        assertTrue(l[1][1], "an empty cell is filled");
        ZonesSection.frame(l, 1, 1, 1, 1, false, true);
        assertFalse(l[1][1], "and cleared by the same click: no other tool needed");
    }

    @Test
    void aUniformFrameFlipsAMixedOneFollowsTheButton() {
        boolean[][] l = new boolean[4][4];
        ZonesSection.frame(l, 0, 0, 2, 1, false, true);
        assertArrayEquals(new boolean[] {true, true, true, false}, l[0]);
        ZonesSection.frame(l, 0, 0, 2, 1, false, false);
        assertArrayEquals(new boolean[] {false, false, false, false}, l[0], "all filled: the frame clears them");
        l[0][0] = true;
        ZonesSection.frame(l, 0, 0, 3, 0, false, true);
        assertArrayEquals(new boolean[] {true, true, true, true}, l[0], "mixed, left button: all filled");
        l[0][1] = false;
        ZonesSection.frame(l, 0, 0, 3, 0, false, false);
        assertArrayEquals(new boolean[] {false, false, false, false}, l[0], "mixed, right button: all cleared");
    }

    @Test
    void theMiddleButtonFlipsEachCell() {
        boolean[][] l = {{true, false, true, false}};
        ZonesSection.frame(l, 0, 0, 3, 0, true, false);
        assertArrayEquals(new boolean[] {false, true, false, true}, l[0]);
    }
}
