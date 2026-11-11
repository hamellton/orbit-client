package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class MigrationsTest {

    @Test
    void schemaOneKeepsOffEnemyBases() throws Exception {
        ObjectNode old = (ObjectNode) Json.MAPPER.readTree("{\"schema\": 1, \"general\": {\"workingMap\": 22}}");
        BotConfig c = Migrations.read(old);
        assertEquals(BotConfig.SCHEMA, c.schema());
        assertEquals(BotConfig.General.DEFAULT_BASE_RADIUS, c.general().baseRadius(), "on, as AstraBot does");
        assertEquals(22, c.general().workingMap());

        ObjectNode off = (ObjectNode) Json.MAPPER.readTree("{\"schema\": 2, \"general\": {\"baseRadius\": 0}}");
        assertEquals(0, Migrations.read(off).general().baseRadius(), "switched off stays off");
    }
}
