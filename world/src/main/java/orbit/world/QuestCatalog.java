package orbit.world;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;

/**
 * The quests the bot has seen — taken or offered with their details — kept in {@code local/quests-seen.json} for the
 * «Квесты» tab, where the user marks them «first», «skip», «cancel» (the server sends no titles: a quest is its number,
 * type, conditions and rewards).
 */
public final class QuestCatalog {

    public static final Path DEFAULT_FILE = Path.of("local", "quests-seen.json");
    private static final ObjectMapper JSON = new ObjectMapper();
    private static QuestCatalog shared;

    /** One quest as seen: its type, conditions and rewards in words, its lowest level (0 unknown). */
    public record Entry(int id, String type, String conditions, String rewards, int minLevel) {

        /** The game's title ({@link QuestTitles}), or "". */
        public String title() {
            String t = QuestTitles.of(id);
            return t == null ? "" : t;
        }
    }

    private final Path file;
    private final Map<Integer, Entry> entries = new TreeMap<>();

    public QuestCatalog(Path file) {
        this.file = file;
        try {
            if (file != null && Files.exists(file)) {
                JSON.readTree(file.toFile()).fields().forEachRemaining(e -> {
                    JsonNode n = e.getValue();
                    try {
                        int id = Integer.parseInt(e.getKey());
                        entries.put(id, new Entry(id, n.path("type").asText(""), n.path("conditions").asText(""),
                                n.path("rewards").asText(""), n.path("minLevel").asInt(0)));
                    } catch (NumberFormatException ignored) {
                        // not a quest number
                    }
                });
            }
        } catch (IOException e) {
            // a broken file is rewritten with what is seen next
        }
    }

    public static synchronized QuestCatalog shared() {
        if (shared == null) {
            shared = new QuestCatalog(DEFAULT_FILE);
        }
        return shared;
    }

    public synchronized Map<Integer, Entry> all() {
        return Map.copyOf(entries);
    }

    /** Remembers what the snapshot says of its quests (the taken ones, the offered ones' details and levels). */
    public synchronized void note(Quests q) {
        boolean changed = false;
        Map<Integer, Integer> levels = new TreeMap<>();
        q.offers().forEach(o -> levels.put(o.questId(), o.minLevel()));
        for (Quests.Quest quest : q.active().values()) {
            changed |= put(quest, levels.getOrDefault(quest.id(), 0));
        }
        for (Quests.Quest quest : q.details().values()) {
            changed |= put(quest, levels.getOrDefault(quest.id(), 0));
        }
        if (changed) {
            save();
        }
    }

    private boolean put(Quests.Quest quest, int minLevel) {
        Entry before = entries.get(quest.id());
        String conditions = String.join("; ", quest.leaves().stream().map(c -> {
            String t = QuestTexts.condition(c);
            return c.target() > 0 && !QuestTexts.isTime(c) && c.type() != Quests.AVOID_DEATH ? t + " " + (long) c.target() : t;
        }).toList());
        String rewards = String.join(", ", quest.rewards().stream().map(QuestTexts::reward).toList());
        Entry e = new Entry(quest.id(), QuestTexts.type(quest.types()), conditions, rewards,
                minLevel > 0 ? minLevel : before == null ? 0 : before.minLevel());
        if (e.equals(before)) {
            return false;
        }
        entries.put(quest.id(), e);
        return true;
    }

    private void save() {
        if (file == null) {
            return;
        }
        ObjectNode root = JSON.createObjectNode();
        entries.forEach((id, e) -> {
            ObjectNode n = root.putObject(String.valueOf(id));
            n.put("type", e.type());
            n.put("conditions", e.conditions());
            n.put("rewards", e.rewards());
            if (e.minLevel() > 0) {
                n.put("minLevel", e.minLevel());
            }
        });
        try {
            Path dir = file.toAbsolutePath().getParent();
            if (dir != null) {
                Files.createDirectories(dir);
            }
            JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
        } catch (IOException e) {
            // kept in memory
        }
    }
}
