package orbit.world.maps;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The radius DarkBot recommends for each NPC ({@code reference/maps/npc-radius.json}, the list DarkBot shows, from
 * the user 2026-11-12): the default when a config's NPC table leaves the radius at 0. Names match by their letters
 * and digits ({@code -=[ Boss Saimon ]=-} → {@code BossSaimon}), the case kept where it tells two NPCs apart
 * ({@code Streuner} of x-1 and {@code StreuneR} of x-8), else ignored.
 */
public final class NpcRadius {

    private static NpcRadius bundled;

    private final Map<String, Double> exact = new HashMap<>();
    private final Map<String, Double> anyCase = new HashMap<>();

    public static synchronized NpcRadius bundled() {
        if (bundled == null) {
            NpcRadius r = new NpcRadius();
            try (InputStream in = NpcRadius.class.getResourceAsStream("npc-radius.json")) {
                if (in != null) {
                    r.read(new ObjectMapper().readTree(in));
                }
            } catch (IOException e) {
                // no table: the default radius for all
            }
            bundled = r;
        }
        return bundled;
    }

    void read(JsonNode root) {
        root.fields().forEachRemaining(e -> {
            if (e.getValue().isNumber()) {
                exact.put(e.getKey(), e.getValue().asDouble());
                String low = e.getKey().toLowerCase(Locale.ROOT);
                // two NPCs only told apart by the case: no case-free match for them
                anyCase.merge(low, e.getValue().asDouble(), (a, b) -> Double.NaN);
            }
        });
    }

    /** The recommended radius of an NPC by the name the game shows, or 0 when the table has none. */
    public double of(String name) {
        if (name == null) {
            return 0;
        }
        String key = compact(name);
        Double r = exact.get(key);
        if (r == null) {
            r = anyCase.get(key.toLowerCase(Locale.ROOT));
        }
        return r == null || r.isNaN() ? 0 : r;
    }

    static String compact(String name) {
        StringBuilder s = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                s.append(ch);
            }
        }
        return s.toString();
    }
}
