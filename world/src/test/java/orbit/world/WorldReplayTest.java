package orbit.world;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.net.Recording.PacketRecord;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.MoveRequest;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Replays the local recordings through {@link World} and checks the hero position prediction. */
class WorldReplayTest {

    /**
     * The client reports the position it shows in every {@code MoveRequest}: the prediction made
     * before each request must match it.
     */
    @Test
    void heroPredictionMatchesClientPositions() throws Exception {
        List<Path> files = recordings();
        Assumptions.assumeFalse(files.isEmpty(), "no recordings");
        List<Double> errors = new ArrayList<>();
        int jumps = 0;
        int maxShips = 0;
        int maxNpcs = 0;
        int maxCollectables = 0;
        int maxPortals = 0;
        int maxZones = 0;
        int lockedByOther = 0;
        List<String> problems = new ArrayList<>();
        for (Path f : files) {
            World w = new World();
            for (PacketRecord r : Recording.readPackets(f)) {
                Packet p = PacketCodec.decode(r.bytes());
                if (r.up()) {
                    WorldSnapshot.Hero h = w.snapshot(r.nanos()).hero();
                    if (p instanceof MoveRequest m && h.id() != 0) {
                        double e = Math.hypot(h.x() - m.positionX, h.y() - m.positionY);
                        if (e < World.BEACON_JUMP) {
                            errors.add(e);
                        } else {
                            jumps++; // the first request after a map change still uses the old map's position
                        }
                    }
                    w.applyOutgoing(p, r.nanos());
                    continue;
                }
                w.apply(p, r.nanos());
                WorldSnapshot s = w.snapshot(r.nanos());
                maxShips = Math.max(maxShips, s.ships().size());
                maxNpcs = Math.max(maxNpcs, s.nearest(WorldSnapshot.ShipKind.NPC).size());
                maxCollectables = Math.max(maxCollectables, s.collectables().size());
                maxPortals = Math.max(maxPortals, s.portals().size());
                maxZones = Math.max(maxZones, s.zones().size());
                lockedByOther = Math.max(lockedByOther, (int) s.ships().values().stream().filter(WorldSnapshot.Ship::lockedByOther).count());
            }
            WorldSnapshot end = w.snapshot(Long.MAX_VALUE / 2);
            WorldSnapshot.Hero h = end.hero();
            if (end.selected("lasers") == null || end.selected("lasers").counter() <= 0) {
                problems.add(f.getFileName() + ": no selected laser ammunition");
            }
            // free space below zero is an overfull hold (ru1, 2026-11-11: -11175 of 1600), not a model error
            if (end.inventory().ores().isEmpty() || h.cargoMax() <= 0 || h.cargoFree() > h.cargoMax()) {
                problems.add(f.getFileName() + ": cargo " + h.cargoFree() + "/" + h.cargoMax() + ", ores " + end.inventory().ores());
            }
            if (h.config() != 1 && h.config() != 2) {
                problems.add(f.getFileName() + ": config " + h.config());
            }
            if (end.inventory().bootyKeys().isEmpty()) {
                problems.add(f.getFileName() + ": no booty keys");
            }
        }
        Collections.sort(errors);
        double median = errors.get(errors.size() / 2);
        double p90 = errors.get((int) (errors.size() * 0.9));
        System.out.printf("moves=%d jumps=%d median=%.0f p90=%.0f max=%.0f ships=%d npcs=%d collectables=%d portals=%d zones=%d locked=%d%n",
                errors.size(), jumps, median, p90, errors.getLast(), maxShips, maxNpcs, maxCollectables, maxPortals, maxZones,
                lockedByOther);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(lockedByOther > 0, "recordings contain NPCs attacked by other players");
        assertTrue(errors.size() > 100, "too few moves: " + errors.size());
        assertTrue(jumps <= files.size(), "unexpected jumps: " + jumps);
        assertTrue(median < 20 && p90 < 80, "prediction too far from the client: median " + median + ", p90 " + p90);
        assertTrue(maxShips > 0 && maxNpcs > 0 && maxCollectables > 0 && maxPortals > 0);
    }

    private static List<Path> recordings() throws Exception {
        Path dir = Path.of(System.getProperty("orbit.recordings", "recordings"));
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.toString().endsWith(".dorec")).sorted().toList();
        }
    }
}
