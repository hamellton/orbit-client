package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Totals window's galaxy gates tab. */
class TotalsGatesTest {

    @Test
    void aGateRowSaysPlacedDoneTheAverageRunDeathsLivesAndToday() {
        List<String[]> rows = InfoWindows.gateRows(
                Map.of("α|placed", 3, "α|done", 2, "α|seconds", 1_530, "α|deaths", 1, "α|lives", 4),
                Map.of("α|done", 5, "β|done", 1), 2.0);
        assertEquals(2, rows.size());
        assertArrayEquals(new String[] {"α", "3", "2", "1", "12 мин 45 с", "1", "4", "5"}, rows.get(0));
        assertArrayEquals(new String[] {"β", "—", "—", "—", "—", "—", "—", "1"}, rows.get(1), "only today's");
    }
}
