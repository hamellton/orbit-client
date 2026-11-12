package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import orbit.world.World;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MapFeaturesTest {

    @Test
    void stationsAndObstaclesSeenAreKept(@TempDir Path dir) {
        World w = new World();
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 92;
        si.factionId = 2;
        w.apply(si, 0);
        orbit.protocol.gen.AssetCreateCommand a = new orbit.protocol.gen.AssetCreateCommand();
        a.assetId = 5;
        a.type = new orbit.protocol.gen.AssetTypeModule();
        a.type.typeValue = 52;
        a.userName = "OreTrade";
        a.posX = 9_900;
        a.posY = 6_600;
        w.apply(a, 0);
        orbit.protocol.gen.MapAddPOICommand z = new orbit.protocol.gen.MapAddPOICommand();
        z.poiId = "x";
        z.poiType = new orbit.protocol.gen.POITypeModule();
        z.poiType.typeValue = 5;
        z.shape = 0;
        z.shapeCoordinates = List.of(5_000, 5_000, 800);
        z.active = true;
        w.apply(z, 0);
        Path file = dir.resolve("f.json");
        MapFeatures f = new MapFeatures(file);
        assertTrue(f.note(w.snapshot(0)));
        assertFalse(f.note(w.snapshot(0)), "nothing new the second time");
        MapFeatures again = new MapFeatures(file);
        assertEquals(1, again.stations(92).size());
        assertEquals("OreTrade", again.stations(92).getFirst().name());
        assertEquals(0, again.stations(92).getFirst().faction());
        assertEquals(List.of(5_000, 5_000, 800), again.obstacles(92).getFirst().coords());
    }
}
