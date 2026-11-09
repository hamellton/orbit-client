package orbit.bot.nav;

import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.world.WorldSnapshot;

final class WorldSnapshotProbe {

    private WorldSnapshotProbe() {
    }

    static void assertNear(WorldSnapshot.Hero h, double x, double y, double radius) {
        double d = Math.hypot(h.x() - x, h.y() - y);
        assertTrue(d <= radius, "hero at " + h.x() + "," + h.y() + " is " + d + " from " + x + "," + y);
    }
}
