package orbit.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import orbit.secrets.SecretMask;

/**
 * The website shop of the logged-in account (the in-game client buys nothing itself; the game's
 * AmmunitionBuyRequest is never sent by it). Facts from the open-source DarkBot SharedPlugin Autobuy:
 * <ul>
 *   <li>the ammunition page {@code indexInternal.es?action=internalDock&tpl=internalDockAmmo} carries
 *       {@code Shop.Parameters = {…, "itemData": {"<item id>": {category, code, price, currency, maxAmount…}}}};
 *       currency {@code virtual} = credits, {@code real} = uridium;</li>
 *   <li>a purchase is {@code POST ajax/shop.php} with {@code action=purchase, category, itemId, amount,
 *       level="", selectedName=""} and the page as referer.</li>
 * </ul>
 */
public final class Shop {

    /** One shop offer. {@code price} is per unit (an amount of 1). */
    public record Item(String id, String category, String code, double price, String currency, int maxAmount) {

        public boolean uridium() {
            return "real".equals(currency) || "uridium".equals(currency);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    static final String AMMO_PAGE = "/indexInternal.es?action=internalDock&tpl=internalDockAmmo";
    static final String START_PAGE = "/indexInternal.es?action=internalStart";

    private final HttpSession http;
    private final String origin;

    /** @param origin the account's site, e.g. {@code https://int1.darkorbit.com} ({@link GameSession#host}) */
    public Shop(HttpSession http, String origin) {
        this.http = http;
        this.origin = origin;
    }

    /** Ammunition offers by item id. */
    public Map<String, Item> ammunition() throws IOException {
        HttpSession.Response r = http.get(origin + AMMO_PAGE, origin + START_PAGE);
        if (r.status() != 200) {
            throw new IOException("магазин ответил " + r.status());
        }
        return parse(r.body());
    }

    /** Buys {@code amount} units; returns the shop's answer (short, secrets masked). */
    public String purchase(Item item, int amount) throws IOException {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("action", "purchase");
        form.put("category", item.category());
        form.put("itemId", item.id());
        form.put("amount", String.valueOf(amount));
        form.put("level", "");
        form.put("selectedName", "");
        HttpSession.Response r = http.postForm(origin + "/ajax/shop.php", form, origin + AMMO_PAGE);
        String body = r.body() == null ? "" : r.body().strip();
        if (r.status() != 200) {
            throw new IOException("покупка: ответ " + r.status());
        }
        String shortBody = body.length() > 300 ? body.substring(0, 300) + "…" : body;
        return SecretMask.mask(shortBody);
    }

    /** True if the shop's answer reports an error ({@code "isError":1}, or an error text). */
    public static boolean isError(String answer) {
        try {
            JsonNode n = JSON.readTree(answer);
            return n.path("isError").asInt(0) != 0 || n.path("error").asBoolean(false);
        } catch (IOException e) {
            return answer.toLowerCase().contains("error");
        }
    }

    /** The offers in a shop page's {@code Shop.Parameters} JSON. */
    static Map<String, Item> parse(String html) throws IOException {
        int at = html.indexOf("Shop.Parameters");
        int start = at < 0 ? -1 : html.indexOf('{', at);
        if (start < 0) {
            throw new IOException("на странице магазина нет Shop.Parameters");
        }
        int depth = 0;
        int end = -1;
        boolean inString = false;
        for (int i = start; i < html.length(); i++) {
            char ch = html.charAt(i);
            if (inString) {
                if (ch == '\\') {
                    i++;
                } else if (ch == '"') {
                    inString = false;
                }
            } else if (ch == '"') {
                inString = true;
            } else if (ch == '{') {
                depth++;
            } else if (ch == '}' && --depth == 0) {
                end = i;
                break;
            }
        }
        if (end < 0) {
            throw new IOException("Shop.Parameters не разобран");
        }
        JsonNode items = JSON.readTree(html.substring(start, end + 1)).path("itemData");
        Map<String, Item> out = new LinkedHashMap<>();
        items.properties().forEach(e -> {
            JsonNode o = e.getValue();
            if (o.path("isPaymentItem").asBoolean(false)) {
                return; // bought with real money
            }
            out.put(e.getKey(), new Item(e.getKey(), o.path("category").asText(""), o.path("code").asText(e.getKey()),
                    o.path("price").asDouble(-1), o.path("currency").asText(""), o.path("maxAmount").asInt(0)));
        });
        return out;
    }
}
