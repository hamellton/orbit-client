package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class QuestTitlesTest {

    @Test
    void theGamesOwnTitles() {
        // resource_quest.xml (ru): Silent Sabotage, which opens the 100-quest chain
        assertEquals("Безмолвный саботаж", QuestTitles.of(122004));
        assertEquals("122004 «Безмолвный саботаж»", QuestTitles.label(122004));
        assertEquals("Радиоактивный отбор", QuestTitles.of(300013));
        assertNull(QuestTitles.of(999_999_999));
        assertEquals("999999999", QuestTitles.label(999_999_999));
    }
}
