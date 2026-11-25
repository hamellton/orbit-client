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
    public record Move(String itemId, String loot, int config, boolean toShip, String slotset) {

        public String describe() {
            return (toShip ? "поставить " : "снять ") + loot + " (" + itemId + ") " + (toShip ? "в" : "из") + " конфиг " + config
                    + " " + slotset;
        }
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
                int free = Math.max(0, n - fitted.size());
                int outs = Math.max(0, in.size() - free);
                List<SiteHangars.Owned> worst = fitted.stream().filter(o -> !wanted.contains(o.id()))
                        .sorted(best.reversed()).limit(outs).toList();
                worst.forEach(o -> out.add(new Move(o.id(), o.loot(), config, false, set)));
                in.stream().limit(free + worst.size()).forEach(o -> out.add(new Move(o.id(), o.loot(), config, true, set)));
            }
        }
        return out;
    }

    /** The place in the order, best first; an item outside it ranks after all. */
    private static int rank(List<String> order, String loot) {
        int i = order.indexOf(loot);
        return i < 0 ? Integer.MAX_VALUE : i;
    }
}
