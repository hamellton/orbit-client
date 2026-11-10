package orbit.bot.run;

import java.io.IOException;
import java.util.Map;

/** The account's shop as the bot needs it (the app wires the website shop in). */
public interface ShopAccess {

    /** @param price per unit; {@code maxAmount} per purchase (0 = no limit known) */
    record Offer(String item, double price, boolean uridium, int maxAmount) {
    }

    /** Ammunition and rocket offers by item id. */
    Map<String, Offer> offers() throws IOException;

    /** Buys; returns the shop's answer. {@link IOException} when it refused or failed. */
    String buy(String item, int amount) throws IOException;
}
