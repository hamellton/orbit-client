package orbit.bot.run;

/**
 * A module's wish for a new cloaking CPU when the fitted one is empty ({@link CloakCare}): {@code craft} one in the
 * assembly when the account has no spare, {@code amount} at a time.
 */
public record CloakPlan(boolean craft, int amount) {

    public CloakPlan {
        amount = Math.max(1, amount);
    }
}
