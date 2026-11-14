package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EnemyHeatTest {

    @Test
    void keepsSavingAfterAForcedSave(@TempDir Path dir) {
        Path f = dir.resolve("heat.json");
        EnemyHeat h = EnemyHeat.open(f);
        h.add(307, 1_000, 1_000, 40);
        h.save(10_000_000_000L); // a death
        h.add(306, 2_000, 2_000, 5);
        h.saveSometimes(80_000_000_000L); // a minute later: written again
        EnemyHeat back = EnemyHeat.open(f);
        assertEquals(40, back.total(307), 0.01);
        assertEquals(5, back.total(306), 0.01);
    }

    @Test
    void nearerHeatWeighsMore() {
        EnemyHeat h = EnemyHeat.inMemory();
        h.add(307, 5_250, 5_250, 10);
        double near = h.around(307, 5_250, 5_250, 3_000);
        double far = h.around(307, 7_250, 5_250, 3_000);
        assertEquals(10, near, 0.01);
        assertEquals(10 * (1 - 2_000 / 3_000.0), far, 0.01);
    }
}
