package orbit.bot.level;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Reads the account from the website's inventory answers ({@code flashAPI/inventory.php}, decoded): the hangar list
 * ({@code getHangarList}: hangars, money, every ship's {@code owned}, the ships' slotsets in {@code itemInfo}) and one
 * hangar's view ({@code getHangar}: items and what each config fits). Facts: docs/recon/SHOP-AUCTION.md, ASSEMBLY.md.
 */
public final class SiteHangars {

    private SiteHangars() {
    }

    /** What the hangar list tells. */
    public record ListInfo(int activeHangar, String activeShip, Set<String> ownedShips, Map<String, Integer> slots,
                           long credits, long uridium) {
    }

    /** What one hangar's view tells: per config slotset → loot ids, the spares, the config entries' field names. */
    public record HangarInfo(Map<Integer, Map<String, List<String>>> fitted, Map<String, Integer> spare, String shape) {
    }

    public static ListInfo list(JsonNode root) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        int active = 0;
        String ship = "";
        int shipIndex = -1;
        for (JsonNode h : each(data.path("ret").path("hangars"))) {
            if (h.path("hangar_is_active").asBoolean()) {
                active = h.path("hangarID").asInt();
                JsonNode l = h.path("general").path("ship").path("L");
                shipIndex = l.isInt() ? l.asInt() : -1;
                ship = shipIndex >= 0 && lootIds.has(shipIndex) ? lootIds.get(shipIndex).asText() : "";
            }
        }
        Set<String> owned = new HashSet<>();
        for (JsonNode s : data.path("ret").path("shipInfo")) {
            if (s.path("owned").asInt() == 1) {
                owned.add(s.path("lootId").asText());
            }
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (JsonNode i : data.path("ret").path("itemInfo")) {
            if (i.path("L").asInt(-2) == shipIndex && "ship".equals(i.path("C").asText())) {
                JsonNode levels = i.path("levels");
                JsonNode sets = levels.path(0).path("slotsets");
                sets.properties().forEach(e -> slots.put(e.getKey(), e.getValue().path("Q").asInt()));
            }
        }
        JsonNode money = data.path("money");
        return new ListInfo(active, ship, owned, slots, amount(money.path("credits")), amount(money.path("uridium")));
    }

    public static HangarInfo hangar(JsonNode root, int hangarId) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        Map<String, String> lootOf = new HashMap<>();
        Map<String, Integer> spare = new TreeMap<>();
        for (JsonNode i : data.path("ret").path("items")) {
            JsonNode l = i.path("L");
            String loot = l.isInt() && lootIds.has(l.asInt()) ? lootIds.get(l.asInt()).asText() : "";
            lootOf.put(i.path("I").asText(), loot);
            if (i.path("EQH").isNull() || i.path("EQH").isMissingNode()) {
                spare.merge(loot, 1, Integer::sum);
            }
        }
        Map<Integer, Map<String, List<String>>> fitted = new TreeMap<>();
        List<String> shape = new ArrayList<>();
        for (JsonNode h : each(data.path("ret").path("hangars"))) {
            if (h.path("hangarID").asInt() != hangarId) {
                continue;
            }
            h.path("config").properties().forEach(c -> {
                int config;
                try {
                    config = Integer.parseInt(c.getKey().strip());
                } catch (NumberFormatException e) {
                    return;
                }
                if (shape.isEmpty()) {
                    shape.add("config " + names(c.getValue()));
                    c.getValue().properties().forEach(part -> shape.add(part.getKey() + " " + names(part.getValue())
                            + (part.getValue().has("EQ") ? " EQ " + names(part.getValue().path("EQ")) : "")));
                }
                Map<String, List<String>> sets = new TreeMap<>();
                c.getValue().path("ship").path("EQ").properties().forEach(set -> {
                    List<String> ids = new ArrayList<>();
                    set.getValue().forEach(id -> ids.add(lootOf.getOrDefault(id.asText(), "?" + id.asText())));
                    sets.put(set.getKey(), List.copyOf(ids));
                });
                fitted.put(config, sets);
            });
        }
        return new HangarInfo(fitted, spare, String.join("; ", shape));
    }

    /** The full state: the list and the active hangar's view, with the game's level and money where it knows them. */
    public static AccountState state(ListInfo list, HangarInfo hangar, int level, long gameCredits, long gameUridium) {
        return new AccountState(level, gameCredits >= 0 ? gameCredits : list.credits(),
                gameUridium >= 0 ? gameUridium : list.uridium(), list.activeHangar(), list.activeShip(), list.ownedShips(),
                list.slots(), hangar == null ? Map.of() : hangar.fitted(), hangar == null ? Map.of() : hangar.spare());
    }

    /** The site writes money as text with dots between thousands ({@code "869.218.568.732"}); {@code -1} unreadable. */
    static long amount(JsonNode n) {
        if (n.isNumber()) {
            return n.asLong();
        }
        String digits = n.asText("").replaceAll("[^0-9]", "");
        if (digits.isEmpty()) {
            return -1;
        }
        try {
            return Long.parseLong(digits);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static List<JsonNode> each(JsonNode n) {
        List<JsonNode> out = new ArrayList<>();
        if (n.isArray()) {
            n.forEach(out::add);
        } else {
            n.elements().forEachRemaining(out::add);
        }
        return out;
    }

    private static List<String> names(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.fieldNames().forEachRemaining(out::add);
        return out;
    }
}
