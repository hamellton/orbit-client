package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

class LootLogTest {

    @Test
    void oneEventIsOneLineAndSpendingIsTold() {
        Logger logger = (Logger) LoggerFactory.getLogger(LootLog.class);
        ListAppender<ILoggingEvent> out = new ListAppender<>();
        out.start();
        logger.addAppender(out);
        try {
            LootLog loot = new LootLog();
            long t = 1_000_000_000L;
            loot.legacy("0|LM|ST|CRE|1000|50000", t);
            loot.legacy("0|LM|ST|URI|5|300", t + 1_000_000);
            loot.legacy("0|LM|ST|LOT|resource_collectable_luminium|20", t + 2_000_000);
            loot.flush(t + 3_000_000, false);
            assertEquals(0, out.list.size(), "the event's lines are still coming");
            loot.flush(t + LootLog.BATCH_NANOS + 1, false);
            List<String> lines = out.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
            assertEquals(1, lines.size());
            assertEquals("Добыча: +1 000 кредитов  ·  +5 уридия  ·  +20 Luminium", lines.getFirst().replace(' ', ' '));
        } finally {
            logger.detachAppender(out);
        }
    }
}
