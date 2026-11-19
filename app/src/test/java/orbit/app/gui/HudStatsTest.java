package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import org.junit.jupiter.api.Test;

/** The user, 2026-11-19: «в итогах почему я вижу данные, а потом снизу — ещё; по нажатию на ещё открывать все». */
class HudStatsTest {

    static Hud.Card stats(Hud hud, orbit.app.WorldFeed feed) {
        return hud.blocks(feed.snapshot(), feed, "x").get(ViewSettings.Block.STATS);
    }

    static void paint(Hud.Card c) {
        BufferedImage img = new BufferedImage(360, c.height(360) + 10, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        c.paint(g, 0, 0, 360);
        g.dispose();
    }

    /** The point of the card's «ещё N» / «свернуть», {@code null} none. */
    static java.awt.Point link(Hud.Card c) {
        for (int y = 0; y < c.height(360); y++) {
            java.awt.Point p = new java.awt.Point(20, y);
            if (c.clickable(p)) {
                return p;
            }
        }
        return null;
    }

    @Test
    void moreOpensAllTheLootAndFoldsBack() {
        orbit.app.WorldFeed feed = new orbit.app.WorldFeed("hud-stats-test");
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.hitPoints = 100;
        si.hitMax = 100;
        feed.onPacket(si, 0);
        for (int i = 0; i < 12; i++) {
            orbit.protocol.gen.LegacyModule l = new orbit.protocol.gen.LegacyModule();
            l.message = "0|LM|ST|LOT|resource_collectable_item-" + i + "|" + (100 - i);
            feed.onPacket(l, 1 + i);
        }
        Hud hud = new Hud(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        Hud.Card shortCard = stats(hud, feed);
        paint(shortCard);
        int shortH = shortCard.height(360);
        java.awt.Point more = link(shortCard);
        assertNotNull(more, "«ещё 4» is there to click");
        assertTrue(shortCard.click(more));
        Hud.Card all = stats(hud, feed);
        paint(all);
        int line = all.height(360) - shortH;
        assertTrue(line > 0, "taller with all the loot");
        java.awt.Point less = link(all);
        assertNotNull(less, "«свернуть» at the end");
        assertTrue(all.click(less));
        Hud.Card again = stats(hud, feed);
        assertEquals(shortH, again.height(360), "short again");
    }
}
