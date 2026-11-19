package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SidePanelTest {

    @Test
    void aCardGrowsAtOnceAndShrinksOnlyWhenItStaysSmaller() {
        SidePanel.Slot s = new SidePanel.Slot();
        SidePanel.steady(s, 100, 0);
        SidePanel.steady(s, 140, 1_000);
        assertEquals(140, s.height, "grows at once");
        SidePanel.steady(s, 100, 2_000);
        SidePanel.steady(s, 140, 5_000); // back before the hold ran out
        SidePanel.steady(s, 100, 6_000);
        SidePanel.steady(s, 100, 12_000);
        assertEquals(140, s.height, "a row that comes and goes keeps its room");
        SidePanel.steady(s, 100, 16_500);
        assertEquals(100, s.height, "smaller for longer than the hold: the room given back");
    }

    @Test
    void theRightColumnHasTheFightThenTheResultsSkylabAndEnrichment() {
        // the user, 2026-11-19: «итоги, скайлаб и обогащение — в правой колонке, под целями и атакующими»
        java.util.List<ViewSettings.Block> right = java.util.Arrays.stream(ViewSettings.Block.values())
                .filter(b -> b.rightColumn() >= 0)
                .sorted(java.util.Comparator.comparingInt(ViewSettings.Block::rightColumn)).toList();
        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(ViewSettings.Block.TARGET,
                ViewSettings.Block.ATTACKERS, ViewSettings.Block.STATS, ViewSettings.Block.SKYLAB, ViewSettings.Block.LAB,
                ViewSettings.Block.BOOSTERS, ViewSettings.Block.GROUP), right);
    }
}
