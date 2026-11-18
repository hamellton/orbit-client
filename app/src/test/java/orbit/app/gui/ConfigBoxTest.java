package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-18: the configs chosen by folders, a folder's heading never chosen. */
class ConfigBoxTest {

    static Map<String, List<String>> folders() {
        Map<String, List<String>> f = new LinkedHashMap<>();
        f.put("Default", List.of("EBG", "Mimesis VRU 3-4"));
        f.put("Behemoth", List.of("BEHE 1", "BEHE 2"));
        f.put("Empty", List.of());
        f.put("", List.of("loose"));
        return f;
    }

    @Test
    void theFoldersAreHeadingsWithTheirConfigsUnder() {
        ConfigBox b = new ConfigBox();
        b.fill(List.of("— без конфига —"), folders(), "BEHE 2");
        assertEquals("BEHE 2", b.getSelectedItem());
        // «— без конфига —», Default, EBG, Mimesis, Behemoth, BEHE 1, BEHE 2, «Без папки», loose (the empty folder left out)
        assertEquals(9, b.getItemCount());
        assertEquals(ConfigBox.HEAD + "Behemoth", b.getItemAt(4));
    }

    @Test
    void aHeadingIsSkippedTheWayTheSelectionMoves() {
        ConfigBox b = new ConfigBox();
        b.fill(List.of(), folders(), "Mimesis VRU 3-4");
        b.setSelectedIndex(3); // down onto «Behemoth»
        assertEquals("BEHE 1", b.getSelectedItem());
        b.setSelectedIndex(3); // up from BEHE 1 onto it
        assertEquals("Mimesis VRU 3-4", b.getSelectedItem());
    }
}
