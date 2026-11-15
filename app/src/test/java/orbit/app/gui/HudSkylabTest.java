package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.Map;
import orbit.auth.Skylab;
import org.junit.jupiter.api.Test;

class HudSkylabTest {

    static Skylab.Info sample() {
        return new Skylab.Info(List.of(new Skylab.Module("baseModule", 20, true),
                new Skylab.Module("transportModule", 1, true), new Skylab.Module("sepromRefinery", 20, false)),
                List.of(new Skylab.Ore("prometium", 1_234_567, 20_000_000), new Skylab.Ore("seprom", 42, 9_000)),
                false, Map.of("prometiumCollector", 0), 1_700_000_000_000L);
    }

    @Test
    void theCardShowsTheReadSkylab() throws Exception {
        Hud hud = new Hud(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        assertNull(hud.skylab(null), "not read yet: no card");
        Hud.Card card = hud.skylab(sample());
        assertNotNull(card);
        int h = card.height(360);
        assertTrue(h > 60, "height " + h);
        BufferedImage img = new BufferedImage(360, h + 20, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        card.paint(g, 0, 0, 360);
        g.dispose();
        String out = System.getProperty("skylab.png");
        if (out != null) {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(out));
        }
    }
}
