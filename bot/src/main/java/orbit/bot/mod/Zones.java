package orbit.bot.mod;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.config.BotConfig;
import orbit.world.maps.MapCatalog;

/** The config's zones of a map (DarkBot's preferred and avoided zones), for roaming and picking. */
final class Zones {

    private Zones() {
    }

    /** A random point in a random cell of the map's preferred zone, or {@code null} when it has none. */
    static double[] randomPreferred(BotConfig c, MapCatalog.MapInfo map, Random random) {
        BotConfig.Zones z = c.zones().get(map.id());
        if (z == null || z.preferred() == null || z.preferred().isEmpty()) {
            return null;
        }
        BotConfig.ZoneGrid g = z.preferred();
        List<int[]> cells = new ArrayList<>();
        for (int y = 0; y < g.rows().size(); y++) {
            String row = g.rows().get(y);
            for (int x = 0; x < row.length(); x++) {
                if (row.charAt(x) == '#') {
                    cells.add(new int[] {x, y});
                }
            }
        }
        if (cells.isEmpty()) {
            return null;
        }
        int[] cell = cells.get(random.nextInt(cells.size()));
        double cw = (double) map.width() / g.resolution();
        double ch = (double) map.height() / g.resolution();
        // inside the cell, off its edges
        return new double[] {(cell[0] + 0.2 + 0.6 * random.nextDouble()) * cw, (cell[1] + 0.2 + 0.6 * random.nextDouble()) * ch};
    }

    /** The point lies in the map's preferred zone, or the map has none. */
    static boolean inPreferred(BotConfig c, MapCatalog.MapInfo map, double x, double y) {
        BotConfig.Zones z = c.zones().get(map.id());
        if (z == null || z.preferred() == null || z.preferred().isEmpty()) {
            return true;
        }
        return z.preferred().contains(x, y, map.width(), map.height());
    }

    /** The point lies in the map's avoided zone. */
    static boolean avoided(BotConfig c, int mapId, double x, double y, MapCatalog maps) {
        BotConfig.Zones z = c.zones().get(mapId);
        if (z == null || z.avoided() == null || z.avoided().isEmpty()) {
            return false;
        }
        MapCatalog.MapInfo m = maps.info(mapId);
        return z.avoided().contains(x, y, m.width(), m.height());
    }
}
