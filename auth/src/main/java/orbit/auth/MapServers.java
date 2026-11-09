package orbit.auth;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Parses {@code maps.php}: map id → game server {@code host:port}. */
public final class MapServers {

    /** Port the client uses when {@code gameserverIP} has none. */
    public static final int DEFAULT_PORT = 5001;

    private static final Pattern MAP = Pattern.compile(
            "<map\\s+id=\"(\\d+)\"\\s*>\\s*<gameserverIP>([^<]+)</gameserverIP>", Pattern.CASE_INSENSITIVE);

    /** A game server address. */
    public record Address(String host, int port) {
        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private final Map<Integer, Address> byMap;

    private MapServers(Map<Integer, Address> byMap) {
        this.byMap = Map.copyOf(byMap);
    }

    public static MapServers parse(String xml) {
        Map<Integer, Address> out = new HashMap<>();
        Matcher m = MAP.matcher(xml);
        while (m.find()) {
            String hp = m.group(2).trim();
            int colon = hp.lastIndexOf(':');
            Address a = colon > 0
                    ? new Address(hp.substring(0, colon), Integer.parseInt(hp.substring(colon + 1)))
                    : new Address(hp, DEFAULT_PORT);
            out.put(Integer.parseInt(m.group(1)), a);
        }
        return new MapServers(out);
    }

    /** Server for the map, or {@code null} if the map is unknown. */
    public Address forMap(int mapId) {
        return byMap.get(mapId);
    }

    public int size() {
        return byMap.size();
    }
}
