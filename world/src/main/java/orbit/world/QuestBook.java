package orbit.world;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.protocol.Packet;
import orbit.protocol.gen.LootModule;
import orbit.protocol.gen.QuestCancelledCommand;
import orbit.protocol.gen.QuestCaseModule;
import orbit.protocol.gen.QuestCompletedCommand;
import orbit.protocol.gen.QuestConditionModule;
import orbit.protocol.gen.QuestConditionUpdateCommand;
import orbit.protocol.gen.QuestDefinitionModule;
import orbit.protocol.gen.QuestElementModule;
import orbit.protocol.gen.QuestFailedCommand;
import orbit.protocol.gen.QuestGiverModule;
import orbit.protocol.gen.QuestGiversAvailableCommand;
import orbit.protocol.gen.QuestInfoCommand;
import orbit.protocol.gen.QuestInitializationCommand;
import orbit.protocol.gen.QuestListCommand;
import orbit.protocol.gen.QuestListRemoveCommand;
import orbit.protocol.gen.QuestListUpdateCommand;
import orbit.protocol.gen.QuestPrivilegeCommand;
import orbit.protocol.gen.QuestSlimInfoModule;
import orbit.protocol.gen.QuestTypeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The quest packets applied to {@link Quests} (the world's part for quests). */
final class QuestBook {

    private static final Logger log = LoggerFactory.getLogger(QuestBook.class);

    private final Map<Integer, Quests.Quest> active = new LinkedHashMap<>();
    private final List<Quests.Offer> offers = new ArrayList<>();
    private final Map<Integer, Quests.Quest> details = new LinkedHashMap<>();
    private final Set<Integer> givers = new LinkedHashSet<>();
    private int maxQuests;
    private int maxEventQuests;
    private boolean onlyStarter;
    private long offersNanos;
    private int privileged;
    private Quests snapshot = Quests.NONE;
    private boolean dirty;

    /** @return true when the packet was a quest packet */
    boolean apply(Packet p, long nanos) {
        switch (p) {
            case QuestInitializationCommand q when q.quest != null -> {
                Quests.Quest quest = quest(q.quest);
                boolean fresh = active.put(quest.id(), quest) == null;
                if (fresh && offersNanos != 0) {
                    log.info("Квест {} взят: {}", QuestTitles.label(quest.id()), describe(quest));
                }
                markOffer(quest.id(), Quests.RUNNING);
            }
            case QuestConditionUpdateCommand u when u.state != null -> update(u.questConditionId, u.state.currentValue,
                    u.state.active, u.state.completed);
            case QuestCompletedCommand c -> {
                Quests.Quest q = active.remove(c.id);
                log.info("Квест {} выполнен{}", QuestTitles.label(c.id), q == null ? "" : ": " + rewards(q));
                markOffer(c.id, Quests.COMPLETED);
            }
            case QuestFailedCommand f -> {
                active.remove(f.id);
                log.warn("Квест {} провален", QuestTitles.label(f.id));
            }
            case QuestCancelledCommand c -> {
                active.remove(c.id);
                log.info("Квест {} отменён", QuestTitles.label(c.id));
            }
            case QuestPrivilegeCommand pr -> privileged = pr.questId;
            case QuestListCommand l -> {
                offers.clear();
                l.list.forEach(s -> offers.add(offer(s)));
                maxQuests = l.maxQuests;
                maxEventQuests = l.maxEventQuests;
                onlyStarter = l.onlyStarter;
                offersNanos = nanos;
            }
            case QuestListUpdateCommand l -> {
                for (QuestSlimInfoModule s : l.list) {
                    Quests.Offer o = offer(s);
                    int i = indexOf(o.questId());
                    if (i >= 0) {
                        offers.set(i, o);
                    } else {
                        offers.add(o);
                    }
                }
                offersNanos = nanos;
            }
            case QuestListRemoveCommand r when r.list != null -> offers.removeIf(o -> r.list.contains(o.questId()));
            case QuestInfoCommand i when i.definition != null -> {
                Quests.Quest q = quest(i.definition);
                details.put(q.id(), q);
            }
            case QuestGiversAvailableCommand g -> {
                givers.clear();
                g.questGivers.forEach(m -> givers.add(m.mapObjectId));
            }
            default -> {
                return false;
            }
        }
        dirty = true;
        return true;
    }

    /** A new map: the quest givers there are not known yet (the taken quests stay). */
    void clearMap() {
        givers.clear();
        dirty = true;
    }

    Quests snapshot() {
        if (dirty) {
            dirty = false;
            snapshot = new Quests(Map.copyOf(active), List.copyOf(offers), Map.copyOf(details), Set.copyOf(givers),
                    maxQuests, maxEventQuests, onlyStarter, offersNanos, privileged);
        }
        return snapshot;
    }

    private void update(int conditionId, double current, boolean on, boolean completed) {
        for (Map.Entry<Integer, Quests.Quest> e : active.entrySet()) {
            Quests.Quest q = e.getValue();
            List<Quests.Condition> changed = replace(q.conditions(), conditionId, current, on, completed);
            if (changed != null) {
                e.setValue(new Quests.Quest(q.id(), q.types(), changed, q.rewards(), q.star()));
                return;
            }
        }
    }

    /** The list with the condition updated, or {@code null} when it is not in it. */
    private static List<Quests.Condition> replace(List<Quests.Condition> list, int id, double current, boolean on,
                                                  boolean completed) {
        for (int i = 0; i < list.size(); i++) {
            Quests.Condition c = list.get(i);
            Quests.Condition next = null;
            if (c.id() == id) {
                next = c.with(current, on, completed);
            } else {
                List<Quests.Condition> sub = replace(c.sub(), id, current, on, completed);
                if (sub != null) {
                    next = new Quests.Condition(c.id(), c.type(), c.matches(), c.target(), c.current(), c.active(),
                            c.completed(), c.mandatory(), sub);
                }
            }
            if (next != null) {
                List<Quests.Condition> out = new ArrayList<>(list);
                out.set(i, next);
                return List.copyOf(out);
            }
        }
        return null;
    }

    private int indexOf(int questId) {
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).questId() == questId) {
                return i;
            }
        }
        return -1;
    }

    private void markOffer(int questId, int status) {
        int i = indexOf(questId);
        if (i >= 0) {
            Quests.Offer o = offers.get(i);
            offers.set(i, new Quests.Offer(o.questId(), o.types(), o.minLevel(), status, o.star()));
        }
    }

    static Quests.Offer offer(QuestSlimInfoModule s) {
        return new Quests.Offer(s.questId, types(s.types), s.minLevel,
                s.acceptabilityStatus == null ? Quests.NOT_ACCEPTABLE : s.acceptabilityStatus.type, s.star);
    }

    static Quests.Quest quest(QuestDefinitionModule d) {
        List<Quests.Condition> conditions = new ArrayList<>();
        if (d.rootCase != null) {
            collect(d.rootCase, conditions);
        }
        List<Quests.Reward> rewards = new ArrayList<>();
        if (d.rewards != null) {
            for (LootModule l : d.rewards) {
                rewards.add(new Quests.Reward(l.lootId, l.amount));
            }
        }
        return new Quests.Quest(d.id, types(d.types), List.copyOf(conditions), List.copyOf(rewards), d.star);
    }

    /** A case's conditions, nested cases flattened into the list (the client keeps only their conditions). */
    private static void collect(QuestCaseModule c, List<Quests.Condition> out) {
        if (c.modifier == null) {
            return;
        }
        for (QuestElementModule e : c.modifier) {
            if (e.questCase != null && e.questCase.id > 0) {
                collect(e.questCase, out);
            } else if (e.condition != null && e.condition.id > 0) {
                out.add(condition(e.condition));
            }
        }
    }

    private static Quests.Condition condition(QuestConditionModule c) {
        List<Quests.Condition> sub = new ArrayList<>();
        if (c.subConditions != null) {
            c.subConditions.forEach(s -> sub.add(condition(s)));
        }
        boolean on = c.state != null && c.state.active;
        boolean done = c.state != null && c.state.completed;
        double current = c.state == null ? 0 : c.state.currentValue;
        return new Quests.Condition(c.id, c.displayType, c.matches == null ? List.of() : List.copyOf(c.matches),
                c.targetValue, current, on, done, c.mandatory, List.copyOf(sub));
    }

    private static List<Integer> types(List<QuestTypeModule> types) {
        return types == null ? List.of() : types.stream().map(t -> (int) t.type).toList();
    }

    /** {@code 4122/35000 Protegit…} for the log, short. */
    static String describe(Quests.Quest q) {
        return String.join("; ", q.leaves().stream().map(QuestBook::describe).toList());
    }

    static String describe(Quests.Condition c) {
        String what = QuestTexts.condition(c);
        return c.target() > 0 && !QuestTexts.isTime(c) ? what + " " + (long) c.current() + "/" + (long) c.target() : what;
    }

    static String rewards(Quests.Quest q) {
        return String.join(", ", q.rewards().stream().map(r -> QuestTexts.reward(r)).toList());
    }
}
