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

    /**
     * One hangar: its id, whether it is the active one, its ship ({@code ship_goliath}…, or "" unknown) and its
     * design's loot id when the answer names one ({@code null} otherwise; not seen in the answer yet).
     */
    public record Hangar(int id, boolean active, String ship, String design) {

        public Hangar(int id, boolean active, String ship) {
            this(id, active, ship, null);
        }
    }

    /** The site sent a page instead of the list: its login is stale (live 2026-11-12 on gb2, after a night). */
    public static final class Expired extends IOException {

        Expired() {
            super("сайт прислал страницу вместо списка (вход на сайт устарел)");
        }
    }

    private static volatile String lastShape = "";

    /** The field names (no values) of the last list's first hangar: to learn where the site puts the design. */
    public static String lastShape() {
        return lastShape;
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
        keep(r);
        String body = r.body() == null ? "" : r.body().strip();
        if (r.status() != 200) {
            throw new IOException("сайт ответил HTTP " + r.status());
        }
        if (body.startsWith("<")) {
            throw new Expired();
        }
        JsonNode root;
        try {
            root = decode(body);
        } catch (IOException | RuntimeException e) {
            throw new IOException("ответ сайта не читается (" + body.length() + " символов)");
        }
        if (root.path("isError").asInt(1) != 0) {
            throw new IOException("сайт вернул ошибку (isError " + root.path("isError").asText("?") + ")");
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
            JsonNode ship = h.path("general").path("ship");
            out.add(new Hangar(h.path("hangarID").asInt(), h.path("hangar_is_active").asBoolean(), loot(ship.path("L"), lootIds),
                    design(ship, lootIds)));
        }
        if (!each.isEmpty()) {
            JsonNode h = each.getFirst();
            lastShape = "hangar " + names(h) + ", general " + names(h.path("general")) + ", ship "
                    + names(h.path("general").path("ship"));
        }
        return out;
    }

    private static String loot(JsonNode index, JsonNode lootIds) {
        return index.isInt() && lootIds.has(index.asInt()) ? lootIds.get(index.asInt()).asText() : "";
    }

    /** A design loot id if the ship entry names one ({@code design_…}, by index or as text); else {@code null}. */
    private static String design(JsonNode ship, JsonNode lootIds) {
        for (String k : new String[] {"SM", "D", "design"}) {
            JsonNode v = ship.path(k);
            String id = v.isInt() ? loot(v, lootIds) : v.isTextual() ? v.asText() : "";
            if (id.startsWith("design_")) {
                return id;
            }
        }
        return null;
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
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

    /** The last answer, the dosid masked, to see what the site sent ({@code local/debug/hangars-answer.txt}). */
    static final java.nio.file.Path ANSWER_FILE = java.nio.file.Path.of("local", "debug", "hangars-answer.txt");

    private static void keep(HttpSession.Response r) {
        try {
            java.nio.file.Files.createDirectories(ANSWER_FILE.getParent());
            String body = r.body() == null ? "" : r.body();
            String decoded = body.strip().startsWith("<") ? "" : new String(decodeBytes(body), StandardCharsets.UTF_8);
            java.nio.file.Files.writeString(ANSWER_FILE, "HTTP " + r.status() + " " + orbit.secrets.SecretMask.mask(String.valueOf(r.url()))
                    + "\n--- body ---\n" + orbit.secrets.SecretMask.mask(body) + "\n--- decoded ---\n" + orbit.secrets.SecretMask.mask(decoded));
        } catch (IOException | RuntimeException ignored) {
            // only for diagnosis
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
