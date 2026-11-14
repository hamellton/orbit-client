package orbit.config;

/**
 * The behemoth module's train settings (tested live 2026-11-14 on the VPS, docs/BLACKLIGHT.md, and CARNAGE's BeheSolo):
 * the defaults, the config window's «Поставить как в CARNAGE» and what {@link ConfigCheck} compares a config with.
 */
public final class BehemothProven {

    /** Rewards came with up to 10 enemies by the behemoth at the first look; 13–15 meant a death and nothing. */
    public static final int TRAIN_LIMIT = 12;
    public static final int TRAIN_RADIUS = 1_500;
    /** Killed at the behemoth once — revive at the portal and on to the next map (the user, 2026-11-14, as CARNAGE). */
    public static final int DEATHS_PER_SPAWN = 1;

    private BehemothProven() {
    }
}
