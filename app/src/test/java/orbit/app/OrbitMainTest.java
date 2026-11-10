package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.List;
import orbit.app.gui.MainWindow;
import orbit.protocol.gen.JumpgateCreateCommand;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import org.junit.jupiter.api.Test;

class OrbitMainTest {

    @Test
    void optionsAreParsed() {
        OrbitMain.Options o = OrbitMain.Options.parse(List.of("--account", "main", "--headless", "--direct", "1.2.3.4:6001-6007",
                "--speed", "2", "--at", "30", "recordings/x.dorec"));
        assertEquals("main", o.account());
        assertTrue(o.headless());
        assertEquals("1.2.3.4:6001-6007", o.direct());
        assertEquals(2.0, o.speed());
        assertEquals(30.0, o.at());
        assertEquals(List.of("recordings/x.dorec"), o.positional());
        assertEquals(Path.of("local", "vault.bin"), o.vault());
    }

    @Test
    void minimapDrawsHeroShipsAndPortalsWithoutAWindow() {
        System.setProperty("java.awt.headless", "true");
        WorldFeed feed = new WorldFeed("тест");
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 9;
        si.x = 10500;
        si.y = 6550;
        si.factionId = 3;
        feed.onPacket(si, 0);
        JumpgateCreateCommand gate = new JumpgateCreateCommand();
        gate.gateId = 5;
        gate.x = 2000;
        gate.y = 2000;
        gate.isVisibleOnMinimap = true;
        feed.onPacket(gate, 0);
        ShipCreateCommand npc = new ShipCreateCommand();
        npc.userId = 7;
        npc.npc = true;
        npc.x = 15750;
        npc.y = 6550;
        feed.onPacket(npc, 0);
        feed.setClock(() -> 0);

        BufferedImage img = MainWindow.renderMinimap(feed, 840, 524);
        // map 21000x13100 scaled into 840x524 with 8 px padding: scale 0.03878, x = 12.8 + mapX * scale
        int heroPixel = img.getRGB(420, 262) & 0xffffff;
        int npcPixel = img.getRGB(623, 262) & 0xffffff;
        int empty = img.getRGB(300, 400) & 0xffffff;
        assertEquals(0x4fd1ff, heroPixel, "hero in the middle of the map");
        assertEquals(0xff4d4d, npcPixel, "NPC three quarters to the right");
        assertNotEquals(heroPixel, empty);
    }
}
