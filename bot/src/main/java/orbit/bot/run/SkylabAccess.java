package orbit.bot.run;

import java.io.IOException;
import java.util.Map;

/** The account's Skylab on the website, for the bot (the app reads and sends through its web session). */
public interface SkylabAccess {

    /** @param ores the storage by ore name ({@code promerium}, {@code seprom}…), what lies there */
    record Stock(boolean transporting, Map<String, Long> ores) {
    }

    /** The page read now. */
    Stock stock() throws IOException;

    /** Starts the transport module with these amounts by ore name ({@code fast}: the quick paid one). */
    void transport(Map<String, Long> amounts, boolean fast) throws IOException;
}
