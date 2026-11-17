package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import orbit.app.WorldFeed;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.ShipInitializationCommand;
import org.junit.jupiter.api.Test;

class MinimapTrailTest {

    @Test
    void theShipsWayOfTheLastSecondsIsDrawnBehindIt() {
        // DarkBot's TrailDrawer (the user, 2026-11-17: the overlays as in DarkBot)
        WorldFeed feed = new WorldFeed("тест");
        long[] now = {1_000_000_000L};
        feed.setClock(() -> now[0]);
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 1;
        si.x = 2_000;
        si.y = 6_500;
        si.speed = 1_000;
        si.hitPoints = 1;
        si.hitMax = 1;
        feed.onPacket(si, now[0]);
        MoveRequest m = new MoveRequest();
        m.targetX = 12_000;
        m.targetY = 6_500;
        m.positionX = 2_000;
        m.positionY = 6_500;
        feed.onSent(m, now[0]);
        MinimapPanel panel = new MinimapPanel(feed);
        panel.setSize(1000, 624);
        BufferedImage img = null;
        for (int i = 0; i < 9; i++) {
            img = new BufferedImage(1000, 624, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            panel.paint(g);
            g.dispose();
            now[0] += 1_000_000_000L;
        }
        // the way from x 2 000 to about 10 000 at y 6 500: half-way along it, x 5 000
        double sx = (1000 - 16.0) / 21_000;
        double sy = (624 - 16.0) / 13_100;
        double s = Math.min(sx, sy);
        double ox = (1000 - 21_000 * s) / 2;
        double oy = (624 - 13_100 * s) / 2;
        int px = (int) Math.round(ox + 5_000 * s);
        int py = (int) Math.round(oy + 6_500 * s);
        int brightest = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int rgb = img.getRGB(px, py + dy);
            brightest = Math.max(brightest, ((rgb >> 16) & 255) + ((rgb >> 8) & 255) + (rgb & 255));
        }
        assertTrue(brightest > 3 * 90, "a light line along the way: " + brightest);
    }
}
