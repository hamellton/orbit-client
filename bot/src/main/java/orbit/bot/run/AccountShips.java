package orbit.bot.run;

/**
 * The account's ships chosen outside the config (the account's settings, {@code local/accounts.json}): hangar
 * ids belong to an account, so a config shared by accounts does not carry them. Set by the app at each login.
 */
public final class AccountShips {

    private volatile int palladiumCollect;
    private volatile int palladiumSell;
    private volatile int serviceShip;
    private volatile int startShip;

    /**
     * The ship to start on ({@code 0} = the one active on the site): the runner switches to it at the session's start,
     * unless the two-ship palladium's collect ship rules.
     */
    public void setStartShip(int hangar) {
        startShip = hangar;
    }

    public int startShip() {
        return startShip;
    }

    /** The ship for the Skylab and the enrichment (a big hold, e.g. the Citadel); {@code 0} = none chosen. */
    public void setServiceShip(int hangar) {
        serviceShip = hangar;
    }

    public int serviceShip() {
        return serviceShip;
    }

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
