package orbit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

/** A made-up shop page in the site's shape (no real account data). */
class ShopTest {

    static final String PAGE = """
            <html><script>
            var x = 1;
            Shop.Parameters = {"lang":"ru","itemData":{
              "ammunition_laser_lcb-10":{"category":"battery","code":"lcb-10","price":10,"currency":"virtual","maxAmount":100000,"name":"LCB-10 {x}"},
              "ammunition_rocket_plt-2026":{"category":"rocket","code":"plt-2026","price":0.5,"currency":"real","maxAmount":10000},
              "ammunition_laser_ucb-100":{"category":"battery","code":"ucb-100","price":0.5,"currency":"real","maxAmount":100000,"isPaymentItem":false},
              "pack_premium":{"category":"special","code":"x","price":5,"currency":"real","maxAmount":1,"isPaymentItem":true}
            }};
            Shop.init();
            </script></html>""";

    @Test
    void readsTheOffers() throws IOException {
        var items = Shop.parse(PAGE);
        assertEquals(3, items.size(), "real-money items are left out");
        Shop.Item lcb = items.get("ammunition_laser_lcb-10");
        assertEquals("battery", lcb.category());
        assertEquals(10, lcb.price());
        assertFalse(lcb.uridium());
        assertEquals(100_000, lcb.maxAmount());
        assertTrue(items.get("ammunition_rocket_plt-2026").uridium());
    }

    @Test
    void refusesAPageWithoutTheShop() {
        assertThrows(IOException.class, () -> Shop.parse("<html>login</html>"));
    }

    @Test
    void errorAnswers() {
        assertTrue(Shop.isError("{\"isError\":1,\"data\":{\"errorMessage\":\"x\"}}"));
        assertFalse(Shop.isError("{\"isError\":0,\"data\":{\"money\":\"1\"}}"));
    }
}
