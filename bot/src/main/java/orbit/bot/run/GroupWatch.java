package orbit.bot.run;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The group by the config's {@code group} rules (AstraBot's INVITATION section): accept or reject
 * invitations, invite the listed players, leave a group without them, as the leader remove the others,
 * hand the lead over, set who may invite. Every action waits a few seconds like a player reading the
 * window, and nothing is repeated more often than a player would.
 */
final class GroupWatch {

    private static final Logger log = LoggerFactory.getLogger(GroupWatch.class);

    static final long INVITE_AGAIN_MS = 5 * 60_000;
    static final long ACTION_GAP_MS = 4_000;

    private final BotContext ctx;
    /** inviter id → when to answer */
    private final Map<Integer, Long> answerAt = new HashMap<>();
    private final Set<Integer> answered = new HashSet<>();
    private final Map<String, Long> invitedAt = new HashMap<>();
    private long nextAction;
    private long leftAt = Long.MIN_VALUE / 2;
    private int modeSetFor;
    private int leadGivenIn;

    GroupWatch(BotContext ctx) {
        this.ctx = ctx;
    }

    void tick() {
        BotConfig.GroupRules g = ctx.config().get().group();
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (h.id() == 0 || now < nextAction) {
            return;
        }
        if (invitations(g, w, now) || ownGroup(g, w, now)) {
            nextAction = now + ctx.pause(ACTION_GAP_MS, ACTION_GAP_MS + 3_000);
        }
    }

    /** Answers incoming invitations; true when an action was sent. */
    private boolean invitations(BotConfig.GroupRules g, WorldSnapshot w, long now) {
        for (WorldSnapshot.Invitation inv : w.invitations()) {
            if (!inv.incoming() || answered.contains(inv.inviterId())) {
                continue;
            }
            boolean listed = listed(g.players(), inv.inviterName(), w, inv.inviterId());
            boolean accept = !w.group().exists() && (g.accept() == BotConfig.Accept.ANY
                    || (g.accept() == BotConfig.Accept.LIST && listed));
            if (!accept && !g.rejectOthers()) {
                continue; // let it time out, like a player ignoring it
            }
            Long at = answerAt.computeIfAbsent(inv.inviterId(), k -> now + ctx.pause(2_000, 6_000));
            if (now < at) {
                continue;
            }
            answered.add(inv.inviterId());
            answerAt.remove(inv.inviterId());
            if (accept) {
                log.info("Принимаю приглашение в группу от {}", inv.inviterName());
                ctx.actions().acceptInvitation(inv.inviterId());
            } else {
                log.info("Отклоняю приглашение в группу от {}", inv.inviterName());
                ctx.actions().rejectInvitation(inv.inviterId());
            }
            return true;
        }
        // forget answered invitations that are gone, so a new one from the same player is answered again
        answered.removeIf(id -> w.invitations().stream().noneMatch(i -> i.incoming() && i.inviterId() == id));
        return false;
    }

    /** Leaving, kicking, inviting, leader and mode; true when an action was sent. */
    private boolean ownGroup(BotConfig.GroupRules g, WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Group group = w.group();
        boolean leader = group.exists() && group.leaderId() == h.id();
        if (group.exists() && g.leaveWithoutListed() && !g.players().isEmpty()
                && group.members().stream().noneMatch(m -> m.id() != h.id() && listed(g.players(), m.name(), w, m.id()))
                && now - leftAt > 60_000_000_000L) {
            log.info("В группе нет никого из списка — выхожу из группы");
            ctx.actions().leaveGroup();
            leftAt = now;
            return true;
        }
        if (leader && g.kickUnlisted() && !g.players().isEmpty()) {
            for (WorldSnapshot.Member m : group.members()) {
                if (m.id() != h.id() && !listed(g.players(), m.name(), w, m.id())) {
                    log.info("Исключаю из группы {} (нет в списке)", m.name());
                    ctx.actions().kick(m.id());
                    return true;
                }
            }
        }
        if (leader && g.openInvites() != null && modeSetFor != group.id()) {
            modeSetFor = group.id();
            log.info("Приглашать в группу могут: {}", g.openInvites() ? "все" : "только лидер");
            ctx.actions().invitationMode(g.openInvites());
            return true;
        }
        if (leader && !g.leader().isEmpty() && leadGivenIn != group.id()) {
            for (WorldSnapshot.Member m : group.members()) {
                if (m.id() != h.id() && m.name().equalsIgnoreCase(g.leader())) {
                    leadGivenIn = group.id();
                    log.info("Передаю лидерство {}", m.name());
                    ctx.actions().changeLeader(m.id());
                    return true;
                }
            }
        }
        if (g.invite() && (!group.exists() || leader || Boolean.TRUE.equals(g.openInvites()))) {
            for (String p : g.players()) {
                if (p.startsWith("[")) {
                    continue; // a clan is not a name to invite
                }
                boolean inGroup = group.members().stream().anyMatch(m -> m.name().equalsIgnoreCase(p));
                boolean pending = w.invitations().stream().anyMatch(i -> !i.incoming() && i.candidateName().equalsIgnoreCase(p));
                Long last = invitedAt.get(p.toLowerCase(Locale.ROOT));
                if (!inGroup && !pending && (last == null || now - last > INVITE_AGAIN_MS * 1_000_000)
                        && !p.equalsIgnoreCase(h.name())) {
                    invitedAt.put(p.toLowerCase(Locale.ROOT), now);
                    log.info("Приглашаю в группу {}", p);
                    ctx.actions().invite(p);
                    return true;
                }
            }
        }
        return false;
    }

    /** A name on the list, or a {@code [TAG]} of the player's clan when the player is in sight. */
    static boolean listed(Set<String> players, String name, WorldSnapshot w, int id) {
        if (name == null) {
            return false;
        }
        WorldSnapshot.Ship ship = w.ships().get(id);
        String tag = ship == null || ship.clanTag() == null || ship.clanTag().isBlank() ? null
                : "[" + ship.clanTag().toLowerCase(Locale.ROOT) + "]";
        for (String p : players) {
            String x = p.strip().toLowerCase(Locale.ROOT);
            if (x.equals(name.toLowerCase(Locale.ROOT)) || (tag != null && x.equals(tag))) {
                return true;
            }
        }
        return false;
    }
}
