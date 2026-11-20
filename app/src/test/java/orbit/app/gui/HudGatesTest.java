package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import orbit.app.WorldFeed;
import orbit.protocol.gen.GalaxyGateBuilderGateModule;
import orbit.protocol.gen.GalaxyGateBuilderInfoCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.world.World;
import org.junit.jupiter.api.Test;

/** The Gates card: how the bot shows a galaxy gate's build. */
class HudGatesTest {

    static World world(int map) {
        World w = new World();
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = map;
        si.factionId = 3;
        si.hitPoints = 100;
        si.hitMax = 100;
        w.apply(si, 1);
        GalaxyGateBuilderInfoCommand c = new GalaxyGateBuilderInfoCommand();
        c.money = 297_546;
        c.samples = 9_546;
        List<GalaxyGateBuilderGateModule> gates = new ArrayList<>();
        int[][] gb2 = {{1, 5, -1, 0}, {2, 45, -1, 0}, {3, 82, 3, 12}, {6, 0, -1, 0}, {7, 0, -1, 0}, {19, 100, 5, 0}};
        for (int[] g : gb2) {
            GalaxyGateBuilderGateModule m = new GalaxyGateBuilderGateModule();
            m.id = (byte) g[0];
            m.currPart = (short) g[1];
            m.livesLeft = g[2];
            m.currWave = (short) g[3];
            gates.add(m);
        }
        c.gates = gates;
        w.apply(c, 2);
        return w;
    }

    @Test
    void inAGateTheCardShowsTheBuilderAndTheGateHere() throws Exception {
        World w = world(53);
        Hud hud = new Hud(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        var cards = hud.blocks(w.snapshot(3), new WorldFeed("test"), "врата абг гб2");
        Hud.Card card = cards.get(ViewSettings.Block.GATES);
        assertNotNull(card);
        int width = 300;
        BufferedImage img = new BufferedImage(width + 20, card.height(width) + 20, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        card.paint(g, 10, 10, width);
        g.dispose();
        String out = System.getProperty("hud.png", "build/hud-gates.png");
        if (out != null) {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(out));
        }
    }

    @Test
    void noGateModuleOutOfAGateNoCard() {
        World w = world(MapId.HOME);
        Hud hud = new Hud(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        assertNull(hud.blocks(w.snapshot(3), new WorldFeed("test"), "x").get(ViewSettings.Block.GATES));
    }

    static final class MapId {
        static final int HOME = orbit.world.maps.MapCatalog.bundled().byName("3-1").id();
    }
}
