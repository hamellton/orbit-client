package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

/** The next refinement towards promerium. */
class RefinerTest {

    @Test
    void promeriumWhenItCanBeMade() {
        assertArrayEquals(new long[] {6, 2}, Refiner.next(Map.of(4, 25.0, 5, 20.0, 3, 5.0)), "two by duranium");
        assertArrayEquals(new long[] {6, 1}, Refiner.next(Map.of(4, 30.0, 5, 30.0, 3, 1.0)), "one by xenomit");
    }

    @Test
    void theScarcerIntermediateFirstAndTheEnduriumShared() {
        // prometium 100, terbium 100, endurium 40: prometid and duranium could each take it all — half each
        assertArrayEquals(new long[] {4, 2}, Refiner.next(Map.of(0, 100.0, 1, 40.0, 2, 100.0)));
        assertArrayEquals(new long[] {5, 2}, Refiner.next(Map.of(0, 100.0, 1, 40.0, 2, 100.0, 4, 10.0)),
                "prometid ahead: duranium now");
        assertArrayEquals(new long[] {4, 3}, Refiner.next(Map.of(0, 60.0, 1, 30.0)), "no terbium: all to prometid");
    }

    @Test
    void nothingToDo() {
        assertNull(Refiner.next(Map.of(0, 19.0, 1, 100.0)));
        assertNull(Refiner.next(Map.of(4, 50.0, 5, 50.0)), "no xenomit, no raw left");
    }
}
