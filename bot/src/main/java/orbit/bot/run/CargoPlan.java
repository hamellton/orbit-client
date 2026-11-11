package orbit.bot.run;

import java.util.List;

/**
 * How a full hold is handled: {@code mode} as {@code BotConfig.Collect.cargo} (NONE, STOP, BASE, PET), the map to
 * sell on ({@code 0} = the nearest own base, x-1 or x-8) and the ores to sell. A module may set its own (palladium:
 * BASE on 5-2, palladium only); otherwise the config's «Сбор → Полный трюм».
 */
public record CargoPlan(String mode, int map, List<Integer> ores, boolean always) {

    public CargoPlan {
        ores = List.copyOf(ores);
    }

    public CargoPlan(String mode, int map, List<Integer> ores) {
        this(mode, map, ores, false);
    }
}
