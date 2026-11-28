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

    @Test
    void readsAJavaScriptObjectLiteral() throws IOException {
        String page = "<script>Shop.Parameters = {categories: ['battery', 'rocket'], itemData: {"
                + "'ammunition_laser_lcb-10': {category: 'battery', code: 'lcb-10', price: 10, currency: 'virtual', maxAmount: 100000, name: 'LCB {10}'},"
                + "}};</script>";
        var items = Shop.parse(page);
        assertEquals(1, items.size());
        assertEquals(10, items.get("ammunition_laser_lcb-10").price());
    }

    /** A drones tab as the site sends it (invented values): drones keyed by number, the loot id in imageVars. */
    static final String DRONES_TAB = "<script>Shop.Parameters = { category : 'drones', itemData : {"
            + "\"1\":{\"category\":\"drone\",\"code\":\"FLAX\",\"price\":100000,\"currency\":\"virtual\",\"maxAmount\":1,"
            + "\"imageVars\":{\"loot_id\":\"drone_flax\"}},"
            + "\"2\":{\"category\":\"drone\",\"code\":\"IRIS\",\"price\":15000,\"currency\":\"real\",\"maxAmount\":1,"
            + "\"imageVars\":{\"loot_id\":\"drone_iris\"}},"
            + "\"package_x\":{\"category\":\"special\",\"price\":5,\"currency\":\"real\",\"imageVars\":{\"loot_id\":\"drone_iris\"}},"
            + "\"drone_formation_f-01-tu\":{\"category\":\"drone\",\"price\":1000000,\"currency\":\"virtual\",\"maxAmount\":1}"
            + "} };</script>";

    @Test
    void findsOffersByTheirLootId() throws Exception {
        java.util.Map<String, Shop.Item> by = Shop.parseByLoot(DRONES_TAB);
        assertEquals("1", by.get("drone_flax").id(), "the shop's own key is what a purchase sends");
        assertEquals("drone", by.get("drone_flax").category());
        assertEquals(15000, by.get("drone_iris").price(), 0.1, "the package holding an Iris is left out");
        assertTrue(by.get("drone_iris").uridium());
        assertEquals("drone_formation_f-01-tu", by.get("drone_formation_f-01-tu").id(), "no loot id: the key");
        assertEquals("Drones", Shop.tabOf("drone_iris"));
        assertEquals("Laser", Shop.tabOf("equipment_weapon_laser_sll-02"));
        assertEquals("Ships", Shop.tabOf("ship_nostromo"));
        assertEquals(null, Shop.tabOf("ammunition_laser_lcb-10"));
    }

    /** A P.E.T. gear with levels as the pet gear tab sends it (invented values): the level the P.E.T. allows, prices. */
    static final String GEAR_TAB = "<script>Shop.Parameters = { category : 'petgear', itemData : {"
            + "\"equipment_petgear_g-ar1\":{\"category\":\"petgear\",\"code\":\"G-AR1\",\"price\":2500,\"currency\":\"real\","
            + "\"maxAmount\":1,\"isLevelSetable\":true,\"levelGrades\":[1,2,3],\"maxLevel\":2,\"levelItemShopData\":{"
            + "\"1\":{\"price\":2500},\"2\":{\"price\":6000},\"3\":{\"price\":17500}}}"
            + "} };</script>";

    @Test
    void aGearsLevelsAndTheLevelThePetAllows() throws Exception {
        Shop.Item gear = Shop.parseByLoot(GEAR_TAB).get("equipment_petgear_g-ar1");
        assertEquals(2, gear.maxLevel(), "a P.E.T. too young for the 3rd");
        assertEquals(17500, gear.levelPrices().get(3), 0.1);
        assertEquals("PetGear", Shop.tabOf("equipment_petgear_g-ar1"));
        assertEquals("PetGear", Shop.tabOf("pet_pet10"), "the P.E.T. is sold among its gears");
        Shop.Item pet = new Shop.Item("pet_pet10", "pet", "P.E.T.-10", 50000, "real", 1, 0, java.util.Map.of());
        assertEquals("Pet", Shop.purchaseForm(pet, 1).get("selectedName"), "the shop wants a name for a P.E.T.");
        assertEquals("", Shop.purchaseForm(gear, 1).get("selectedName"), "no name for other items");
        assertEquals("3", Shop.purchaseForm(gear, 1, 3).get("level"), "the level the page's selector sends");
        assertEquals("", Shop.purchaseForm(gear, 1).get("level"), "no level: as before");
        assertEquals(0, Shop.parseByLoot(DRONES_TAB).get("drone_flax").maxLevel(), "an offer without levels");
    }

    @Test
    void thePurchaseFormAndItsAnswer() {
        Shop.Item flax = new Shop.Item("1", "drone", "FLAX", 100000, "virtual", 1);
        assertEquals(java.util.List.of("action", "category", "itemId", "amount", "level", "selectedName"),
                java.util.List.copyOf(Shop.purchaseForm(flax, 1).keySet()));
        assertEquals("1", Shop.purchaseForm(flax, 1).get("itemId"));
        assertTrue(Shop.refused("{\"result\":\"error\",\"message\":\"мало кредитов\"}"));
        assertFalse(Shop.refused("{\"result\":\"success\",\"message\":\"куплено\"}"));
        assertTrue(Shop.refused("<html>"));
        assertFalse(Shop.refused("{\"result\":\"success\",\"message\":\"Куплены лазеры…"), "a shortened answer that went through");
    }
}
