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

    /**
     * One item of a kind in the hangar view: its id, the hangar it is fitted in ({@code 0} = none, a spare), its charges
     * ({@code -1} unknown) and the configs of the asked hangar whose ship has it fitted (by the config's lists).
     */
    public record Fitted(String id, int hangar, int charges, java.util.Set<Integer> configs) {

        public boolean spare() {
            return hangar == 0;
        }
    }

    /**
     * The items of one kind ({@code equipment_extra_cpu_cl04k-xl}…) the site shows for {@code hangarId}:
     * {@code action=getHangar}, {@code params} = base64 of {@code {"params":{"hi":<hangar>}}} (the inventory page's
     * own call, read from its {@code inventory.swf}, 2026-11-14 on gb2). The answer: {@code data.ret.items[]} — {@code I}
     * the id, {@code L} an index into {@code data.map.lootIds}, {@code EQH} the hangar it is fitted in (null = spare),
     * {@code CH} "Charges: N"; {@code data.ret.hangars{…}.config{"1","2"}.ship.EQ.extras} — what each config fits.
     * Items fitted in other hangars are listed too.
     */
    public List<Fitted> items(int hangarId, String loot) throws IOException {
        JsonNode root = call("getHangar", "{\"params\":{\"hi\":" + hangarId + "}}");
        return items(root, hangarId, loot);
    }

    static List<Fitted> items(JsonNode root, int hangarId, String loot) {
        JsonNode lootIds = root.path("data").path("map").path("lootIds");
        Map<String, java.util.Set<Integer>> fitted = new java.util.HashMap<>();
        JsonNode hangars = root.path("data").path("ret").path("hangars");
        List<JsonNode> each = new ArrayList<>();
        if (hangars.isArray()) {
            hangars.forEach(each::add);
        } else {
            hangars.elements().forEachRemaining(each::add);
        }
        for (JsonNode h : each) {
            if (h.path("hangarID").asInt() != hangarId) {
                continue;
            }
            h.path("config").fields().forEachRemaining(c -> {
                int config = parseInt(c.getKey());
                c.getValue().path("ship").path("EQ").elements().forEachRemaining(slots -> slots.forEach(
                        id -> fitted.computeIfAbsent(id.asText(), k -> new java.util.TreeSet<>()).add(config)));
            });
        }
        List<Fitted> out = new ArrayList<>();
        for (JsonNode i : root.path("data").path("ret").path("items")) {
            if (!loot.equals(loot(i.path("L"), lootIds))) {
                continue;
            }
            String id = i.path("I").asText();
            out.add(new Fitted(id, i.path("EQH").isNull() ? 0 : parseInt(i.path("EQH").asText()), charges(i),
                    java.util.Set.copyOf(fitted.getOrDefault(id, java.util.Set.of()))));
        }
        return out;
    }

    private static int charges(JsonNode item) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d+)").matcher(item.path("CH").asText(""));
        if (m.find()) {
            return parseInt(m.group(1));
        }
        String d = item.path("properties").path("durability").asText("");
        return d.isEmpty() ? -1 : parseInt(d);
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /**
     * Moves an item between the inventory and a config of the ship's {@code slotset} ({@code extras} for CPUs), as the
     * inventory page's drag does ({@code inventory.swf}: {@code DragComponent} → {@code ConnectionProxy.itemMoveRequest}):
     * {@code action=move}, {@code params} = base64 of {@code {"action":"move","params":{"hi":<hangar>},
     * "from":{"configId":c,"target":"inventory","items":[id]},"to":{"configId":c,"target":"ship","slotset":"extras"}}}
     * (out: {@code from} the ship with its slotset, {@code to} the inventory at index 0). Tried 2026-11-14 on gb2 with
     * the cloak CPU, out and back: {@code {"isError":0,"data":{"ret":1,…}}}.
     */
    public void move(int hangarId, String itemId, int config, boolean toShip, String slotset) throws IOException {
        String ship = "{\"configId\":" + config + ",\"target\":\"ship\",\"slotset\":\"" + slotset + "\"";
        String inv = "{\"configId\":" + config + ",\"target\":\"inventory\"";
        String items = ",\"items\":[\"" + itemId + "\"]}";
        String from = toShip ? inv + items : ship + items;
        String to = toShip ? ship + "}" : inv + ",\"index\":0}";
        JsonNode root = call("move", "{\"action\":\"move\",\"params\":{\"hi\":" + hangarId + "},\"from\":" + from
                + ",\"to\":" + to + "}");
        if (root.path("data").path("ret").asInt(0) != 1) {
            throw new IOException("сайт не переложил предмет: " + root.path("data").toString()
                    .substring(0, Math.min(200, root.path("data").toString().length())));
        }
    }

    /** A {@code flashAPI/inventory.php} call: the action and its params as base64; the answer read, errors thrown. */
    private JsonNode call(String action, String params) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", action);
        form.put("params", Base64.getEncoder().encodeToString(params.getBytes(StandardCharsets.UTF_8)));
        HttpSession.Response r = http.postForm(origin + PATH, form, origin + Shop.START_PAGE);
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
            throw new IOException("сайт вернул ошибку: " + orbit.secrets.SecretMask.mask(root.toString()
                    .substring(0, Math.min(200, root.toString().length()))));
        }
        return root;
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
