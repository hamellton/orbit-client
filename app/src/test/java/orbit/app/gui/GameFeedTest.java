package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import orbit.world.World;
import org.junit.jupiter.api.Test;

class GameFeedTest {

    @Test
    void theGamesLinesInItsWords() {
        assertEquals("Вы получили 26 880 опыта", MinimapPanel.noteText(new World.GameNote(0, "experience", 26_880, null)));
        assertEquals("Вы получили 100 уридия", MinimapPanel.noteText(new World.GameNote(0, "uridium", 100, null)));
        assertEquals("Потрачено 500 уридия", MinimapPanel.noteText(new World.GameNote(0, "uridium", -500, null)));
        assertEquals("-=- 5 -=-", MinimapPanel.noteText(new World.GameNote(0, null, 0, "-=- 5 -=-")));
    }

    @Test
    void theWorldKeepsTheGamesLines() {
        World w = new World();
        orbit.protocol.gen.LegacyModule lm = new orbit.protocol.gen.LegacyModule();
        lm.message = "0|LM|ST|URI|100|260711";
        w.apply(lm, 5);
        lm = new orbit.protocol.gen.LegacyModule();
        lm.message = "0|A|STD| -=- 5 -=- ";
        w.apply(lm, 6);
        var notes = w.gameNotes();
        assertEquals(2, notes.size());
        assertEquals("uridium", notes.get(0).item());
        assertEquals(100, notes.get(0).amount());
        assertEquals("-=- 5 -=-", notes.get(1).text());
    }

    private static java.awt.image.BufferedImage cell(int width, int height, boolean notes) {
        orbit.app.WorldFeed feed = new orbit.app.WorldFeed("тест");
        feed.setClock(() -> 2_000_000_000L);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 10_000;
        si.y = 6_500;
        si.hitPoints = 1;
        si.hitMax = 1;
        feed.onPacket(si, 1_000_000_000L);
        if (notes) {
            for (String m : new String[] {"0|LM|ST|EP|26880|1", "0|LM|ST|URI|100|260711", "0|LM|ST|CRE|5000|9"}) {
                orbit.protocol.gen.LegacyModule lm = new orbit.protocol.gen.LegacyModule();
                lm.message = m;
                feed.onPacket(lm, 1_900_000_000L);
            }
        }
        MinimapPanel panel = new MinimapPanel(feed, true, () -> "конфиг");
        panel.setSize(width, height);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = img.createGraphics();
        panel.paint(g);
        g.dispose();
        return img;
    }

    private static int differing(java.awt.image.BufferedImage a, java.awt.image.BufferedImage b) {
        int n = 0;
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                n += a.getRGB(x, y) != b.getRGB(x, y) ? 1 : 0;
            }
        }
        return n;
    }

    @Test
    void theAllGridShowsTheGamesLinesAsTheAccountsTab() throws Exception {
        java.awt.image.BufferedImage big = cell(640, 400, true);
        if (Boolean.getBoolean("orbit.saveCells")) {
            javax.imageio.ImageIO.write(big, "png", new java.io.File("build/cell-big.png"));
            javax.imageio.ImageIO.write(cell(800, 560, true), "png", new java.io.File("build/cell-tall.png"));
        }
        org.junit.jupiter.api.Assertions.assertTrue(differing(big, cell(640, 400, false)) > 200, "lines in a big cell");
        org.junit.jupiter.api.Assertions.assertTrue(differing(cell(420, 262, true), cell(420, 262, false)) > 200,
                "and in a small one");
    }

    @Test
    void npcNamesWithoutTheGamesFrame() {
        assertEquals("Invoke XVI", InfoWindows.npcName("\\\\ Invoke XVI //"));
        assertEquals("Cubikon", InfoWindows.npcName("-=[ Cubikon ]=-"));
        assertEquals("Boss Kristallin", InfoWindows.npcName("..::{ Boss Kristallin }::.."));
        assertEquals("Mindfire Behemoth", InfoWindows.npcName("Mindfire Behemoth"));
    }
}
