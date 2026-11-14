package orbit.bot.run;

import java.io.IOException;
import java.util.List;
import java.util.Set;

/** The account's equipment on the website (the inventory page's calls), for fitting a new CPU. */
public interface HangarAccess {

    /**
     * One CPU of a kind: its id, whether it is a spare (fitted in no hangar), its charges ({@code -1} unknown) and the
     * configs of the active ship that fit it.
     */
    record Cpu(String id, boolean spare, int charges, Set<Integer> configs) {

        public Cpu {
            configs = Set.copyOf(configs);
        }
    }

    /** The CPUs of {@code loot} the active ship's hangar view lists. */
    List<Cpu> cpus(String loot) throws IOException;

    /** Fits {@code id} into the active ship's config ({@code extras}), or takes it out to the inventory. */
    void move(String id, int config, boolean toShip) throws IOException;
}
