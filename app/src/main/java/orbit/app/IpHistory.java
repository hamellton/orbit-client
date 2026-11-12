package orbit.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The public IP and its country each account was last seen with through its proxy ({@code local/proxy-seen.json},
 * plan 5в.5): an account's IP must not jump between countries, and two accounts behind one IP are linked by the game.
 */
public final class IpHistory {

    private static final Logger log = LoggerFactory.getLogger(IpHistory.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    public static final Path DEFAULT_FILE = Path.of("local", "proxy-seen.json");

    /** What an account was seen with; {@code country} may be {@code null} (the service did not say). */
    public record Seen(String ip, String country, long at) {
    }

    private static IpHistory shared;

    /** The program's history in {@link #DEFAULT_FILE}. */
    public static synchronized IpHistory shared() {
        if (shared == null) {
            shared = new IpHistory(DEFAULT_FILE);
        }
        return shared;
    }

    private final Path file;
    private final Map<String, Seen> seen = new LinkedHashMap<>();

    public IpHistory(Path file) {
        this.file = file;
        try {
            if (Files.exists(file)) {
                JsonNode root = JSON.readTree(file.toFile());
                root.fields().forEachRemaining(e -> {
                    JsonNode n = e.getValue();
                    String country = n.path("country").asText("");
                    seen.put(e.getKey(), new Seen(n.path("ip").asText(""), country.isEmpty() ? null : country,
                            n.path("at").asLong(0)));
                });
            }
        } catch (IOException | RuntimeException e) {
            log.warn("Не прочитать {}: {}", file, e.getMessage());
        }
    }

    public synchronized Seen get(String account) {
        return seen.get(account);
    }

    public synchronized Map<String, Seen> all() {
        return Map.copyOf(seen);
    }

    /**
     * Remembers what the account is seen with now.
     *
     * @return the country it had before when that differs from the new one (both known), else {@code null}
     */
    public synchronized String record(String account, String ip, String country) {
        if (ip == null || ip.isBlank()) {
            return null;
        }
        Seen before = seen.get(account);
        String land = country != null ? country : before != null && ip.equals(before.ip()) ? before.country() : null;
        seen.put(account, new Seen(ip, land, System.currentTimeMillis()));
        save();
        return before != null && before.country() != null && land != null && !before.country().equals(land)
                ? before.country() : null;
    }

    public synchronized void remove(String account) {
        if (seen.remove(account) != null) {
            save();
        }
    }

    private void save() {
        ObjectNode root = JSON.createObjectNode();
        seen.forEach((k, v) -> {
            ObjectNode n = root.putObject(k);
            n.put("ip", v.ip());
            if (v.country() != null) {
                n.put("country", v.country());
            }
            n.put("at", v.at());
        });
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            JSON.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), root);
            Files.move(tmp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            log.warn("Не записать {}: {}", file, e.getMessage());
        }
    }
}
