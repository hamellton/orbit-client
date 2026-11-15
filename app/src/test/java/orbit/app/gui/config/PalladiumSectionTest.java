package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

class PalladiumSectionTest {

    @Test
    void theNpcAvoidSwitchWritesTheFleeFlagOnlyWhenChanged() {
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.PALLADIUM);
        PalladiumSection s = new PalladiumSection();
        s.load(c);
        assertEquals(c.flee().npcs(), s.apply(c).flee().npcs(), "untouched: as it was");
        s.npcAvoid.setSelected(!c.flee().npcs());
        assertEquals(!c.flee().npcs(), s.apply(c).flee().npcs());
        s.npcAvoid.setSelected(false);
        assertFalse(s.apply(c).flee().npcs(), "off: just collect");
    }
}
