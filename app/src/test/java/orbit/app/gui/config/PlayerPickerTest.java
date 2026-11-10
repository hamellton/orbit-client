package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerPickerTest {

    @Test
    void appendsOnlyNewNames() {
        assertEquals("Alpha\nBeta\n[XYZ]", PlayerPicker.append("Alpha\nBeta", List.of("alpha", "[XYZ]", "Beta")));
        assertEquals("Gamma", PlayerPicker.append("  ", List.of("Gamma")));
    }

    @Test
    void noFeedsNoRows() {
        assertEquals(List.of(), PlayerPicker.rows(List.of(), 0));
    }
}
