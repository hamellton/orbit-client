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
        String json = "{\"isError\":0,\"data\":{\"ret\":{\"hangars\":{\"0\":{\"hangarID\":801116,\"hangar_is_active\":false,"
                + "\"general\":{\"ship\":{\"L\":1}}},\"1\":{\"hangarID\":801119,\"hangar_is_active\":true,"
                + "\"general\":{\"ship\":{\"L\":0}}}}},\"map\":{\"lootIds\":[\"ship_goliath\",\"ship_citadel\"]}}}";
        String base64 = Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        var list = Hangars.parse(new ObjectMapper().readTree(Hangars.decodeBytes(base64)));
        assertEquals(2, list.size());
        assertEquals(new Hangars.Hangar(801116, false, "ship_citadel"), list.get(0));
        assertTrue(list.get(1).active());
        assertEquals("ship_goliath", list.get(1).ship());
    }
}
