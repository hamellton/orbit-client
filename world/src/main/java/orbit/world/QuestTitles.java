package orbit.world;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

/**
 * Quest titles from the game's own texts ({@code resource_quest.xml}, key {@code quest_title_<id>}, as the client's
 * {@code QuestLocale.getTitleEx}), bundled as {@code reference/maps/quest-titles-ru.json} — the server sends none
 * ({@code scripts/update-quest-titles.sh} after a game patch).
 */
public final class QuestTitles {

    private static Map<String, String> titles;

    private QuestTitles() {
    }

    private static synchronized Map<String, String> titles() {
        if (titles == null) {
            Map<String, String> m = new HashMap<>();
            try (InputStream in = orbit.world.maps.MapCatalog.class.getResourceAsStream("quest-titles-ru.json")) {
                if (in != null) {
                    new ObjectMapper().readTree(in).fields().forEachRemaining(e -> m.put(e.getKey(), e.getValue().asText()));
                }
            } catch (IOException e) {
                // no titles: quests by number
            }
            titles = m;
        }
        return titles;
    }

    /** The quest's title, or {@code null} when the texts have none. */
    public static String of(int questId) {
        return titles().get(String.valueOf(questId));
    }

    /** {@code 122004 «Безмолвный саботаж»}, or the number alone. */
    public static String label(int questId) {
        String t = of(questId);
        return t == null ? String.valueOf(questId) : questId + " «" + t + "»";
    }
}
