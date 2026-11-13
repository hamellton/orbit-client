package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

class NavigatorRelaxTest {

    @Test
    void whenTheSoftBarsCloseEveryWayTheShipGoesThroughThemRatherThanStand() {
        // live 2026-11-13 on the VPS, 2-BL: «Нет пути… зоны сервера, чужие базы 17, запретная зона конфига» — the ship
        // stood in a corner; here the config's avoided zone is a band over the whole map
        List<String> rows = new ArrayList<>();
        for (int r = 0; r < 30; r++) {
            rows.add(r == 14 || r == 15 ? "#".repeat(30) : ".".repeat(30));
        }
        BotConfig.ZoneGrid band = new BotConfig.ZoneGrid(30, rows);
        Sim sim = new Sim(BotConfig.empty(), 26, 5_000, 2_000);
        sim.ctx.nav().setAvoidedZones(map -> band);
        sim.ctx.nav().moveTo(5_000, 12_000, true);
        sim.run(sim.ctx.nav()::tick, 40_000);
        var h = sim.hero();
        assertTrue(h.y() > 10_000, "through the band: " + h);
    }
}
