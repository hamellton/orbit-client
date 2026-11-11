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

    @Test
    void schemaTwoLimitsPaidRevivesInARow() throws Exception {
        ObjectNode old = (ObjectNode) Json.MAPPER.readTree("{\"schema\": 2, \"safety\": {\"revive\": \"PORTAL\", \"maxDeaths\": 5}}");
        BotConfig c = Migrations.read(old);
        assertEquals(2, c.safety().paidRevives());
        assertEquals(3, c.safety().paidReviveMinutes());
        assertEquals(5, c.safety().maxDeaths(), "the rest stays");
        ObjectNode off = (ObjectNode) Json.MAPPER.readTree("{\"schema\": 3, \"safety\": {\"paidRevives\": 0, \"paidReviveMinutes\": 3}}");
        assertEquals(0, Migrations.read(off).safety().paidRevives(), "switched off stays off");
    }
}
