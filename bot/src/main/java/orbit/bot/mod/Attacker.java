package orbit.bot.mod;

import orbit.bot.run.BotContext;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Attacks one ship the way the player does in the recordings: click it, choose the ammunition (the
 * config's, SAB while the own shield is low), the attack starts with the ammunition key 0.8–1.5 s
 * later; keeps the NPC's radius from the config.
 */
public final class Attacker {

    private static final Logger log = LoggerFactory.getLogger(Attacker.class);

    /**
     * Fewer rounds than this count as none: the game fires them in a salvo or two and switches to other
     * ammunition by itself, and switching back every time only spams the server.
     */
    static final double MIN_AMMO = 200;

    /** Farther than the radius by this much the target is being chased. */
    static final double CHASE_GAP = 300;

    private final BotContext ctx;
    private int targetId;
    private boolean shotAt;
    private long selectAt;
    private long pressAt;
    private long pressedAt = Long.MIN_VALUE / 2;
    private long ammoAt = Long.MIN_VALUE / 2;
    private long nextApproach;
    private int kills;
    private boolean t0Cubikon;
    private int circleDir;
    private String fallbackTold;
    private String triedAmmo;
    /** Wanted ammunition already reported missing (logged once). */
    private String missingAmmo;
    private String missingRocket;
    /** Ammunition switches sent without the game confirming them. */
    private int ammoTries;
    private long rocketSelectAt = Long.MIN_VALUE / 2;
    private long rocketPressAt;
    private long rocketFiredAt = Long.MIN_VALUE / 2;
    private long rocketFireAt;
    /** The ammunition selected before the bot switched to SAB, to go back to when no ammunition is configured. */
    private String beforeSab;
    /** Hull plus shield of the target when it last went down, and when. */
    private double lastHealth = -1;
    private long healthDropAt;
    /** NPCs given up on (no damage for {@link #NO_DAMAGE_MS}) until the time stored. */
    private final java.util.Map<Integer, Long> givenUp = new java.util.HashMap<>();
    /** Shooting this long without the target losing anything: something is wrong (no ammunition, out of range). */
    static final long NO_DAMAGE_MS = 30_000;
    public static final long GIVE_UP_MS = 120_000;

    /** An NPC the bot gave up on a moment ago (not to be picked again right away). */
    public boolean gaveUp(int id) {
        Long until = givenUp.get(id);
        if (until == null) {
            return false;
        }
        if (ctx.now() > until) {
            givenUp.remove(id);
            return false;
        }
        return true;
    }

    public Attacker(BotContext ctx) {
        this.ctx = ctx;
    }

    public int targetId() {
        return targetId;
    }

    /** The current target has been shot at (the attack ran). */
    public boolean shooting() {
        return shotAt;
    }

    public int kills() {
        return kills;
    }

    public void target(WorldSnapshot.Ship s) {
        if (s.id() == targetId) {
            return;
        }
        targetId = s.id();
        shotAt = false;
        circleDir = 0;
        t0Cubikon = CubikonModule.isCubikon(s);
        if (t0Cubikon) {
            ctx.stats().cubeFought(s.id());
        }
        selectAt = ctx.now() + ctx.pause(300, 900);
        pressAt = 0;
        lastHealth = -1;
        healthDropAt = ctx.now();
        WorldSnapshot.Hero h = ctx.world().get().hero();
        log.info("Цель: {} в {}", s.name(), (int) s.distanceTo(h.x(), h.y()));
    }

    public void clear() {
        targetId = 0;
        shotAt = false;
    }

    /**
     * @param move whether to keep the distance (false while the module moves the ship, e.g. onto a box)
     * @return false when there is no target (none set, or it is gone)
     */
    public boolean tick(boolean move) {
        if (targetId == 0) {
            return false;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship t = w.ships().get(targetId);
        if (t == null) {
            if (shotAt) {
                kills++;
                ctx.stats().kills.incrementAndGet();
                if (t0Cubikon) {
                    ctx.stats().cubesKilled.incrementAndGet();
                }
                log.info("Цель уничтожена (всего {})", kills);
            }
            clear();
            return false;
        }
        BotConfig c = ctx.config().get();
        long now = ctx.now();
        double radius = Targets.radius(c, t, h.mapId());
        double dist = t.distanceTo(h.x(), h.y());
        BotConfig.NpcRule rule = Targets.rule(c, t, h.mapId());
        boolean follow = rule != null && rule.has(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW);
        boolean chasing = move && dist > radius + CHASE_GAP && (follow || c.general().runModeChase());
        ctx.ship().want(chasing ? c.general().run() : c.general().offensive());
        if (move && dist > radius + 60 && now >= nextApproach) {
            // following: aim at where it flies, not where it is
            double tx = follow && t.moving() ? t.targetX() : t.x();
            double ty = follow && t.moving() ? t.targetY() : t.y();
            double d = Math.max(1, Math.hypot(tx - h.x(), ty - h.y()));
            double k = Math.max(0, (d - radius) / d);
            ctx.nav().moveTo(h.x() + (tx - h.x()) * k, h.y() + (ty - h.y()) * k, false);
            nextApproach = now + (follow ? ctx.pause(400, 800) : ctx.pause(700, 1_200));
        } else if (move && c.loot().circle() && (rule == null || !rule.has(BotConfig.NpcFlag.NO_CIRCLE))
                && now >= nextApproach) {
            circle(h, t, radius);
            nextApproach = now + ctx.pause(900, 1_500);
        }
        if (move) {
            ctx.nav().tick();
        }
        if (h.attackTargetId() == targetId) {
            shotAt = true;
            if (noDamage(t, now)) {
                return false;
            }
        } else {
            healthDropAt = now; // not shooting yet: nothing to expect
        }
        if (h.selectedId() != targetId) {
            if (now >= selectAt) {
                ctx.actions().select(t);
                selectAt = now + ctx.pause(1_500, 2_500);
                pressAt = 0;
            }
            return true;
        }
        String main = ammo(c, w, t);
        String ammo = usable(c, w, main);
        if (ammo != null && !ammo.equals(main) && !ammo.equals(fallbackTold)) {
            fallbackTold = ammo;
            log.info("Патроны {} кончились — беру запасные {}", orbit.config.ItemCatalog.label(main),
                    orbit.config.ItemCatalog.label(ammo));
        }
        WorldSnapshot.Item selected = w.selected("lasers");
        String sab = c.loot().sab() != null ? c.loot().sab() : "ammunition_laser_sab-50";
        if (ammo != null && ammo.equals(sab) && selected != null && !selected.id().equals(sab)) {
            beforeSab = selected.id();
        }
        if (ammo == null && selected != null && selected.id().equals(sab) && beforeSab != null) {
            ammo = beforeSab; // SAB is no longer wanted: back to what was in use
        }
        WorldSnapshot.Item wanted = ammo == null ? null : w.items().get(ammo);
        if (ammo != null && (wanted == null || wanted.counter() < MIN_AMMO) && !ammo.equals(missingAmmo)) {
            missingAmmo = ammo;
            log.warn("Нет патронов {} ({}) — стреляю тем, что выбрано в игре ({})", orbit.config.ItemCatalog.label(ammo),
                    wanted == null ? "нет в инвентаре" : (long) wanted.counter() + " шт.",
                    selected == null ? "?" : orbit.config.ItemCatalog.label(selected.id()));
        }
        if (wanted != null && wanted.selected()) {
            ammoTries = 0;
        }
        if (ammo != null && !ammo.equals(triedAmmo)) {
            triedAmmo = ammo; // SAB and back are two switches, not two failed tries of one
            ammoTries = 0;
        }
        if (wanted != null && !wanted.selected() && ammoTries >= 2 && now - ammoAt > 60_000_000_000L) {
            ammoTries = 0; // try again after the pause
        }
        if (wanted != null && !wanted.selected() && wanted.counter() >= MIN_AMMO && ammoTries < 2
                && now - ammoAt > 1_500_000_000L) {
            if (pressAt == 0) {
                pressAt = now + ctx.pause(800, 1_500);
            }
            if (now >= pressAt) {
                log.info("Патроны: {}", orbit.config.ItemCatalog.label(ammo));
                missingAmmo = null;
                ammoTries++;
                if (ammoTries == 2) {
                    log.warn("Игра не переключает патроны на {} (у бота: {} шт., выбраны {} — {} шт.) — минуту стреляю ими",
                            orbit.config.ItemCatalog.label(ammo), (long) wanted.counter(),
                            selected == null ? "?" : orbit.config.ItemCatalog.label(selected.id()),
                            selected == null ? 0 : (long) selected.counter());
                }
                ctx.actions().useItem(ammo); // with a target selected this also starts the attack
                ammoAt = now;
                pressedAt = now;
                pressAt = 0;
            }
            return true;
        }
        if (h.attackTargetId() == targetId) {
            rockets(c, w, t, dist, radius, now);
            return true;
        }
        if (pressAt == 0) {
            pressAt = now + ctx.pause(800, 1_500);
        }
        if (now >= pressAt && dist <= radius + 200 && now - pressedAt > 3_000_000_000L) {
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

    /** Each circling click moves on this much of the circle (radians), like a player dragging the mouse round. */
    static final double CIRCLE_STEP = Math.toRadians(35);

    /**
     * Within the radius: the next point a little further round the target, at the radius give or take a
     * few percent, always the same way round for one target.
     */
    private void circle(WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        if (circleDir == 0) {
            circleDir = ctx.random().nextBoolean() ? 1 : -1;
            log.info("Кружу вокруг {} ({}, радиус {})", t.name(), circleDir > 0 ? "по часовой" : "против часовой",
                    (int) radius);
        }
        double angle = Math.atan2(h.y() - t.y(), h.x() - t.x())
                + circleDir * CIRCLE_STEP * (0.8 + 0.4 * ctx.random().nextDouble());
        double r = radius * (0.92 + 0.1 * ctx.random().nextDouble());
        ctx.nav().moveTo(t.x() + Math.cos(angle) * r, t.y() + Math.sin(angle) * r, false);
    }

    /** Rockets are launched this close at most (a little beyond the laser radius). */
    static final double ROCKET_EXTRA = 200;

    /**
     * Keeps the configured rocket type selected (the automatic rocket CPU launches it while attacking);
     * with {@code fireRockets} also launches one whenever the rocket is ready.
     */
    private void rockets(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t, double dist, double radius, long now) {
        String rocket = rocket(c, w, t);
        if (rocket == null || BotConfig.Loot.NO_ROCKETS.equals(rocket)) {
            return;
        }
        WorldSnapshot.Item item = w.items().get(rocket);
        if (item == null || item.counter() <= 0) {
            if (!rocket.equals(missingRocket)) {
                missingRocket = rocket;
                log.warn("Нет ракет {} ({})", orbit.config.ItemCatalog.label(rocket), item == null ? "нет в инвентаре" : "0 шт.");
            }
            return;
        }
        if (!item.selected()) {
            if (now - rocketSelectAt > 2_000_000_000L) {
                if (rocketPressAt == 0) {
                    rocketPressAt = now + ctx.pause(300, 900);
                } else if (now >= rocketPressAt) {
                    log.info("Ракеты: {}", orbit.config.ItemCatalog.label(rocket));
                    missingRocket = null;
                    ctx.actions().useItem(rocket);
                    rocketSelectAt = now;
                    rocketPressAt = 0;
                }
            }
            return;
        }
        if (c.loot().fireRockets() && dist <= radius + ROCKET_EXTRA && item.remainingMs(now) == 0
                && now - rocketFiredAt > 1_000_000_000L) {
            if (rocketFireAt == 0) {
                rocketFireAt = now + ctx.pause(200, 800);
            } else if (now >= rocketFireAt) {
                ctx.actions().attackRocket(t);
                rocketFiredAt = now;
                rocketFireAt = 0;
            }
        }
    }

    /** The NPC's rocket, else the config's ({@code null} = leave the game's choice). */
    static String rocket(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t) {
        BotConfig.NpcRule r = Targets.rule(c, t, w.hero().mapId());
        return r != null && r.rocket() != null ? r.rocket() : c.loot().rocket();
    }

    /** The wanted ammunition if there is enough, else the first fallback that has enough, else the wanted. */
    public static String usable(BotConfig c, WorldSnapshot w, String wanted) {
        if (wanted == null) {
            return null;
        }
        WorldSnapshot.Item item = w.items().get(wanted);
        if (item != null && item.counter() >= MIN_AMMO) {
            return wanted;
        }
        for (String f : c.loot().fallback()) {
            WorldSnapshot.Item fi = w.items().get(f);
            if (fi != null && fi.counter() >= MIN_AMMO) {
                return f;
            }
        }
        if (item == null || item.counter() <= 0) {
            // nothing of the wanted nor of the spares: any laser ammunition there is, the cheap first, SAB last
            for (orbit.config.ItemCatalog.Item l : orbit.config.ItemCatalog.LASERS) {
                WorldSnapshot.Item li = w.items().get(l.id());
                if (li != null && li.counter() >= MIN_AMMO && !l.id().contains("sab")) {
                    return l.id();
                }
            }
        }
        return wanted;
    }

    /**
     * SAB on NPCs marked for it, while the own shield is low and the target has shield to drain; else the
     * NPC's own or the default ammunition.
     */
    static String ammo(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t) {
        BotConfig.Loot l = c.loot();
        BotConfig.NpcRule r = Targets.rule(c, t, w.hero().mapId());
        double shield = w.hero().health().shieldPercent();
        if (l.sab() != null && r != null && r.sab() && shield >= 0 && shield < l.sabBelow()
                && t.health().shield() >= l.sabNpcShieldMin() && t.health().shield() > 0) {
            return l.sab();
        }
        return r != null && r.ammo() != null ? r.ammo() : l.ammo();
    }

    /** True (and the target dropped) when the ship has shot at an NPC for a while without it losing anything. */
    private boolean noDamage(WorldSnapshot.Ship t, long now) {
        if (!t.health().known() || t0Cubikon) {
            healthDropAt = now;
            return false;
        }
        double health = t.health().hp() + Math.max(0, t.health().shield());
        if (lastHealth < 0 || health < lastHealth) {
            healthDropAt = now;
        }
        lastHealth = health;
        if (now - healthDropAt < NO_DAMAGE_MS * 1_000_000) {
            return false;
        }
        WorldSnapshot.Item laser = ctx.world().get().selected("lasers");
        log.warn("{} не теряет HP {} с (патроны {}) — бросаю цель", t.name(), NO_DAMAGE_MS / 1000,
                laser == null ? "?" : orbit.config.ItemCatalog.label(laser.id()) + " ×" + (long) laser.counter());
        givenUp.put(t.id(), now + GIVE_UP_MS * 1_000_000);
        ctx.nav().stop();
        clear();
        return true;
    }
}
