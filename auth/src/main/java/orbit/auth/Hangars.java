package orbit.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The account's hangars on the website, as DarkBot reads and switches them (facts from its open
 * {@code HangarManager}/{@code LegacyHangarManager}):
 * <ul>
 *   <li>the list: {@code flashAPI/inventory.php?action=getHangarList}, answer base64 JSON {@code {isError,
 *       data:{ret:{hangars:[{hangarID, hangar_is_active, general:{ship:{L}}}]}, map:{lootIds:[…]}}}} — the ship's
 *       {@code L} indexes {@code lootIds} ({@code ship_goliath}…); {@code hangars} may be an object too;</li>
 *   <li>the switch: {@code POST flashAPI/inventory.php}, {@code action=activateShip}, {@code params} = base64 of
 *       {@code {"params":{"hi":<active hangar>,"hangarId":<wanted>}}}; done when the answer has {@code "isError":0};
 *       DarkBot waits 12 s before the next switch.</li>
 * </ul>
 */
public final class Hangars {

    /** One hangar: its id, whether it is the active one, and its ship ({@code ship_goliath}…, or "" unknown). */
    public record Hangar(int id, boolean active, String ship) {
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String PATH = "/flashAPI/inventory.php";

    private final HttpSession http;
    private final String origin;

    /** @param origin the account's site, e.g. {@code https://ru1.darkorbit.com} ({@link GameSession#host}) */
    public Hangars(HttpSession http, String origin) {
        this.http = http;
        this.origin = origin;
    }

    /** All hangars of the account. */
    public List<Hangar> list() throws IOException {
        HttpSession.Response r = http.get(origin + PATH + "?action=getHangarList", origin + Shop.START_PAGE);
        JsonNode root = decode(r.body());
        if (root.path("isError").asInt(1) != 0) {
            throw new IOException("сайт не отдал список ангаров");
        }
        return parse(root);
    }

    static List<Hangar> parse(JsonNode root) {
        JsonNode lootIds = root.path("data").path("map").path("lootIds");
        JsonNode hangars = root.path("data").path("ret").path("hangars");
        List<JsonNode> each = new ArrayList<>();
        if (hangars.isArray()) {
            hangars.forEach(each::add);
        } else {
            hangars.elements().forEachRemaining(each::add);
        }
        List<Hangar> out = new ArrayList<>();
        for (JsonNode h : each) {
            JsonNode l = h.path("general").path("ship").path("L");
            String ship = l.isInt() && lootIds.has(l.asInt()) ? lootIds.get(l.asInt()).asText() : "";
            out.add(new Hangar(h.path("hangarID").asInt(), h.path("hangar_is_active").asBoolean(), ship));
        }
        return out;
    }

    /** Makes {@code hangarId} the active ship; {@code active} is the one active now. */
    public void activate(int active, int hangarId) throws IOException {
        String params = "{\"params\":{\"hi\":" + active + ",\"hangarId\":" + hangarId + "}}";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "activateShip");
        form.put("params", Base64.getEncoder().encodeToString(params.getBytes(StandardCharsets.UTF_8)));
        HttpSession.Response r = http.postForm(origin + PATH, form, origin + Shop.START_PAGE);
        String answer = new String(decodeBytes(r.body()), StandardCharsets.UTF_8);
        if (!answer.contains("\"isError\":0")) {
            throw new IOException("сайт не сменил корабль: " + answer.substring(0, Math.min(200, answer.length())));
        }
    }

    private static JsonNode decode(String body) throws IOException {
        return JSON.readTree(decodeBytes(body));
    }

    /** The answers are base64 (DarkBot: {@code Base64Utils.decode}); plain JSON passes as it is. */
    static byte[] decodeBytes(String body) {
        String b = body.strip();
        if (b.startsWith("{")) {
            return b.getBytes(StandardCharsets.UTF_8);
        }
        try {
            return Base64.getMimeDecoder().decode(b);
        } catch (IllegalArgumentException e) {
            return b.getBytes(StandardCharsets.UTF_8);
        }
    }
}
