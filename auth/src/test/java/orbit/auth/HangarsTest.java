package orbit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class HangarsTest {

    @Test
    void theHangarListNamesTheShipsAndTheActiveOne() throws Exception {
        // the shape DarkBot reads (hangars as an object here; an array works the same)
        String json = "{\"isError\":0,\"data\":{\"ret\":{\"hangars\":{\"0\":{\"hangarID\":500102,\"hangar_is_active\":false,"
                + "\"general\":{\"ship\":{\"L\":1}}},\"1\":{\"hangarID\":500101,\"hangar_is_active\":true,"
                + "\"general\":{\"ship\":{\"L\":0}}}}},\"map\":{\"lootIds\":[\"ship_goliath\",\"ship_citadel\"]}}}";
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        var list = Hangars.parse(new ObjectMapper().readTree(Hangars.decodeBytes(base64)));
        assertEquals(2, list.size());
        assertEquals(new Hangars.Hangar(500102, false, "ship_citadel"), list.get(0));
        assertTrue(list.get(1).active());
        assertEquals("ship_goliath", list.get(1).ship());
    }
    @Test
    void theHangarViewTellsEachCloakCpuItsChargesAndWhereItIsFitted() throws Exception {
        // the getHangar shape seen 2026-11-14 on gb2 (ids invented): spares have EQH null, a CPU fitted in another
        // hangar is listed too, the configs' lists say which config fits what
        String json = "{\"isError\":0,\"data\":{\"ret\":{\"hangars\":{\"19\":{\"hangarID\":700,\"hangar_is_active\":true,"
                + "\"config\":{\"1\":{\"ship\":{\"EQ\":{\"lasers\":[\"5\"],\"extras\":[\"11\",\"12\"]}}},"
                + "\"2\":{\"ship\":{\"EQ\":{\"extras\":[\"12\"]}}}}}},\"items\":["
                + "{\"I\":\"11\",\"L\":1,\"EQH\":\"700\",\"EQC\":\"1\",\"CH\":\"Charges: 15\"},"
                + "{\"I\":\"12\",\"L\":0,\"EQH\":\"700\",\"EQC\":\"1\"},"
                + "{\"I\":\"13\",\"L\":1,\"EQH\":null,\"EQC\":null,\"CH\":\"Charges: 50\"},"
                + "{\"I\":\"14\",\"L\":1,\"EQH\":\"701\",\"EQC\":\"1\",\"properties\":{\"durability\":\"3\"}}]},"
                + "\"map\":{\"lootIds\":[\"equipment_extra_repbot_rep-4\",\"equipment_extra_cpu_cl04k-xl\"]}}}";
        var items = Hangars.items(new ObjectMapper().readTree(json), 700, "equipment_extra_cpu_cl04k-xl");
        assertEquals(java.util.List.of(
                new Hangars.Fitted("11", 700, 15, java.util.Set.of(1)),
                new Hangars.Fitted("13", 0, 50, java.util.Set.of()),
                new Hangars.Fitted("14", 701, 3, java.util.Set.of())), items);
        assertTrue(items.get(1).spare());
    }
}
