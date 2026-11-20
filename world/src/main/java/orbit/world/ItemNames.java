package orbit.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Item names as the client shows them, from the game's own texts ({@code resource_items.xml}, key
 * {@code items_<lootId>_fullname}), bundled as {@code reference/maps/item-names-ru.json}
 * ({@code scripts/update-item-names.sh} after a game patch).
 */
public final class ItemNames {

    private static Map<String, String> names;

    private ItemNames() {
    }

    private static synchronized Map<String, String> names() {
        if (names == null) {
            Map<String, String> m = new HashMap<>();
            try (InputStream in = orbit.world.maps.MapCatalog.class.getResourceAsStream("item-names-ru.json")) {
                if (in != null) {
                    new ObjectMapper().readTree(in).fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
                }
            } catch (IOException e) {
                // no names: the ids as they are
            }
            names = m;
        }
        return names;
    }

    /**
     * The client's name of a loot id ({@code resource_blueprint_zeus-part} → «Фрагмент чертежа («Zeus»)»), or
     * {@code null} when the texts have none; a collectable is looked for with and without {@code collectable_}.
     */
    public static String of(String lootId) {
        if (lootId == null) {
            return null;
        }
        Map<String, String> m = names();
        String n = m.get(lootId);
        if (n == null && lootId.startsWith("resource_collectable_")) {
            n = m.get("resource_" + lootId.substring("resource_collectable_".length()));
        } else if (n == null && lootId.startsWith("resource_")) {
            n = m.get("resource_collectable_" + lootId.substring("resource_".length()));
        }
        return n;
    }
}
