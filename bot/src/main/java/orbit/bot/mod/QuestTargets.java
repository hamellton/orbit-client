package orbit.bot.mod;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import orbit.world.Quests;
import orbit.world.maps.NpcCatalog;
import orbit.world.maps.NpcMaps;

/**
 * What a quest condition asks the bot to do, in the bot's terms: the NPCs to kill by the names the game shows on the
 * map ({@code -=[ Protegit ]=-}; a condition only gives the NPC number, {@code npcs.json} its short name) and the maps
 * where they live ({@link NpcMaps}: the bundled start and what the bot saw).
 */
public final class QuestTargets {

    private QuestTargets() {
    }

    /** The condition kinds the quest module does. */
    public static boolean killing(Quests.Condition c) {
        return c.type() == Quests.KILL_NPC || c.type() == Quests.KILL_NPCS;
    }

    public static boolean travelling(Quests.Condition c) {
        return c.type() == Quests.VISIT_MAP || c.type() == Quests.MAP || c.type() == Quests.ON_MAP
                || c.type() == Quests.COORDINATES;
    }

    /** Limits the bot keeps by itself (time, deaths): no work of their own. */
    public static boolean passive(Quests.Condition c) {
        return switch (c.type()) {
            case Quests.TIMER, Quests.HASTE, Quests.COUNTDOWN, Quests.REAL_TIME_HASTE, Quests.AVOID_DEATH -> true;
            default -> false;
        };
    }

    /** The NPC numbers of a kill condition. */
    public static List<Integer> npcIds(Quests.Condition c) {
        List<Integer> out = new ArrayList<>();
        if (c.type() == Quests.KILL_NPC) {
            out.add(c.match(0));
        } else if (c.type() == Quests.KILL_NPCS) {
            int n = c.match(0);
            for (int i = 1; i <= n && i < c.matches().size(); i++) {
                out.add(c.match(i));
            }
        }
        out.removeIf(id -> id <= 0);
        return out;
    }

    /** Map id → the game's names of the condition's NPCs seen there. */
    public static Map<Integer, Set<String>> where(Quests.Condition c, NpcMaps maps) {
        Set<String> wanted = new LinkedHashSet<>();
        for (int id : npcIds(c)) {
            String n = NpcCatalog.bundled().name(id);
            if (n != null) {
                wanted.add(norm(n));
            }
        }
        Map<Integer, Set<String>> out = new TreeMap<>();
        for (int map : maps.maps()) {
            for (String game : maps.on(map)) {
                if (wanted.contains(norm(game))) {
                    out.computeIfAbsent(map, k -> new LinkedHashSet<>()).add(game);
                }
            }
        }
        return out;
    }

    /** The maps a travel condition names (any of them will do). */
    public static List<Integer> mapsOf(Quests.Condition c) {
        List<Integer> out = new ArrayList<>();
        if (c.type() == Quests.VISIT_MAP) {
            out.add(c.match(0));
        } else if (c.type() == Quests.COORDINATES) {
            out.add(c.match(2));
        } else {
            for (int i = 0; i < c.matches().size(); i++) {
                out.add(c.match(i));
            }
        }
        out.removeIf(m -> m <= 0);
        return out;
    }

    /** {@code -=[ Uber Protegit ]=-} and {@code UberProtegit} alike: letters and digits only, lower case. */
    static String norm(String name) {
        StringBuilder s = new StringBuilder();
        for (char ch : name.toCharArray()) {
            if (Character.isLetterOrDigit(ch)) {
                s.append(Character.toLowerCase(ch));
            }
        }
        return s.toString().toLowerCase(Locale.ROOT);
    }
}
