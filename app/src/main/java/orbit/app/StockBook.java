package orbit.app;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The rare parts of a ship's Plus (docs/CRAFT.md: indoctrine accelerators and diametrion) in the account's store, kept
 * in {@code local/stock-<account>.json}; what of them came is in the Totals' loot already (the reward log). The game tells a
 * resource's amount only when it changes ({@code InventoryItemUpdatedCommand}, none at the login: recordings
 * 2026-11-12/14), so the last amount told is kept over logins and restarts, with when it was told; each change is logged.
 */
public final class StockBook {

    private static final Logger log = LoggerFactory.getLogger(StockBook.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Map<String, StockBook> ACCOUNTS = new ConcurrentHashMap<>();

    /** The rare parts of a Plus followed in store. */
    public static final List<String> TRACKED = List.of("resource_collectable_indoctrine-accelerator",
            "resource_collectable_diametrion");

    /** One part's amount in store and when the game told it (epoch ms). */
    public record Stock(double amount, long atMillis) {
    }

    private final Path file;
    private final Map<String, Stock> stock = new LinkedHashMap<>();

    StockBook(Path file) {
        this.file = file;
        load();
    }

    /** The account's book (kept on disk). */
    public static StockBook of(String account) {
        return ACCOUNTS.computeIfAbsent(account == null ? "" : account, a -> new StockBook(
                Path.of("local", "stock-" + (a.isEmpty() ? "default" : a.replaceAll("[^\\w.@-]", "_")) + ".json")));
    }

    /** The amounts the game has told this session; a change of a followed part is logged and kept. */
    public synchronized void update(Map<String, Double> resources, long nowMillis) {
        boolean changed = false;
        for (String id : TRACKED) {
            Double now = resources.get(id);
            if (now == null) {
                continue;
            }
            Stock before = stock.get(id);
            if (before != null && before.amount() == now) {
                continue;
            }
            String name = SessionSummary.clientName(id);
            if (before == null) {
                log.info("Склад: {} — {}", name, SessionSummary.number(now));
            } else {
                double d = now - before.amount();
                log.info("Склад: {} {} → {} ({}{})", name, SessionSummary.number(before.amount()),
                        SessionSummary.number(now), d > 0 ? "+" : "−", SessionSummary.number(Math.abs(d)));
            }
            stock.put(id, new Stock(now, nowMillis));
            changed = true;
        }
        if (changed) {
            save();
        }
    }

    /** The last amount the game told, {@code null} when never. */
    public synchronized Stock stock(String id) {
        return stock.get(id);
    }

    private void load() {
        try {
            if (file == null || !Files.exists(file)) {
                return;
            }
            JsonNode root = JSON.readTree(file.toFile());
            root.path("stock").properties().forEach(e -> stock.put(e.getKey(),
                    new Stock(e.getValue().path("amount").asDouble(), e.getValue().path("at").asLong())));
        } catch (IOException e) {
            // started anew
        }
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        ObjectNode s = root.putObject("stock");
        stock.forEach((id, v) -> {
            ObjectNode n = s.putObject(id);
            n.put("amount", v.amount());
            n.put("at", v.atMillis());
        });
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // kept in memory
        }
    }
}
