package orbit.bot.level;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * What the leveling mode knows of the account at one moment (docs/LEVELING.md): the pilot's level and money, the ships
 * owned, the active ship with its slots, what each of its configs fits and the spare items. Read from the website's
 * hangar list and hangar view ({@link SiteHangars}) and the game (level, money).
 *
 * @param level         the pilot's level ({@code 0} unknown)
 * @param credits       credits ({@code -1} unknown)
 * @param uridium       uridium ({@code -1} unknown)
 * @param activeHangar  the active hangar's id ({@code 0} unknown)
 * @param activeShip    the active ship's loot id ({@code ship_liberator}…; {@code ""} unknown)
 * @param ownedShips    loot ids of every ship the account owns
 * @param slots         the active ship's slots by slotset ({@code lasers}, {@code generators}…)
 * @param fitted        per config (1, 2): slotset → loot ids fitted in the active ship
 * @param spare         loot id → how many lie in the inventory, fitted in no hangar
 * @param drones        the active ship's drones ({@code -1} unknown)
 */
public record AccountState(int level, long credits, long uridium, int activeHangar, String activeShip,
                           Set<String> ownedShips, Map<String, Integer> slots, Map<Integer, Map<String, List<String>>> fitted,
                           Map<String, Integer> spare, int drones) {

    public AccountState {
        ownedShips = Set.copyOf(ownedShips);
        slots = Map.copyOf(slots);
        fitted = Map.copyOf(fitted);
        spare = Map.copyOf(spare);
    }

    /** The slots of a slotset on the active ship ({@code 0} unknown or none). */
    public int slots(String slotset) {
        return slots.getOrDefault(slotset, 0);
    }

    /** The loot ids a config fits in a slotset (empty when none or unknown). */
    public List<String> fitted(int config, String slotset) {
        return fitted.getOrDefault(config, Map.of()).getOrDefault(slotset, List.of());
    }

    /** How many items of {@code accepted} the active ship's {@code slotset} holds in {@code config}. */
    public int fittedOf(int config, String slotset, Set<String> accepted) {
        return (int) fitted(config, slotset).stream().filter(accepted::contains).count();
    }

    /** Spare items of {@code accepted} in the inventory. */
    public int spareOf(Set<String> accepted) {
        int n = 0;
        for (String id : accepted) {
            n += spare.getOrDefault(id, 0);
        }
        return n;
    }

    /** The state as one line for the leveling log. */
    public String describe() {
        List<String> parts = new ArrayList<>();
        parts.add("уровень " + (level > 0 ? level : "?"));
        parts.add("кредиты " + (credits >= 0 ? credits : "?"));
        parts.add("уридий " + (uridium >= 0 ? uridium : "?"));
        parts.add("корабль " + (activeShip.isEmpty() ? "?" : activeShip) + " (ангар " + activeHangar + ")");
        parts.add("слоты " + slots);
        for (int c : new int[] {1, 2}) {
            parts.add("конфиг " + c + " " + fitted.getOrDefault(c, Map.of()));
        }
        parts.add("дроны " + (drones >= 0 ? drones : "?"));
        parts.add("запас " + spare);
        parts.add("корабли " + new java.util.TreeSet<>(ownedShips));
        return String.join("; ", parts);
    }
}
