package orbit.world;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The account's quests as the server tells them (client facts: {@code QuestManager}, {@code QuestProxy},
 * {@code questfm/QuestParser}): the quests taken, with every condition's progress; what the quest giver offers
 * ({@code QuestListCommand}) and the details of offered quests ({@code QuestInfoCommand}, before accepting); which
 * quest givers the server switched on near the ship ({@code QuestGiversAvailableCommand}).
 *
 * @param active    taken quests by id
 * @param offers    the last quest giver list, in its order
 * @param details   offered quests' full definitions, by id (as far as asked)
 * @param givers    quest giver asset ids the server enabled now (the ship is close enough to open them)
 * @param maxQuests free slots for missions and challenges; {@code maxEventQuests} for event quests
 * @param privileged the quest shown in the HUD, 0 none
 */
public record Quests(Map<Integer, Quest> active, List<Offer> offers, Map<Integer, Quest> details,
                     java.util.Set<Integer> givers, int maxQuests, int maxEventQuests, boolean onlyStarter,
                     long offersNanos, int privileged) {

    public static final Quests NONE = new Quests(Map.of(), List.of(), Map.of(), java.util.Set.of(), 0, 0, false, 0, 0);

    /** {@code QuestTypeModule}. */
    public static final int STARTER = 1;
    public static final int MISSION = 2;
    public static final int DAILY = 3;
    public static final int CHALLENGE = 4;
    public static final int EVENT = 5;
    public static final int WEEKLY = 6;
    public static final int BATTLEPASS = 7;
    public static final int DIMINISHING = 8;
    public static final int SEASONPASS = 9;

    /** {@code QuestAcceptabilityStatusModule}. */
    public static final int NOT_ACCEPTABLE = 0;
    public static final int ACCEPTABLE = 1;
    public static final int RUNNING = 2;
    public static final int COMPLETED = 3;

    /** A quest: its types, its conditions (a tree as the server sends it) and rewards. */
    public record Quest(int id, List<Integer> types, List<Condition> conditions, List<Reward> rewards, int star) {

        public boolean is(int type) {
            return types.contains(type);
        }

        /** Every condition with no sub-conditions, depth first. */
        public List<Condition> leaves() {
            List<Condition> out = new ArrayList<>();
            conditions.forEach(c -> c.leaves(out));
            return out;
        }

        /** All conditions, sub-conditions included, depth first. */
        public List<Condition> all() {
            List<Condition> out = new ArrayList<>();
            conditions.forEach(c -> c.all(out));
            return out;
        }

        public boolean done() {
            return conditions.stream().filter(Condition::mandatory).allMatch(Condition::completed);
        }
    }

    /**
     * One condition. {@code type} is the server's {@code displayType} — the client builds the condition from it
     * ({@code QuestParser.as}); {@code matches} are its parameters (an NPC number, an ore number, map ids…).
     */
    public record Condition(int id, int type, List<String> matches, double target, double current, boolean active,
                            boolean completed, boolean mandatory, List<Condition> sub) {

        Condition with(double current, boolean active, boolean completed) {
            return new Condition(id, type, matches, target, current, active, completed, mandatory, sub);
        }

        /**
         * The work: this condition itself when its sub-conditions only bound it (a map to do it on, a time limit — live
         * 2026-11-12: 117079 «kill 10 NPC 177», sub «on 1-BL or 3-BL»), else the sub-conditions that are work.
         */
        void leaves(List<Condition> out) {
            List<Condition> work = sub.stream().filter(c -> !c.bound()).toList();
            if (work.isEmpty()) {
                out.add(this);
            } else {
                work.forEach(c -> c.leaves(out));
            }
        }

        /** A condition that only bounds its parent: where (on a map) or how fast / how safely. */
        public boolean bound() {
            return switch (type) {
                case MAP, ON_MAP, TIMER, HASTE, COUNTDOWN, REAL_TIME_HASTE, AVOID_DEATH -> true;
                default -> false;
            };
        }

        /** The maps a sub-condition says the work counts on (any of them), empty when anywhere. */
        public List<Integer> onMaps() {
            List<Integer> out = new ArrayList<>();
            for (Condition c : sub) {
                if (c.type == MAP || c.type == ON_MAP) {
                    for (int i = 0; i < c.matches.size(); i++) {
                        if (c.match(i) > 0) {
                            out.add(c.match(i));
                        }
                    }
                }
            }
            return out;
        }

        void all(List<Condition> out) {
            out.add(this);
            sub.forEach(c -> c.all(out));
        }

        /** {@code matches[i]} as a number, or {@code -1}. */
        public int match(int i) {
            if (i >= matches.size()) {
                return -1;
            }
            try {
                return Integer.parseInt(matches.get(i).trim());
            } catch (NumberFormatException e) {
                return -1;
            }
        }
    }

    public record Reward(String lootId, int amount) {
    }

    /** One entry of the quest giver's list. */
    public record Offer(int questId, List<Integer> types, int minLevel, int status, int star) {

        public boolean is(int type) {
            return types.contains(type);
        }
    }

    /**
     * The condition types the client knows ({@code QuestConditionModule.CONDITIONTYPE_*}), the ones a quest uses most.
     */
    public static final int TIMER = 1;
    public static final int HASTE = 2;
    public static final int ENDURANCE = 3;
    public static final int COUNTDOWN = 4;
    public static final int COLLECT = 5;
    public static final int KILL_NPC = 6;
    public static final int AVOID_DEATH = 10;
    public static final int COORDINATES = 11;
    public static final int TRAVEL = 13;
    public static final int MAP = 16;
    public static final int KILL_NPCS = 27;
    public static final int KILL_PLAYERS = 28;
    public static final int DAMAGE_NPCS = 29;
    public static final int DAMAGE_PLAYERS = 30;
    public static final int VISIT_MAP = 34;
    public static final int MISCELLANEOUS = 19;
    public static final int SALVAGE = 23;
    public static final int SELL_ORE = 52;
    public static final int LEVEL = 53;
    public static final int COLLECT_BONUS_BOX = 55;
    public static final int KILL_PLAYER = 61;
    public static final int VISIT_QUEST_GIVER = 62;
    public static final int REAL_TIME_HASTE = 63;
    public static final int COLLECT_LOOT = 67;
    public static final int REFINE_ORE = 69;
    public static final int ON_MAP = 77;
    public static final int FINISH_GALAXY_GATE = 79;
}
