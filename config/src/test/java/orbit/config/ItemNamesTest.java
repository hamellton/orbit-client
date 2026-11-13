package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ItemNamesTest {

    @Test
    void namesForPeople() {
        // the user, 2026-11-13: the hangar showed the game's ids
        assertEquals("Mine ACM-01", ItemCatalog.friendly("ammunition_mine_acm-01"));
        assertEquals("Mine EMPM-01", ItemCatalog.friendly("ammunition_mine_empm-01"));
        assertEquals("Mine AGL-M01", ItemCatalog.friendly("ammunition_mine_agl-m01"));
        assertEquals("DCR-250", ItemCatalog.friendly("ammunition_specialammo_dcr-250"));
        assertEquals("Alien CPU (common)", ItemCatalog.friendly("ammunition_specialammo_alien-cpu-common"));
        assertEquals("Bountiful alien CPU (easy)", ItemCatalog.friendly("ammunition_specialammo_bountiful-alien-cpu-easy"));
        assertEquals("Emote alien angry", ItemCatalog.friendly("ammunition_specialammo_emote_alien_angry"));
        assertEquals("Cubi portal", ItemCatalog.friendly("ammunition_specialammo_cubi-portal"));
        assertEquals("Rocket " + ItemCatalog.label("ammunition_rocket_r-310"), ItemCatalog.friendly("ammunition_rocket_r-310"));
        assertEquals(ItemCatalog.label("ammunition_laser_lcb-10"), ItemCatalog.friendly("ammunition_laser_lcb-10"));
    }
}
