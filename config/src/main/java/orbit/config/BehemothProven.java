package orbit.config;

/**
 * The behemoth module's train settings tested live (2026-11-14 on the VPS, docs/BLACKLIGHT.md): the defaults, the
 * config window's «Поставить проверенные настройки» and what {@link ConfigCheck} compares a config with.
 */
public final class BehemothProven {

    /** Rewards came with up to 10 enemies by the behemoth at the first look; 13–15 meant a death and nothing. */
    public static final int TRAIN_LIMIT = 12;
    public static final int TRAIN_RADIUS = 1_500;
    public static final int DEATHS_PER_SPAWN = 4;

    private BehemothProven() {
    }
}
