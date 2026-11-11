package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;
import orbit.app.WorldFeed;
import orbit.bot.act.Actions;
import orbit.bot.nav.Navigator;
import orbit.bot.nav.Traveler;
import orbit.bot.run.BotContext;
import orbit.bot.run.BotRunner;
import orbit.bot.run.ShipSetup;
import orbit.bot.run.Stats;
import orbit.config.BotConfig;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class MinimapOffendersTest {

    @Test
    void aRememberedPlayerIsDrawnWithHisCircleAndName() throws Exception {
        WorldFeed feed = new WorldFeed("бот");
        long t = System.nanoTime();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 93;
        si.factionId = 3;
        si.x = 20_000;
        si.y = 7_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        feed.onPacket(si, t);
        orbit.protocol.gen.ShipCreateCommand c = new orbit.protocol.gen.ShipCreateCommand();
        c.userId = 77;
        c.userName = "Hunter";
        c.factionId = 1;
        c.x = 22_000;
        c.y = 7_000;
        feed.onPacket(c, t);
        orbit.protocol.gen.AttackLaserRunCommand run = new orbit.protocol.gen.AttackLaserRunCommand();
        run.attackerId = 77;
        run.targetId = 1;
        feed.onPacket(run, t);

        BotConfig config = BotConfig.empty().withModule(BotConfig.Module.PALLADIUM)
                .withFlee(new BotConfig.Flee(BotConfig.FleeMode.OFF, null, null, 2_000, 60, true, 90, true, 2_500));
        Random random = new Random(1);
        MapCatalog maps = MapCatalog.bundled();
        Navigator nav = new Navigator(feed::snapshot, p -> { }, maps, random, System::nanoTime);
        Actions actions = new Actions(feed::snapshot, p -> { }, () -> new Actions.Login(1, "s"), Actions.Screen.DEFAULT, random);
        BotContext ctx = new BotContext(feed::snapshot, () -> config, actions, nav,
                new Traveler(feed::snapshot, p -> { }, nav, maps, random, System::nanoTime), maps, random, System::nanoTime,
                (a, b) -> { }, new ShipSetup(feed::snapshot, actions, System::nanoTime, random), null, new Stats());
        BotRunner runner = new BotRunner(ctx, () -> false, s -> { });
        feed.setRunner(runner);
        runner.tick();
        assertTrue(!runner.avoidedPlayers().isEmpty(), "remembered and in sight");

        java.awt.image.BufferedImage img = MinimapPanel.render(feed, 900, 600);
        String out = System.getProperty("orbit.minimap.out");
        if (out != null) {
            javax.imageio.ImageIO.write(img, "png", new java.io.File(out));
        }
        boolean orange = false;
        for (int y = 0; y < img.getHeight() && !orange; y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                if ((img.getRGB(x, y) & 0xffffff) == (MinimapPanel.OFFENDER.getRGB() & 0xffffff)) {
                    orange = true;
                    break;
                }
            }
        }
        assertTrue(orange, "the circle is drawn");
    }
}
