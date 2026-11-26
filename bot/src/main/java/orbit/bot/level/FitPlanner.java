package orbit.bot.level;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Which items the active ship's configs should hold and the inventory moves that get them there (docs/LEVELING.md
 * «Раскладка»): a pure function over the hangar view and the plan's {@link LevelPlan#fit} order — it moves nothing.
 *
 * <p>Per config (1, 2) and slotset: the best items of the order that this hangar owns (spare, or fitted here — one item
 * may stand in both configs, docs/recon/ASSEMBLY.md) fill its slots. When the slots are full, the worst fitted ones go
 * out first (an item outside the order, a shield among the generators, counts as the worst), then the better ones go in.
 */
public final class FitPlanner {

    private FitPlanner() {
    }

    /**
     * One inventory move ({@code inventory.php action=move}, {@code orbit.auth.Hangars.move}).
     *
     * @param toShip true: from the inventory into the config's slotset; false: out of it into the inventory
     */
    public record Move(String itemId, String loot, int config, boolean toShip, String slotset, String droneId) {

        public Move(String itemId, String loot, int config, boolean toShip, String slotset) {
            this(itemId, loot, config, toShip, slotset, null);
        }

        public String describe() {
            return (toShip ? "поставить " : "снять ") + loot + " (" + itemId + ") " + (toShip ? "в" : "из")
                    + (droneId != null ? " дрон " + droneId + ", конфиг " + config : " конфиг " + config + " " + slotset);
        }
    }

    /** The drone slotset of the site ({@code DataInterchange.DEFAULT}). */
    public static final String DRONE_SLOTSET = "default";

    /**
     * The drones' items per config by the plan's {@code fit.drone<c>} order, best first (docs/LEVELING.md «Дроны»; a user's
     * choice 2026-11-26: config 1 — the lasers the ship does not take, config 2 — the shields): the best owned items not on
     * the ship fill the drones' places; an item outside the order or no longer among the best goes out first.
     */
    public static List<Move> drones(SiteHangars.HangarInfo h, SiteHangars.DroneView v, int hangarId, LevelPlan plan) {
        List<Move> out = new ArrayList<>();
        if (v.drones().isEmpty()) {
            return out;
        }
        Set<String> onShip = new HashSet<>();
        h.fittedIds().values().forEach(m -> m.values().forEach(onShip::addAll));
        for (int config : new int[] {1, 2}) {
            List<String> order = plan.fit().get("drone" + config);
            if (order == null || order.isEmpty()) {
                continue;
            }
            Map<String, List<String>> now = v.fitted().getOrDefault(config, Map.of());
            int places = v.drones().stream().mapToInt(SiteHangars.Drone::slots).sum();
            Map<String, SiteHangars.Owned> byId = new java.util.HashMap<>();
            h.items().forEach(o -> byId.put(o.id(), o));
            List<SiteHangars.Owned> own = h.items().stream()
                    .filter(o -> order.contains(o.loot()) && (o.hangar() == 0 || o.hangar() == hangarId)
                            && !onShip.contains(o.id()))
                    .sorted(Comparator.comparingInt((SiteHangars.Owned o) -> rank(order, o.loot()))
                            .thenComparing(SiteHangars.Owned::id))
                    .toList();
            Set<String> wanted = new HashSet<>();
            own.stream().limit(places).forEach(o -> wanted.add(o.id()));
            Set<String> placed = new HashSet<>();
            Map<String, Integer> free = new java.util.LinkedHashMap<>();
            for (SiteHangars.Drone d : v.drones()) {
                int kept = 0;
                for (String id : now.getOrDefault(d.id(), List.of())) {
                    if (wanted.contains(id) && placed.add(id)) {
                        kept++;
                    } else {
                        SiteHangars.Owned o = byId.get(id);
                        out.add(new Move(id, o == null ? "?" : o.loot(), config, false, DRONE_SLOTSET, d.id()));
                    }
                }
                free.put(d.id(), Math.max(0, d.slots() - kept));
            }
            java.util.Iterator<SiteHangars.Owned> next = own.stream().filter(o -> wanted.contains(o.id())
                    && !placed.contains(o.id())).iterator();
            for (var e : free.entrySet()) {
                for (int i = 0; i < e.getValue() && next.hasNext(); i++) {
                    SiteHangars.Owned o = next.next();
                    out.add(new Move(o.id(), o.loot(), config, true, DRONE_SLOTSET, e.getKey()));
                }
            }
        }
        return out;
    }

    public static List<Move> plan(SiteHangars.HangarInfo h, int hangarId, Map<String, Integer> slots, LevelPlan plan) {
        List<Move> out = new ArrayList<>();
        for (var e : plan.fit().entrySet()) {
            String set = e.getKey();
            List<String> order = e.getValue();
            int n = slots.getOrDefault(set, 0);
            if (n <= 0) {
                continue;
            }
            Comparator<SiteHangars.Owned> best = Comparator.comparingInt(o -> rank(order, o.loot()));
            List<SiteHangars.Owned> own = h.items().stream()
                    .filter(o -> order.contains(o.loot()) && (o.hangar() == 0 || o.hangar() == hangarId))
                    .sorted(best.thenComparing(SiteHangars.Owned::id)).toList();
            if ("extras".equals(set)) {
                // one of each kind: a second CPU of a kind gives nothing (and the Nostromo's own set must stay for it)
                Set<String> fittedHere = new HashSet<>();
                h.fittedIds().values().forEach(m -> fittedHere.addAll(m.getOrDefault(set, List.of())));
                Map<String, SiteHangars.Owned> one = new java.util.LinkedHashMap<>();
                own.stream().sorted(best.thenComparing((SiteHangars.Owned o) -> !fittedHere.contains(o.id())))
                        .forEach(o -> one.putIfAbsent(kind(o.loot()), o));
                own = one.values().stream().sorted(best.thenComparing(SiteHangars.Owned::id)).toList();
            }
            Set<String> wanted = new HashSet<>();
            own.stream().limit(n).forEach(o -> wanted.add(o.id()));
            for (int config : new int[] {1, 2}) {
                List<String> fittedIds = h.fittedIds().getOrDefault(config, Map.of()).getOrDefault(set, List.of());
                List<SiteHangars.Owned> fitted = fittedIds.stream()
                        .map(id -> h.items().stream().filter(o -> o.id().equals(id)).findFirst()
                                .orElse(new SiteHangars.Owned(id, "?", hangarId)))
                        .toList();
                List<SiteHangars.Owned> in = own.stream().filter(o -> wanted.contains(o.id()) && !fittedIds.contains(o.id()))
                        .toList();
                // extras: a fitted one of a kind the wanted set already has (REP-2 beside REP-4, SLE-02 beside SLE-04)
                // goes out even with places free — two of one kind give nothing
                Set<String> wantedKinds = new HashSet<>();
                if ("extras".equals(set)) {
                    own.stream().filter(o -> wanted.contains(o.id())).forEach(o -> wantedKinds.add(kind(o.loot())));
                }
                List<SiteHangars.Owned> twins = fitted.stream()
                        .filter(o -> !wanted.contains(o.id()) && wantedKinds.contains(kind(o.loot()))).toList();
                int free = Math.max(0, n - fitted.size() + twins.size());
                int outs = Math.max(0, in.size() - free);
                List<SiteHangars.Owned> worst = new ArrayList<>(twins);
                fitted.stream().filter(o -> !wanted.contains(o.id()) && !twins.contains(o))
                        .sorted(best.reversed()).limit(outs).forEach(worst::add);
                worst.forEach(o -> out.add(new Move(o.id(), o.loot(), config, false, set)));
                in.stream().limit(free + worst.size() - twins.size()).forEach(o -> out.add(new Move(o.id(), o.loot(), config, true, set)));
            }
        }
        return out;
    }

    /** The kind of an extra: the repair robots are one kind, the slot extenders one; any other CPU — itself. */
    public static String kind(String loot) {
        if (loot.startsWith("equipment_extra_repbot_")) {
            return "repbot";
        }
        if (loot.startsWith("equipment_extra_cpu_sle-")) {
            return "sle";
        }
        return loot;
    }

    /** The place in the order, best first; an item outside it ranks after all. */
    private static int rank(List<String> order, String loot) {
        int i = order.indexOf(loot);
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
