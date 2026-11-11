package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalTime;
import java.util.List;
import orbit.config.Json;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class CubeMapsTest {

    private static final long MIN = 60_000;
    private static final LocalTime NOON = LocalTime.of(12, 0);

    private static JsonNode settings(String json) throws Exception {
        return Json.MAPPER.readTree(json);
    }

    @Test
    void mapsComeFromTheTimetableThenTheListThenTheWorkingMap() throws Exception {
        assertEquals(List.of(22), CubeMaps.candidates(settings("{}"), 22, NOON));
        JsonNode s = settings("""
                {"maps": [18, 22, 99],
                 "mapSchedule": [{"from": "08:00", "to": "11:00", "maps": [26]},
                                 {"from": "22:00", "to": "02:00", "maps": [18, 26]}]}""");
        assertEquals(List.of(18, 22), CubeMaps.candidates(s, 22, NOON), "not x-6 ids are dropped");
        assertEquals(List.of(26), CubeMaps.candidates(s, 22, LocalTime.of(9, 30)));
        assertEquals(List.of(18, 26), CubeMaps.candidates(s, 22, LocalTime.of(1, 0)), "a window over midnight");
        assertEquals(List.of(18, 22), CubeMaps.candidates(s, 22, LocalTime.of(11, 0)), "the end is not inside");
    }

    @Test
    void inTurnTheCampMovesOnWhenACubeDies() throws Exception {
        CubeMaps maps = new CubeMaps(MapCatalog.bundled());
        JsonNode s = settings("{\"maps\": [18, 22, 26], \"mapMode\": \"ROUND\"}");
        SpawnMemory memory = SpawnMemory.inMemory();
        assertEquals(22, maps.choose(s, 22, 22, 7500, 4000, 2, false, false, memory, 0, NOON), "starts where the ship is");
        maps.killedOn(s, 22, 22, NOON);
        assertEquals(26, maps.choose(s, 22, 22, 7500, 4000, 2, false, false, memory, 0, NOON));
        maps.killedOn(s, 22, 26, NOON);
        assertEquals(18, maps.choose(s, 22, 26, 7500, 4000, 2, false, false, memory, 0, NOON), "and round again");
    }

    @Test
    void soonestGoesWhereTheCubeIsDueFirstCountingTheWay() throws Exception {
        CubeMaps maps = new CubeMaps(MapCatalog.bundled());
        JsonNode s = settings("{\"maps\": [22, 26]}");
        SpawnMemory memory = SpawnMemory.inMemory();
        long t0 = 10 * 60 * MIN;
        // 2-6: a cube died a minute ago and comes back in 30 minutes (one full cycle learned)
        memory.seen(22, 7500, 4000, t0 - 40 * MIN);
        memory.killed(22, 7500, 4000, t0 - 31 * MIN);
        memory.seen(22, 7500, 4000, t0 - 2 * MIN);
        memory.killed(22, 7500, 4000, t0 - MIN);
        long here = CubeMaps.expectedCube(22, memory, t0);
        assertTrue(here > t0 + 20 * MIN, "2-6 is due late: " + (here - t0) / MIN + " min");
        // 3-6 never seen: a cube may be there now; the way is a few jumps, far less than half an hour
        assertEquals(26, maps.choose(s, 22, 22, 7500, 4000, 2, false, false, memory, t0, NOON));
        assertTrue(maps.travelMs(26, 22, 7500, 4000, 2) < 20 * MIN);
    }

    @Test
    void aLiveCubeKeepsTheShipAndASmallGainDoesNotMoveIt() throws Exception {
        CubeMaps maps = new CubeMaps(MapCatalog.bundled());
        JsonNode s = settings("{\"maps\": [22, 26]}");
        SpawnMemory memory = SpawnMemory.inMemory();
        assertEquals(22, maps.choose(s, 22, 22, 7500, 4000, 2, true, false, memory, 0, NOON), "a cube is here");
        // both unknown: here costs nothing, there costs the way — stay
        assertEquals(22, maps.choose(s, 22, 22, 7500, 4000, 2, false, false, memory, 0, NOON));
    }

    @Test
    void aCubeUnderFireIsFinishedEvenWhenTheTimetableMovesOn() throws Exception {
        // live 2026-11-11: the window switched to 1-6 while the ship was circling a cube on 2-6 — it left at once
        CubeMaps maps = new CubeMaps(MapCatalog.bundled());
        JsonNode s = settings("{\"mapSchedule\": [{\"from\": \"00:00\", \"to\": \"23:59\", \"maps\": [18]}]}");
        SpawnMemory memory = SpawnMemory.inMemory();
        assertEquals(22, maps.choose(s, 22, 22, 7500, 4000, 2, true, true, memory, 0, NOON), "the fight first");
        assertEquals(18, maps.choose(s, 22, 22, 7500, 4000, 2, false, false, memory, 0, NOON), "then the timetable");
    }

    @Test
    void belowLevel24TheWayLeavesTheBlacklightMapsOut() {
        MapCatalog maps = MapCatalog.bundled();
        java.util.Set<Integer> bl = java.util.Set.of(306, 307, 308);
        for (int from : new int[] {18, 22, 26}) {
            for (int to : new int[] {18, 22, 26}) {
                if (from == to) {
                    continue;
                }
                var low = orbit.bot.nav.Router.route(maps, from, 10_000, 6_500, to, 2, 23);
                assertTrue(!low.isEmpty(), from + " → " + to + " is reachable without Blacklight");
                assertTrue(low.stream().noneMatch(p -> bl.contains(p.toId())), from + " → " + to + ": " + low);
            }
        }
    }
}
