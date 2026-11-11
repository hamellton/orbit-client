package orbit.bot.run;

/**
 * The account's ships chosen outside the config (the account's settings, {@code local/accounts.json}): hangar
 * ids belong to an account, so a config shared by accounts does not carry them. Set by the app at each login.
 */
public final class AccountShips {

    private volatile int palladiumCollect;
    private volatile int palladiumSell;

    /** The two-ship palladium sale's hangars; {@code 0} = not chosen for the account. */
    public void setPalladium(int collect, int sell) {
        palladiumCollect = collect;
        palladiumSell = sell;
    }

    public int palladiumCollect() {
        return palladiumCollect;
    }

    public int palladiumSell() {
        return palladiumSell;
    }
}
