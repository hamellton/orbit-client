package orbit.world.maps;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.net.Recording.PacketRecord;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.BeaconCommand;
import orbit.protocol.gen.JumpInitiatedCommand;
import orbit.protocol.gen.JumpgateCreateCommand;
import orbit.protocol.gen.KillScreenRepairRequest;
import orbit.protocol.gen.ReloginCommand;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Checks the map facts against what the server sent in the local recordings. */
class MapReplayTest {

    /** {@code JumpgateCreateCommand.designId} of ordinary portals; others are gates and event portals. */
    private static final int REGULAR_PORTAL_DESIGN = 1;
    /** The hero must be this close to a portal when the server moves it to another map. */
    private static final int JUMP_RANGE = 500;

    @Test
    void portalsSizesAndTransitionsMatchTheGame() throws Exception {
        List<Path> files = recordings();
        Assumptions.assumeFalse(files.isEmpty(), "no recordings");
        MapCatalog catalog = MapCatalog.bundled();
        List<String> problems = new ArrayList<>();
        Set<String> gates = new HashSet<>();
        int transitions = 0;
        int beacons = 0;
        for (Path f : files) {
            World w = new World();
            boolean repairing = false;
            for (PacketRecord r : Recording.readPackets(f)) {
                Packet p = PacketCodec.decode(r.bytes());
                if (r.up()) {
                    repairing |= p instanceof KillScreenRepairRequest;
                    w.applyOutgoing(p, r.nanos());
                    continue;
                }
                WorldSnapshot s = w.snapshot(r.nanos());
                int map = s.hero().mapId();
                if (p instanceof JumpgateCreateCommand g && g.designId == REGULAR_PORTAL_DESIGN && gates.add(map + ":" + g.gateId)) {
                    if (catalog.portalNear(map, g.x, g.y, 1) == null) {
                        problems.add(f.getFileName() + ": portal at " + g.x + "," + g.y + " on " + catalog.info(map).name()
                                + " is not in portals.json");
                    }
                }
                if (p instanceof BeaconCommand b) {
                    beacons++;
                    if (!catalog.info(map).contains(b.positionX, b.positionY)) {
                        problems.add(f.getFileName() + ": hero at " + b.positionX + "," + b.positionY + " outside "
                                + catalog.info(map).name() + " " + catalog.info(map).width() + "x" + catalog.info(map).height());
                    }
                }
                int target = p instanceof ReloginCommand rc ? rc.mapID : p instanceof JumpInitiatedCommand ji ? ji.mapId : -1;
                if (target > 0 && !repairing) {
                    transitions++;
                    MapCatalog.Portal near = catalog.portalNear(map, s.hero().x(), s.hero().y(), JUMP_RANGE);
                    if (near == null || near.toId() != target) {
                        problems.add(f.getFileName() + ": " + catalog.info(map).name() + " → " + catalog.info(target).name()
                                + " from " + s.hero().x() + "," + s.hero().y() + ", graph says "
                                + (near == null ? "no portal near" : near.toName()));
                    }
                }
                if (p instanceof ReloginCommand) {
                    repairing = false;
                }
                w.apply(p, r.nanos());
            }
        }
        System.out.printf("gates=%d transitions=%d beacons=%d%n", gates.size(), transitions, beacons);
        assertTrue(problems.isEmpty(), String.join("\n", problems));
        assertTrue(gates.size() >= 20 && transitions >= 5 && beacons > 100);
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
