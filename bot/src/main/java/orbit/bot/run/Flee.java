package orbit.bot.run;

import java.util.Comparator;
import java.util.Locale;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.protocol.gen.ClanRelationModule;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs from players by the config's {@code flee} section: which players count ({@link BotConfig.FleeMode}),
 * a never-run-from list, the portal the ship reaches before the enemy, a jump when the enemy follows or
 * shoots there, and a wait on the other side before the runner flies back to the working map.
 * Group members and allied clans are never enemies.
 */
public final class Flee {

    private static final Logger log = LoggerFactory.getLogger(Flee.class);

    /** At the portal: the enemy this close (or shooting) makes the ship jump. */
    static final double FOLLOWS = 1_200;

    private final BotContext ctx;
    private long lastThreatAt = Long.MIN_VALUE / 2;
    private String threatName = "";
    private int threatX;
    private int threatY;
    private boolean fleeing;
    private long jumpAt;
    private long jumpedAt = Long.MIN_VALUE / 2;
    private long waitUntil;
    private int jumpedFromMap;
    private boolean oneWayTold;

    public Flee(BotContext ctx) {
        this.ctx = ctx;
    }

    public String threat() {
        return threatName;
    }

    /** @return true while running away or waiting on the other side (nothing else may steer) */
    public boolean tick() {
        BotConfig.Flee f = ctx.config().get().flee();
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (waitUntil != 0) {
            if (now < waitUntil && h.mapId() != jumpedFromMap) {
                ctx.ship().want(ctx.config().get().general().roam());
                return true; // resting on the other side
            }
            if (now >= waitUntil) {
                log.info("Подождал после бегства — возвращаюсь");
                waitUntil = 0;
            }
        }
        if (f.mode() == BotConfig.FleeMode.OFF) {
            fleeing = false;
            return false;
        }
        WorldSnapshot.Ship threat = threat(f, w);
        if (threat != null) {
            lastThreatAt = now;
            threatName = threat.name();
            threatX = threat.x();
            threatY = threat.y();
        }
        boolean active = now - lastThreatAt < Math.max(5, f.rememberSec()) * 1_000_000_000L;
        if (!active) {
            if (fleeing) {
                log.info("Опасность миновала");
            }
            fleeing = false;
            oneWayTold = false;
            return false;
        }
        if (!fleeing) {
            fleeing = true;
            ctx.nav().stop();
            log.warn("Убегаю от {} ({})", threatName, reason(f));
        }
        ctx.ship().want(ctx.config().get().general().run());
        MapCatalog.Portal portal = portal(h);
        if (portal == null) {
            return true; // no portal known: keep still, safety may still heal
        }
        double dist = Math.hypot(portal.x() - h.x(), portal.y() - h.y());
        if (dist > Safety.AT_PORTAL) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != portal.x()) {
                ctx.nav().moveTo(portal.x(), portal.y(), true);
            }
            ctx.nav().tick();
            return true;
        }
        ctx.nav().stop();
        boolean close = threat != null && (threat.distanceTo(h.x(), h.y()) < FOLLOWS || shooting(threat, h));
        boolean oneWay = !ctx.config().get().safety().oneWayJumps() && Safety.oneWay(ctx.maps(), portal, h);
        if (f.jump() && close && oneWay && !oneWayTold) {
            log.warn("{} у портала, но с {} обратно не вернуться — не прыгаю", threatName, portal.toName());
            oneWayTold = true;
        }
        if (f.jump() && close && !oneWay && w.jumpingTo() == 0 && now - jumpedAt > 10_000_000_000L) {
            if (jumpAt == 0) {
                jumpAt = now + ctx.pause(200, 600);
            } else if (now >= jumpAt) {
                log.warn("{} у портала — прыгаю", threatName);
                ctx.actions().jump();
                jumpedAt = now;
                jumpAt = 0;
                jumpedFromMap = h.mapId();
                waitUntil = now + f.waitSec() * 1_000_000_000L + ctx.pause(0, 15_000);
            }
        }
        return true;
    }

    private static String reason(BotConfig.Flee f) {
        return switch (f.mode()) {
            case ATTACKERS -> "атакует";
            case ENEMIES_IN_SIGHT -> "враг рядом";
            case LIST -> "из списка";
            default -> "";
        };
    }

    /** The nearest player the mode counts, or {@code null}. */
    static WorldSnapshot.Ship threat(BotConfig.Flee f, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        Set<Integer> group = new java.util.HashSet<>();
        w.group().members().forEach(m -> group.add(m.id()));
        return w.ships().values().stream()
                .filter(s -> s.kind() == WorldSnapshot.ShipKind.PLAYER && s.id() != h.id() && !group.contains(s.id()))
                .filter(s -> !listed(f.never(), s))
                .filter(s -> switch (f.mode()) {
                    case ATTACKERS -> shooting(s, h);
                    case ENEMIES_IN_SIGHT -> shooting(s, h) || (enemy(s, h) && s.distanceTo(h.x(), h.y()) <= f.sight());
                    case LIST -> listed(f.players(), s) && (shooting(s, h) || s.distanceTo(h.x(), h.y()) <= f.sight());
                    default -> false;
                })
                .min(Comparator.comparingDouble(s -> s.distanceTo(h.x(), h.y())))
                .orElse(null);
    }

    static boolean shooting(WorldSnapshot.Ship s, WorldSnapshot.Hero h) {
        return s.attackTargetId() == h.id() || h.attackers().contains(s.id());
    }

    /** Another faction and not an allied clan (or a pact). */
    static boolean enemy(WorldSnapshot.Ship s, WorldSnapshot.Hero h) {
        if (s.relation() == ClanRelationModule.TYPE_ALLIED || s.relation() == ClanRelationModule.TYPE_NON_AGGRESSION_PACT) {
            return false;
        }
        return s.relation() == ClanRelationModule.TYPE_AT_WAR || (s.factionId() != 0 && s.factionId() != h.factionId());
    }

    /** By name (any case) or by clan tag written as {@code [TAG]}. */
    static boolean listed(Set<String> list, WorldSnapshot.Ship s) {
        if (list.isEmpty()) {
            return false;
        }
        String name = s.name() == null ? "" : s.name().toLowerCase(Locale.ROOT);
        String tag = s.clanTag() == null || s.clanTag().isBlank() ? null : "[" + s.clanTag().toLowerCase(Locale.ROOT) + "]";
        for (String e : list) {
            String x = e.strip().toLowerCase(Locale.ROOT);
            if (x.equals(name) || (tag != null && x.equals(tag))) {
                return true;
            }
        }
        return false;
    }

    /** The portal the ship reaches well before the enemy: the most head start, shortest flight on a tie. */
    private MapCatalog.Portal portal(WorldSnapshot.Hero h) {
        // the portals that let the ship through (shown by the server, none refused this run), else the map's known ones
        java.util.List<MapCatalog.Portal> all = SafePlaces.usablePortals(ctx.world().get(), ctx.maps(),
                ctx.stats().closedPortals);
        if (all.isEmpty()) {
            all = ctx.maps().portals(h.mapId()).stream()
                    .filter(p -> p.faction() == 0 || p.faction() == h.factionId()).toList();
        }
        // a portal with a way back first, unless the user lets the bot jump anywhere
        java.util.List<MapCatalog.Portal> back = ctx.config().get().safety().oneWayJumps() ? all
                : all.stream().filter(p -> !Safety.oneWay(ctx.maps(), p, h)).toList();
        return (back.isEmpty() ? all : back).stream()
                .max(Comparator.comparingDouble((MapCatalog.Portal p) ->
                        Math.hypot(p.x() - threatX, p.y() - threatY) - 1.5 * Math.hypot(p.x() - h.x(), p.y() - h.y())))
                .orElse(null);
    }
}
