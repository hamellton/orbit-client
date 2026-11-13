package orbit.bot.mod;

import java.util.Collection;
import java.util.Set;
import orbit.bot.run.BotContext;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A fight with another player (the behemoth module's defence and enemy hunting, the user 2026-11-13: as AstraBot's
 * group behemoth and CARNAGE's PvpHelper / DefenseMode do it): select like a player, the laser ammunition, fire, keep
 * within laser range of a ship that runs, no circling (players are not NPCs: nothing is learned of them).
 */
public final class PlayerFight {

    private static final Logger log = LoggerFactory.getLogger(PlayerFight.class);

    /** Lasers reach about this far; the ship keeps a little inside. */
    static final double RANGE = 550;
    /** Given up when the player is this far (fled, or the leash). */
    static final double LOST = 2_500;

    private final BotContext ctx;
    private int targetId;
    private String targetName;
    private long selectAt;
    private long pressAt;
    private long ammoAt;
    private long pressedAt;
    private long seenAt;

    public PlayerFight(BotContext ctx) {
        this.ctx = ctx;
    }

    public int targetId() {
        return targetId;
    }

    public String targetName() {
        return targetName;
    }

    public void target(WorldSnapshot.Ship s, String why) {
        if (s.id() == targetId) {
            return;
        }
        targetId = s.id();
        targetName = s.name();
        selectAt = ctx.now() + ctx.pause(200, 700);
        pressAt = 0;
        seenAt = ctx.now();
        WorldSnapshot.Hero h = ctx.world().get().hero();
        log.info("Игрок {} — {} ({} от нас)", s.name(), why, (int) s.distanceTo(h.x(), h.y()));
    }

    public void clear() {
        targetId = 0;
        targetName = null;
    }

    /**
     * @param ammo the laser ammunition for players ({@code null}: what is selected in the game)
     * @return false when the fight is over (gone, dead, too far)
     */
    public boolean tick(String ammo) {
        if (targetId == 0) {
            return false;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship t = w.ships().get(targetId);
        long now = ctx.now();
        if (t == null) {
            if (w.destroyed().containsKey(targetId)) {
                log.info("Игрок {} уничтожен", targetName);
            } else if (now - seenAt < 3_000_000_000L) {
                return true; // out of sight for a moment
            } else {
                log.info("Игрок {} пропал из вида", targetName);
            }
            clear();
            return false;
        }
        seenAt = now;
        double dist = t.distanceTo(h.x(), h.y());
        if (dist > LOST) {
            log.info("Игрок {} ушёл ({} от нас) — бросаю", t.name(), (int) dist);
            clear();
            return false;
        }
        ctx.ship().want(ctx.config().get().general().offensive());
        if (dist > RANGE) {
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - t.x(), ctx.nav().destination().y() - t.y()) > 200) {
                ctx.nav().moveTo(t.x(), t.y(), true);
            }
        } else if (!ctx.nav().idle() && dist < RANGE * 0.6) {
            ctx.nav().stop();
        }
        ctx.nav().tick();
        if (h.selectedId() != targetId) {
            if (now >= selectAt) {
                ctx.actions().select(t);
                selectAt = now + ctx.pause(1_200, 2_000);
                pressAt = 0;
            }
            return true;
        }
        WorldSnapshot.Item wanted = ammo == null ? null : w.items().get(ammo);
        if (wanted != null && !wanted.selected() && wanted.counter() > 0 && now - ammoAt > 2_000_000_000L) {
            ctx.actions().useItem(ammo); // with a target selected this also starts the attack
            ammoAt = now;
            pressedAt = now;
            return true;
        }
        if (h.attackTargetId() == targetId) {
            return true;
        }
        if (pressAt == 0) {
            pressAt = now + ctx.pause(300, 800);
        }
        if (now >= pressAt && dist <= RANGE + 200 && now - pressedAt > 2_000_000_000L) {
            WorldSnapshot.Item laser = w.selected("lasers");
            if (laser != null && laser.counter() > 0) {
                ctx.actions().useItem(laser.id());
            } else {
                ctx.actions().attackLaser(t);
            }
            pressedAt = now;
            pressAt = 0;
        }
        return true;
    }

    /**
     * An enemy player: another faction (or on the black list by name), not a member of our group, not cloaked, not of
     * our clan or an allied one.
     */
    public static boolean enemy(WorldSnapshot.Ship s, WorldSnapshot.Hero h, Set<Integer> group, Collection<String> blacklist) {
        if (s.kind() != WorldSnapshot.ShipKind.PLAYER || s.cloaked() || group.contains(s.id()) || s.id() == h.id()) {
            return false;
        }
        if (s.name() != null && blacklist.contains(s.name())) {
            return true;
        }
        boolean ownClan = h.clanId() != 0 && s.clanId() == h.clanId();
        boolean allied = s.relation() == orbit.protocol.gen.ClanRelationModule.TYPE_ALLIED
                || s.relation() == orbit.protocol.gen.ClanRelationModule.TYPE_NON_AGGRESSION_PACT;
        return s.factionId() != 0 && s.factionId() != h.factionId() && !ownClan && !allied;
    }

    /** The ids of our group's members. */
    public static Set<Integer> group(WorldSnapshot w) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        for (WorldSnapshot.Member m : w.group().members()) {
            out.add(m.id());
        }
        return out;
    }
}
