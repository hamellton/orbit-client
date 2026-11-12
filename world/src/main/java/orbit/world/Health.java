package orbit.world;

/**
 * Hit points, shield and nano hull. {@code -1} means unknown: the server reports other ships'
 * health only for the selected target and in attack hits.
 */
public record Health(double hp, double hpMax, int shield, int shieldMax, int nanoHull, int nanoHullMax) {

    public static final Health UNKNOWN = new Health(-1, -1, -1, -1, -1, -1);

    public boolean known() {
        return hpMax > 0;
    }

    /** Hit points as a share of the maximum, or {@code -1} when unknown. */
    public double hpPercent() {
        return hpMax > 0 && hp >= 0 ? hp / hpMax : -1;
    }

    public double shieldPercent() {
        return shieldMax > 0 && shield >= 0 ? (double) shield / shieldMax : -1;
    }

    Health withHp(double newHp, double newHpMax) {
        double max = newHpMax > 0 ? newHpMax : hpMax;
        // the hull is never above its maximum: a higher one (a booster, say) came without its own packet — the
        // known maximum was stale (ru1 2026-11-11: 485 995 hit points with 406 000 known from the login)
        if (newHpMax <= 0 && max > 0 && newHp > max) {
            max = newHp;
        }
        return new Health(newHp, max, shield, shieldMax, nanoHull, nanoHullMax);
    }

    Health withShield(int newShield, int newShieldMax) {
        return new Health(hp, hpMax, newShield, newShieldMax > 0 ? newShieldMax : shieldMax, nanoHull, nanoHullMax);
    }

    /**
     * The ship's own shield as the server states it, a 0 maximum included: a configuration with no shield
     * generators (live 2026-11-13: config 1 without shields showed 0 of config 2's full shield). In the recordings a
     * 0 maximum always came with 0 shield.
     */
    Health withOwnShield(int newShield, int newShieldMax) {
        return new Health(hp, hpMax, newShield, Math.max(0, newShieldMax), nanoHull, nanoHullMax);
    }

    Health withNanoHull(int newNano, int newNanoMax) {
        return new Health(hp, hpMax, shield, shieldMax, newNano, newNanoMax > 0 ? newNanoMax : nanoHullMax);
    }
}
