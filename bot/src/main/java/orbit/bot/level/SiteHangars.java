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
                           long credits, long uridium, Map<Integer, String> hangars) {

        /** The hangar a ship stands in ({@code 0} none). */
        public int hangarOf(String ship) {
            return hangars.entrySet().stream().filter(e -> e.getValue().equals(ship)).map(Map.Entry::getKey).findFirst()
                    .orElse(0);
        }
    }

    /**
     * What one hangar's view tells: per config slotset → loot ids, the spares (by amount: ammunition counts its {@code Q}),
     * the drones ({@code general.drones}, a list; empty on a new account, 2026-11-25 int1) and the field names seen.
     */
    public record HangarInfo(Map<Integer, Map<String, List<String>>> fitted, Map<String, Integer> spare, int drones,
                             String shape, List<Owned> items, Map<Integer, Map<String, List<String>>> fittedIds) {
    }

    /** One item of the view: its id, loot id and the hangar it is fitted in ({@code 0} = a spare in the inventory). */
    public record Owned(String id, String loot, int hangar) {
    }

    /** One drone of the hangar: its id (the key of {@code config.<c>.drones}), loot id and item places. */
    public record Drone(String id, String loot, int slots, int level) {

        public Drone(String id, String loot, int slots) {
            this(id, loot, slots, 0);
        }
    }

    /**
     * The hangar's drones and what each holds per config: {@code config → drone id → item ids}
     * ({@code data.ret.hangars{…}.config{"1","2"}.drones{<id>}.EQ.default}, as the inventory page reads them).
     */
    public record DroneView(List<Drone> drones, Map<Integer, Map<String, List<String>>> fitted) {
    }

    /** Item places of a drone: Flax one, Iris and the better ones two (the game's drone facts). */
    static int droneSlots(String loot) {
        return loot == null || loot.isEmpty() || loot.equals("drone_flax") ? 1 : 2;
    }

    public static DroneView droneView(JsonNode root, int hangarId) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        List<Drone> drones = new ArrayList<>();
        Map<Integer, Map<String, List<String>>> fitted = new TreeMap<>();
        for (JsonNode h : each(data.path("ret").path("hangars"))) {
            if (h.path("hangarID").asInt() != hangarId) {
                continue;
            }
            for (JsonNode d : each(h.path("general").path("drones"))) {
                JsonNode l = d.path("L");
                String loot = l.isInt() && lootIds.has(l.asInt()) ? lootIds.get(l.asInt()).asText() : "";
                drones.add(new Drone(d.path("I").asText(), loot, droneSlots(loot), d.path("LV").asInt(0)));
            }
            h.path("config").properties().forEach(c -> {
                int config;
                try {
                    config = Integer.parseInt(c.getKey().strip());
                } catch (NumberFormatException e) {
                    return;
                }
                Map<String, List<String>> per = new TreeMap<>();
                c.getValue().path("drones").properties().forEach(d -> {
                    List<String> ids = new ArrayList<>();
                    d.getValue().path("EQ").path("default").forEach(id -> ids.add(id.asText()));
                    per.put(d.getKey(), List.copyOf(ids));
                });
                fitted.put(config, per);
            });
        }
        // the configs' keys are the drones' ids; a list whose «I» is not among them (not seen yet) takes them in order
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        fitted.values().forEach(m -> keys.addAll(m.keySet()));
        if (!keys.isEmpty() && drones.stream().noneMatch(d -> keys.contains(d.id()))) {
            List<Drone> byKey = new ArrayList<>();
            int i = 0;
            for (String k : keys) {
                String loot = i < drones.size() ? drones.get(i).loot() : "";
                byKey.add(new Drone(k, loot, droneSlots(loot), i < drones.size() ? drones.get(i).level() : 0));
                i++;
            }
            drones = byKey;
        }
        return new DroneView(List.copyOf(drones), fitted);
    }

    /**
     * The active hangar's drones as the site tells them, every field but the id ({@code L} as its loot id) — for the log:
     * a formation stays unset on a ship whose drones do not work (2026-11-26: X bought, not set on st2–st5).
     */
    public static String drones(JsonNode root, int hangarId) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        List<String> out = new ArrayList<>();
        for (JsonNode h : each(data.path("ret").path("hangars"))) {
            if (h.path("hangarID").asInt() != hangarId) {
                continue;
            }
            for (JsonNode d : each(h.path("general").path("drones"))) {
                List<String> f = new ArrayList<>();
                d.properties().forEach(e -> {
                    if (e.getKey().equals("I")) {
                        return;
                    }
                    JsonNode v = e.getValue();
                    f.add(e.getKey() + "=" + (e.getKey().equals("L") && v.isInt() && lootIds.has(v.asInt())
                            ? lootIds.get(v.asInt()).asText() : v.toString()));
                });
                out.add(String.join(" ", f));
            }
        }
        return out.isEmpty() ? "нет" : out.size() + ": " + String.join("; ", out);
    }

    /** The slotsets of any ship of the list ({@code itemInfo} of {@code getHangarList}), by its loot id; empty if unknown. */
    public static Map<String, Integer> slotsOf(JsonNode root, String shipLoot) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        int index = -1;
        for (int i = 0; i < lootIds.size(); i++) {
            if (shipLoot.equals(lootIds.get(i).asText())) {
                index = i;
                break;
            }
        }
        Map<String, Integer> slots = new LinkedHashMap<>();
        for (JsonNode i : data.path("ret").path("itemInfo")) {
            if (index >= 0 && i.path("L").asInt(-2) == index && "ship".equals(i.path("C").asText())) {
                i.path("levels").path(0).path("slotsets").properties()
                        .forEach(e -> slots.put(e.getKey(), e.getValue().path("Q").asInt()));
            }
        }
        return slots;
    }

    public static ListInfo list(JsonNode root) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        int active = 0;
        String ship = "";
        int shipIndex = -1;
        Map<Integer, String> hangars = new LinkedHashMap<>();
        for (JsonNode h : each(data.path("ret").path("hangars"))) {
            JsonNode hl = h.path("general").path("ship").path("L");
            hangars.put(h.path("hangarID").asInt(), hl.isInt() && lootIds.has(hl.asInt()) ? lootIds.get(hl.asInt()).asText() : "");
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
        return new ListInfo(active, ship, owned, slots, amount(money.path("credits")), amount(money.path("uridium")),
                Map.copyOf(hangars));
    }

    public static HangarInfo hangar(JsonNode root, int hangarId) {
        JsonNode data = root.path("data");
        JsonNode lootIds = data.path("map").path("lootIds");
        Map<String, String> lootOf = new HashMap<>();
        Map<String, Integer> spare = new TreeMap<>();
        List<Owned> items = new ArrayList<>();
        for (JsonNode i : data.path("ret").path("items")) {
            JsonNode l = i.path("L");
            String loot = l.isInt() && lootIds.has(l.asInt()) ? lootIds.get(l.asInt()).asText() : "";
            lootOf.put(i.path("I").asText(), loot);
            JsonNode eqh = i.path("EQH");
            int in = eqh.isNull() || eqh.isMissingNode() ? 0 : (int) amount(eqh);
            items.add(new Owned(i.path("I").asText(), loot, in));
            if (i.path("EQH").isNull() || i.path("EQH").isMissingNode()) {
                spare.merge(loot, Math.max(1, i.path("Q").asInt(1)), Integer::sum);
            }
        }
        Map<Integer, Map<String, List<String>>> fitted = new TreeMap<>();
        Map<Integer, Map<String, List<String>>> fittedIds = new TreeMap<>();
        List<String> shape = new ArrayList<>();
        int drones = 0;
        for (JsonNode h : each(data.path("ret").path("hangars"))) {
            if (h.path("hangarID").asInt() != hangarId) {
                continue;
            }
            JsonNode d = h.path("general").path("drones");
            drones = d.size();
            // the drones by kind among the spares (drone_flax, drone_iris…): the plan counts Iris apart from Flax
            // (Ц6 «8 Iris», the Flax sold under them — the user's choice 2026-11-26)
            for (JsonNode one : each(d)) {
                JsonNode dl = one.path("L");
                String loot = dl.isInt() && data.path("map").path("lootIds").has(dl.asInt())
                        ? data.path("map").path("lootIds").get(dl.asInt()).asText() : "";
                if (!loot.isEmpty()) {
                    spare.merge(loot, 1, Integer::sum);
                }
            }
            shape.add("general " + names(h.path("general")) + (d.size() > 0 ? ", drone " + names(d.path(0)) : ""));
            h.path("config").properties().forEach(c -> {
                int config;
                try {
                    config = Integer.parseInt(c.getKey().strip());
                } catch (NumberFormatException e) {
                    return;
                }
                if (shape.size() == 1) {
                    shape.add("config " + names(c.getValue()));
                    c.getValue().properties().forEach(part -> shape.add(part.getKey() + " " + names(part.getValue())
                            + (part.getValue().has("EQ") ? " EQ " + names(part.getValue().path("EQ")) : "")));
                }
                Map<String, List<String>> sets = new TreeMap<>();
                Map<String, List<String>> idSets = new TreeMap<>();
                c.getValue().path("ship").path("EQ").properties().forEach(set -> {
                    List<String> loots = new ArrayList<>();
                    List<String> ids = new ArrayList<>();
                    set.getValue().forEach(id -> {
                        loots.add(lootOf.getOrDefault(id.asText(), "?" + id.asText()));
                        ids.add(id.asText());
                    });
                    sets.put(set.getKey(), List.copyOf(loots));
                    idSets.put(set.getKey(), List.copyOf(ids));
                });
                fitted.put(config, sets);
                fittedIds.put(config, idSets);
            });
        }
        return new HangarInfo(fitted, spare, drones, String.join("; ", shape), List.copyOf(items), fittedIds);
    }

    /** The full state: the list and the active hangar's view, with the game's level and money where it knows them. */
    public static AccountState state(ListInfo list, HangarInfo hangar, int level, long gameCredits, long gameUridium) {
        return new AccountState(level, gameCredits >= 0 ? gameCredits : list.credits(),
                gameUridium >= 0 ? gameUridium : list.uridium(), list.activeHangar(), list.activeShip(), list.ownedShips(),
                withExtender(list.slots(), hangar), hangar == null ? Map.of() : hangar.fitted(),
                hangar == null ? Map.of() : hangar.spare(), hangar == null ? -1 : hangar.drones());
    }

    static final String EXTENDER = "equipment_extra_cpu_sle-0";
    /** Places each slot extender frees for extras (the game's item texts: SLE-02 4, SLE-03 6, SLE-04 10). */
    static final Map<String, Integer> EXTENDER_SLOTS = Map.of(EXTENDER + "1", 2, EXTENDER + "2", 4, EXTENDER + "3", 6,
            EXTENDER + "4", 10);

    /**
     * The extras slots with the slot extender's: the best SLE owned (fitted or spare) adds its places
     * ({@link #EXTENDER_SLOTS}) to the ship's {@code extras} — the site
     * lists the ship's own (live 2026-11-26 on int1: a G-Bastion «extras Q 3» with SLE-02 took a fourth and a fifth CPU,
     * AROL-X and RLLB-X, out of the game).
     */
    public static Map<String, Integer> withExtender(Map<String, Integer> slots, HangarInfo hangar) {
        if (hangar == null || !slots.containsKey("extras")) {
            return slots;
        }
        int best = 0;
        java.util.List<String> all = new java.util.ArrayList<>(hangar.spare().keySet());
        hangar.fitted().values().forEach(sets -> sets.values().forEach(all::addAll));
        for (String loot : all) {
            best = Math.max(best, EXTENDER_SLOTS.getOrDefault(loot, 0));
        }
        if (best <= 0) {
            return slots;
        }
        Map<String, Integer> out = new LinkedHashMap<>(slots);
        out.merge("extras", best, Integer::sum);
        return out;
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
