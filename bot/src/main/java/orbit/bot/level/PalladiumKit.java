package orbit.bot.level;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The palladium stage's own ship and its kit (docs/LEVELING.md «План v2», ПД1–ПД3, the user's choice 2026-11-26): the
 * Nostromo has its own items, so nothing is taken off the main ship for it each time — in both configs one of each extra
 * of the kit, the engines in its generators (after the P.E.T.), shields in the drones' places of its hangar (the drones are
 * the account's, what lies in them is each hangar's own). From {@code plan.json} → {@code palladium}. Pure: it reads the
 * site's views and says what to bid on and which moves to make; it moves nothing.
 */
public final class PalladiumKit {

    /**
     * @param ship      the stage's ship ({@code ship_nostromo})
     * @param extras    the extras it gets, best first, one of each kind (the slot extender first: it gives the places)
     * @param engines   what its generators hold (the engines; bought after the P.E.T.)
     * @param shields   what its drones hold in both configs, best first
     * @param kitAuction the extras won on the auction a second time for it (two of each kind: the main ship's and its own)
     * @param enginePrice the engine's shop price in uridium
     * @param enginesAfter the goal the engines wait for (the P.E.T., Ц3а)
     * @param collector  the resource collector gear on its P.E.T. ({@code equipment_petgear_g-ar1})
     * @param collectorLevel the collector's level it takes (the 3rd)
     * @param park       the ship parked at 5-2's trader to sell from (any Vengeance)
     * @param uridiumReserve the uridium kept for the P.E.T.'s fuel before the stage starts (ПД4)
     * @param energyTarget the extra energy (15 palladium = 1) the stage collects before it ends (ПД7)
     * @param config     the stage's config (read-only, folder «Leveling»)
     * @param sellMap    the map of the trader (5-2)
     */
    public record Kit(String ship, List<String> extras, List<String> kitAuction, List<String> engines, int enginePrice,
                      List<String> shields, String enginesAfter, String collector, int collectorLevel, List<String> park,
                      long uridiumReserve, long energyTarget, String config, int sellMap) {
    }

    private static Kit bundled;

    private PalladiumKit() {
    }

    /** The kit of the bundled plan ({@code null} when the plan has none). */
    public static synchronized Kit bundled() {
        if (bundled == null) {
            try (InputStream in = PalladiumKit.class.getResourceAsStream(LevelPlan.RESOURCE)) {
                bundled = parse(new ObjectMapper().readTree(in));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return bundled;
    }

    static Kit parse(JsonNode root) {
        JsonNode p = root.path("palladium");
        if (p.isMissingNode()) {
            return null;
        }
        return new Kit(p.path("ship").asText(), list(p.path("extras")), list(p.path("kitAuction")), list(p.path("engines")),
                p.path("enginePrice").asInt(0), list(p.path("shields")), p.path("enginesAfter").asText("Ц3а"),
                p.path("collector").asText(""), p.path("collectorLevel").asInt(3), list(p.path("park")),
                p.path("uridiumReserve").asLong(0), p.path("energyTarget").asLong(0), p.path("config").asText(""),
                p.path("sellMap").asInt(92));
    }

    private static List<String> list(JsonNode n) {
        List<String> out = new ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return List.copyOf(out);
    }

    /** Items of each loot the account owns, wherever they are (any hangar, a drone, the inventory). */
    static Map<String, Integer> counts(SiteHangars.HangarInfo anyView) {
        Map<String, Integer> n = new LinkedHashMap<>();
        anyView.items().forEach(o -> n.merge(o.loot(), 1, Integer::sum));
        return n;
    }

    /**
     * The kit's extras the auction must still bring: the account owns fewer than two of the kind (one stands on the main
     * ship, one goes to the Nostromo). Nothing before the Nostromo is there.
     */
    public static List<String> missingExtras(Kit kit, SiteHangars.HangarInfo anyView, boolean shipOwned) {
        List<String> out = new ArrayList<>();
        if (kit == null || !shipOwned) {
            return out;
        }
        Map<String, Integer> n = counts(anyView);
        for (String loot : kit.kitAuction()) {
            if (n.getOrDefault(loot, 0) < 2) {
                out.add(loot);
            }
        }
        return out;
    }

    /** The bids for what is missing, as side goals the auction's planner takes like the plan's own. */
    public static List<LevelPlan.Goal> auctionGoals(List<String> missing) {
        List<LevelPlan.Goal> out = new ArrayList<>();
        for (String loot : missing) {
            out.add(new LevelPlan.Goal("ПД2 " + loot, "Комплект Nostromo: второй " + loot + " (аукцион)",
                    LevelPlan.Kind.UNKNOWN, "", List.of(), Set.of(loot), 2, "комплект Nostromo", null, List.of(loot), "", true));
        }
        return out;
    }

    /**
     * The moves that fit the Nostromo's hangar from the inventory: its extras (one of each kind), its engines when
     * {@code engines} (after the P.E.T.), shields in its drones in both configs. Items on another ship are not taken.
     */
    public static List<FitPlanner.Move> moves(Kit kit, SiteHangars.HangarInfo view, SiteHangars.DroneView drones,
                                              int hangarId, Map<String, Integer> slots, boolean engines) {
        return moves(kit, view, drones, hangarId, slots, engines, null);
    }

    /**
     * As above, the main ship first: an extra of a kind the main ship does not hold ({@code mainKinds}, {@link
     * FitPlanner#kind}) is not taken from the inventory — it is the main ship's, out for a moment (live 2026-11-26 on st2:
     * the SLE-03 taken off for the won SLE-04 dropped the places to 3, the site put AROL-X, RLLB-X, G3X-CRGO-X in the
     * inventory, and the Nostromo took them) — and one fitted on the Nostromo goes out for the main ship to take.
     * {@code mainKinds} null: no main ship to mind.
     */
    public static List<FitPlanner.Move> moves(Kit kit, SiteHangars.HangarInfo view, SiteHangars.DroneView drones,
                                              int hangarId, Map<String, Integer> slots, boolean engines,
                                              Set<String> mainKinds) {
        List<FitPlanner.Move> back = new ArrayList<>();
        if (mainKinds != null) {
            final SiteHangars.HangarInfo given = view;
            java.util.Set<String> here = new java.util.HashSet<>();
            given.fittedIds().forEach((config, sets) -> sets.getOrDefault("extras", List.of()).forEach(id -> {
                SiteHangars.Owned o = given.items().stream().filter(x -> x.id().equals(id)).findFirst().orElse(null);
                if (o != null && kit.extras().contains(o.loot()) && !mainKinds.contains(FitPlanner.kind(o.loot()))) {
                    back.add(new FitPlanner.Move(id, o.loot(), config, false, "extras"));
                    here.add(id);
                }
            }));
            List<SiteHangars.Owned> items = given.items().stream()
                    .filter(o -> !here.contains(o.id()) && !(o.hangar() == 0 && o.loot().startsWith("equipment_extra_")
                            && !mainKinds.contains(FitPlanner.kind(o.loot()))))
                    .toList();
            Map<Integer, Map<String, List<String>>> fitted = new java.util.TreeMap<>();
            given.fittedIds().forEach((config, sets) -> {
                Map<String, List<String>> m = new java.util.TreeMap<>();
                sets.forEach((set, ids) -> m.put(set, ids.stream().filter(id -> !here.contains(id)).toList()));
                fitted.put(config, m);
            });
            view = new SiteHangars.HangarInfo(given.fitted(), given.spare(), given.drones(), given.shape(), items, fitted);
        }
        Map<String, List<String>> fit = new LinkedHashMap<>();
        fit.put("extras", kit.extras());
        if (engines) {
            fit.put("generators", kit.engines());
        }
        fit.put("drone1", kit.shields());
        fit.put("drone2", kit.shields());
        LevelPlan own = new LevelPlan(1, 0, 0, List.of(), fit, List.of());
        List<FitPlanner.Move> out = new ArrayList<>(back);
        out.addAll(FitPlanner.plan(view, hangarId, slots, own));
        if (drones != null) {
            out.addAll(FitPlanner.drones(view, drones, hangarId, own));
        }
        return out;
    }

    /** How many engines the Nostromo still lacks (its generator places minus the engines it owns spare or fitted there). */
    public static int enginesMissing(Kit kit, SiteHangars.HangarInfo view, int hangarId, Map<String, Integer> slots) {
        int places = slots.getOrDefault("generators", 0);
        long have = view.items().stream()
                .filter(o -> kit.engines().contains(o.loot()) && (o.hangar() == 0 || o.hangar() == hangarId)).count();
        return (int) Math.max(0, places - have);
    }
}
