package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BoxCheckLayoutTest {

    @Test
    void boxesLieOnOneRingInsideTheCircleWithTheClientsIndexes() {
        for (int seed = 0; seed < 50; seed++) {
            List<WorldSnapshot.BoxCheck.CheckBox> boxes = World.layOutCheckBoxes(20_000, 10_000, 400, 5, 3, new Random(seed));
            assertEquals(8, boxes.size());
            assertEquals(Set.of(0, 1, 2, 3, 4), boxes.stream().filter(b -> !b.red()).map(b -> b.index()).collect(Collectors.toSet()));
            assertEquals(Set.of(5, 6, 7), boxes.stream().filter(b -> b.red()).map(b -> b.index()).collect(Collectors.toSet()));
            double ring = Math.hypot(boxes.getFirst().x() - 20_000, boxes.getFirst().y() - 10_000);
            for (WorldSnapshot.BoxCheck.CheckBox b : boxes) {
                double d = Math.hypot(b.x() - 20_000, b.y() - 10_000);
                assertTrue(d >= 0.6 * 400 - 2 && d <= 0.94 * 400 && Math.abs(d - ring) < 3, "on one ring in the circle: " + d);
            }
        }
    }

    @Test
    void aSentPickTakesTheBoxOffAndCountsTowardsTheTask() {
        World world = new World();
        orbit.protocol.gen.CaptchaTriggerCommand c = new orbit.protocol.gen.CaptchaTriggerCommand();
        c.type = (short) orbit.protocol.gen.CaptchaTriggerCommand.ECAPTCHATYPE_ALL_RED;
        c.posX = 1_000;
        c.posY = 1_000;
        c.Radius = 500;
        c.blackBox = 4;
        c.redBox = 2;
        world.apply(c, 0);
        assertEquals(2, world.snapshot(0).boxCheck().left());
        orbit.protocol.gen.CaptchaCollectRequest r = new orbit.protocol.gen.CaptchaCollectRequest();
        r.index = 5;
        world.applyOutgoing(r, 0);
        WorldSnapshot.BoxCheck check = world.snapshot(0).boxCheck();
        assertEquals(1, check.left());
        assertEquals(List.of(4), check.targets().stream().map(b -> b.index()).toList());
    }
}
