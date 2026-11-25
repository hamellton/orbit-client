package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class QuestTextsTest {

    private static Quests.Condition c(int type, String... matches) {
        return new Quests.Condition(1, type, List.of(matches), 10, 0, true, false, true, List.of());
    }

    @Test
    void namesTheConditionsTheBotToldByTheirNumber() {
        // the Mimesis battle pass (500464–500469): EMAA-20 fired, escort keys spent
        assertEquals("выстрелить лазерными боеприпасами", QuestTexts.condition(c(Quests.SPEND_AMMUNITION, "1", "0")));
        assertEquals("выстрелить ракетами", QuestTexts.condition(c(Quests.SPEND_AMMUNITION, "2", "3")));
        assertEquals("потратить resource_no-such-item", QuestTexts.condition(c(Quests.SPEND_ITEM, "resource_no-such-item")));
        assertEquals("пройти любые из врат", QuestTexts.condition(c(Quests.FINISH_GALAXY_GATES)));
        assertEquals("получить урон", QuestTexts.condition(c(9)));
    }

    @Test
    void aTypeTheClientDoesNotKnowKeepsItsNumber() {
        assertEquals("условие 150", QuestTexts.condition(c(150)));
    }
}
