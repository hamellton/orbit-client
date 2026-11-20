package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class ItemNamesTest {

    @Test
    void namesAsTheClientShowsThem() {
        assertEquals("Фрагмент чертежа («Zeus»)", ItemNames.of("resource_blueprint_zeus-part"));
        assertEquals("Наноконденсатор", ItemNames.of("resource_nano-condenser"));
        assertEquals("Щит SG3N-B02", ItemNames.of("equipment_generator_shield_sg3n-b02"));
        assertEquals("Модуль: бустер урона", ItemNames.of("module_dmgm-1"));
        assertEquals("Скрапиум", ItemNames.of("resource_collectable_scrapium"));
        assertEquals("UCB-100", ItemNames.of("ammunition_laser_ucb-100"));
        assertEquals("Наноконденсатор", ItemNames.of("resource_collectable_nano-condenser"), "with collectable_ too");
        assertNull(ItemNames.of("no_such_item"));
    }
}
