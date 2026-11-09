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
        return new Health(newHp, newHpMax > 0 ? newHpMax : hpMax, shield, shieldMax, nanoHull, nanoHullMax);
    }

    Health withShield(int newShield, int newShieldMax) {
        return new Health(hp, hpMax, newShield, newShieldMax > 0 ? newShieldMax : shieldMax, nanoHull, nanoHullMax);
    }

    Health withNanoHull(int newNano, int newNanoMax) {
        return new Health(hp, hpMax, shield, shieldMax, newNano, newNanoMax > 0 ? newNanoMax : nanoHullMax);
    }
}
