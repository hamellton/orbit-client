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
        if (schema < 2) {
            // 2: enemy bases are kept off (500, like AstraBot) unless a config says otherwise
            ObjectNode general = tree.path("general") instanceof ObjectNode g ? g : tree.putObject("general");
            if (!general.has("baseRadius")) {
                general.put("baseRadius", BotConfig.General.DEFAULT_BASE_RADIUS);
            }
        }
        if (schema < 3) {
            // 3: paid revives in a row are limited (2 within 3 minutes, then one at the base)
            // (no safety section: the defaults, which have the limit already)
            if (tree.path("safety") instanceof ObjectNode safety) {
                if (!safety.has("paidRevives")) {
                    safety.put("paidRevives", BotConfig.Safety.DEFAULT_PAID_REVIVES);
                }
                if (!safety.has("paidReviveMinutes")) {
                    safety.put("paidReviveMinutes", BotConfig.Safety.DEFAULT_PAID_REVIVE_MINUTES);
                }
            }
        }
        tree.put("schema", BotConfig.SCHEMA);
        try {
            return Json.MAPPER.treeToValue(tree, BotConfig.class);
        } catch (JsonProcessingException e) {
            throw new IOException("Конфиг повреждён: " + e.getOriginalMessage(), e);
        }
    }
}
