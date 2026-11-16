package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertSame;

import java.awt.Font;
import org.junit.jupiter.api.Test;

class HudFontsTest {

    @Test
    void aFontIsDerivedOnceNotOnEveryFrame() {
        // on Windows each derived style of FlatLaf's composite base font is a new composite with its own glyph caches:
        // derived per frame they filled 3.6 GB in an hour on the VPS (2026-11-16)
        Hud hud = new Hud(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        assertSame(hud.font(Font.BOLD, 11f), hud.font(Font.BOLD, 11f));
        assertSame(hud.font(Font.PLAIN, 12f), hud.font(Font.PLAIN, 12f));
    }
}
