package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class BoxCheckTextTest {

    @Test
    void everyKindOfTaskReadsPlainly() {
        assertEquals("ПРОВЕРКА: соберите 3 чёрных ящика из 5, осталось 40 с",
                new WorldSnapshot.BoxCheck(0, 3, 40, 0, 0, 400, 5, 3, 0).text(0));
        assertEquals("ПРОВЕРКА: соберите 1 чёрный ящик из 5, осталось 30 с",
                new WorldSnapshot.BoxCheck(0, 1, 40, 0, 0, 400, 5, 3, 0).text(10_000_000_000L));
        assertEquals("ПРОВЕРКА: соберите все чёрные ящики (5), осталось 40 с",
                new WorldSnapshot.BoxCheck(1, 0, 40, 0, 0, 400, 5, 3, 0).text(0));
        assertEquals("ПРОВЕРКА: соберите 2 красных ящика из 3, осталось 0 с",
                new WorldSnapshot.BoxCheck(2, 2, 40, 0, 0, 400, 5, 3, 0).text(99_000_000_000L));
        assertEquals("ПРОВЕРКА: соберите все красные ящики (3)",
                new WorldSnapshot.BoxCheck(3, 0, 0, 0, 0, 400, 5, 3, 0).text(0));
    }
}
