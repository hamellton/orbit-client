package orbit.config;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;

/**
 * Brings config files of older schemas up to {@link BotConfig#SCHEMA}. Each step edits the JSON
 * tree from version n to n + 1; files of a newer schema are refused (written by a newer bot).
 */
final class Migrations {

    private Migrations() {
    }

    static BotConfig read(ObjectNode tree) throws IOException {
        int schema = tree.path("schema").asInt(1);
        if (schema > BotConfig.SCHEMA) {
            throw new IOException("Конфиг новой версии (схема " + schema + ") — обновите бота");
        }
        // no migrations yet: schema 1 is the first
        tree.put("schema", BotConfig.SCHEMA);
        try {
            return Json.MAPPER.treeToValue(tree, BotConfig.class);
        } catch (JsonProcessingException e) {
            throw new IOException("Конфиг повреждён: " + e.getOriginalMessage(), e);
        }
    }
}
