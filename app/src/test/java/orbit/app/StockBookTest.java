package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StockBookTest {

    private static final String ACC = "resource_collectable_indoctrine-accelerator";
    private static final String DIA = "resource_collectable_diametrion";

    @TempDir
    Path dir;

    @Test
    void keepsTheLastAmountTheGameToldOverARestart() {
        Path file = dir.resolve("stock-a.json");
        StockBook book = new StockBook(file);
        assertNull(book.stock(ACC));
        book.update(Map.of(ACC, 37.0, "ammunition_laser_ucb-100", 5.0), 1_000);
        book.update(Map.of(ACC, 39.0, DIA, 120.0), 2_000);
        assertEquals(new StockBook.Stock(39, 2_000), book.stock(ACC));

        StockBook again = new StockBook(file); // a new start of the program: the game has told nothing yet
        assertEquals(new StockBook.Stock(39, 2_000), again.stock(ACC));
        assertEquals(new StockBook.Stock(120, 2_000), again.stock(DIA));
        assertNull(again.stock("ammunition_laser_ucb-100"));
    }

    @Test
    void anUnchangedAmountKeepsWhenItWasTold() {
        StockBook book = new StockBook(dir.resolve("stock-b.json"));
        book.update(Map.of(DIA, 120.0), 1_000);
        book.update(Map.of(DIA, 120.0), 5_000);
        assertEquals(1_000, book.stock(DIA).atMillis());
    }
}
