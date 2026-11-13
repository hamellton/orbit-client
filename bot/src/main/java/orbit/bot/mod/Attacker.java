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
    /**
     * Farther than this beyond the attack radius the ship is still on its way: the travel mode, the attack one
     * only on the approach (live 2026-11-11: after a repair the ship flew 9 000 to the cube in the attack config).
     */
    static final double APPROACH = 1_500;

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
    /** NPC kinds the ship cannot kill before it must repair (name → until, nanos), skipped a while. */
    private static final java.util.Map<String, Long> TOO_STRONG = new java.util.concurrent.ConcurrentHashMap<>();
    /** A fight is judged after this long, and such a kind skipped this long. */
    static final long JUDGE_NS = 15_000_000_000L;
    static final long TOO_STRONG_NS = 10 * 60_000_000_000L;
    static final long TOO_STRONG_MAX_NS = 60 * 60_000_000_000L;
    private static final java.util.Map<String, Integer> TOO_STRONG_TIMES = new java.util.concurrent.ConcurrentHashMap<>();
    /** The kill must come this much sooner than the repair. */
    static final double WIN_MARGIN = 1.3;
    private double judgeTarget0 = -1;
    private double judgeHull0 = -1;

    /** The kind was found too strong a while ago: not taken (nor looked for with the locator) until then. */
    public boolean tooStrong(String name) {
        Long until = name == null ? null : TOO_STRONG.get(name);
        return until != null && ctx.now() < until;
    }

    /**
     * After {@link #JUDGE_NS} of fighting: at this pace the target would die after the ship has to leave for repair
     * (live 2026-11-13: Boss Kristallon on LCB-10 — 30 % of it for 50 % of the hull, healed back by the next try) —
     * left, and its kind skipped {@link #TOO_STRONG_NS}.
     */
    private boolean cannotWin(BotConfig c, WorldSnapshot.Ship t, WorldSnapshot.Hero h, long now) {
        double tp = FightShare.of(t);
        double hp = h.health().hpPercent();
        if (tp < 0 || hp < 0) {
            return false;
        }
        if (judgeTarget0 < 0) {
            judgeTarget0 = tp;
            judgeHull0 = hp;
            return false;
        }
        long age = now - watchedSince;
        if (age < JUDGE_NS) {
            return false;
        }
        double secs = age / 1e9;
        double killRate = (judgeTarget0 - tp) / secs;
        double lossRate = (judgeHull0 - hp) / secs;
        if (lossRate < 0.004) {
            return false; // hardly hurt: take the time it takes
        }
        double toRepair = (hp - c.safety().repairHpMin()) / lossRate;
        double toKill = killRate <= 0 ? Double.MAX_VALUE : tp / killRate;
        if (toKill <= toRepair * WIN_MARGIN) {
            return false;
        }
        // again too strong: twice as long each time, up to an hour (live 2026-11-13: Boss Kristallon tried every 10 min)
        int times = TOO_STRONG_TIMES.merge(t.name(), 1, Integer::sum);
        long skip = Math.min(TOO_STRONG_MAX_NS, TOO_STRONG_NS << Math.min(6, times - 1));
        TOO_STRONG.put(t.name(), now + skip);
        endReason = "не тяну";
        log.warn("{}: не тяну — добить за ~{} с, а на ремонт через ~{} с; таких пропускаю {} мин", t.name(),
                toKill == Double.MAX_VALUE ? "∞" : String.valueOf(Math.round(toKill)), Math.round(toRepair),
                skip / 60_000_000_000L);
        if (h.attackTargetId() == t.id()) {
            ctx.actions().stopAttack();
        }
        clear();
        return true;
    }

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
        kiteAngle = null;
        kiteTold = false;
        standAt = null;
        modeTold = null;
        watchedSince = ctx.now();
        judgeTarget0 = -1;
        judgeHull0 = -1;
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
        if (fight.active()) {
            fight.end("сменил цель", ctx.now());
        }
        fight.begin(s, h, ctx.now());
        endReason = "прервал (ремонт, бегство, пауза)";
    }

    public void clear() {
        if (fight.active()) {
            fight.end(endReason, ctx.now());
        }
        targetId = 0;
        shotAt = false;
        seenAt = 0;
    }

    /** A target out of sight is flown after this long before it is let go (unless it was being shot: then it died). */
    static final long LOST_GRACE_NS = 3_000_000_000L;
    /** A destroyed target this near the ship was ours, though the attack was not confirmed yet. */
    static final double KILL_NEAR = 800;

    /** The target's last position and time, and its speed from them (units per second, smoothed). */
    private double seenX;
    private double seenY;
    private long seenAt;
    private double vx;
    private double vy;

    /** How far ahead the chase aims: at least this (the client's reaction), more the farther the target is. */
    static final double LEAD_MIN_S = 0.4;
    static final double LEAD_MAX_S = 1.2;
    /** A fleeing target is followed to this share of the radius, so it does not slip out again at once. */
    static final double CHASE_INSIDE = 0.7;

    /** Learns the target's velocity from where it was last tick (the server says where it goes, not how fast). */
    private void track(WorldSnapshot.Ship t, long now) {
        if (seenAt != 0 && now > seenAt) {
            double dt = (now - seenAt) / 1e9;
            if (dt > 0.05 && dt < 2) {
                double nx = (t.x() - seenX) / dt;
                double ny = (t.y() - seenY) / dt;
                vx = vx * 0.5 + nx * 0.5;
                vy = vy * 0.5 + ny * 0.5;
                double sp = Math.hypot(vx, vy);
                if (t.moving() && sp > 0 && sp < 2_000 && t.name() != null) {
                    KIND_SPEED.merge(t.name(), sp, Math::max);
                }
            }
        }
        if (!t.moving()) {
            vx = 0;
            vy = 0;
        }
        seenX = t.x();
        seenY = t.y();
        seenAt = now;
    }

    /**
     * Where the target will be a moment from now (DarkBot's idea: its location some hundred milliseconds ahead), never
     * beyond where it flies to.
     */
    private double[] ahead(WorldSnapshot.Ship t, WorldSnapshot.Hero h, double radius) {
        if (!t.moving() || Math.hypot(vx, vy) < 1) {
            return new double[] {t.x(), t.y()};
        }
        double dist = t.distanceTo(h.x(), h.y());
        double lead = Math.min(LEAD_MAX_S, LEAD_MIN_S + Math.max(0, dist - radius) / Math.max(200, h.speed()));
        double px = t.x() + vx * lead;
        double py = t.y() + vy * lead;
        double toDest = Math.hypot(t.targetX() - t.x(), t.targetY() - t.y());
        if (Math.hypot(px - t.x(), py - t.y()) > toDest) {
            px = t.targetX();
            py = t.targetY();
        }
        return new double[] {px, py};
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
        if (t == null && !shotAt && w.destroyed().containsKey(targetId) && seenAt != 0
                && Math.hypot(h.x() - seenX, h.y() - seenY) <= KILL_NEAR) {
            // gone in the first volley before the server confirmed our attack (live 2026-11-13 on 2-3: Lordakia
            // dies in one or two volleys, the reward came, the fight was logged «пропала из вида»)
            shotAt = true;
        }
        if (t == null && !shotAt && seenAt != 0 && ctx.now() - seenAt < LOST_GRACE_NS) {
            // out of sight for a moment (the server shows ships near the ship only; live 2026-11-13 on 2-2: 181 of
            // 213 targets dropped at ~1 300, picked again a second later): on to where it was
            if (ctx.nav().idle() || Math.hypot(ctx.nav().destination().x() - seenX, ctx.nav().destination().y() - seenY) > 200) {
                ctx.nav().moveTo(seenX, seenY, false);
            }
            ctx.nav().tick();
            return true;
        }
        if (t == null) {
            if (shotAt) {
                kills++;
                ctx.stats().kills.incrementAndGet();
                if (t0Cubikon) {
                    ctx.stats().cubesKilled.incrementAndGet();
                }
                log.info("Цель уничтожена (всего {})", kills);
                endReason = "убит";
            } else {
                endReason = "пропала из вида";
            }
            clear();
            return false;
        }
        BotConfig c = ctx.config().get();
        long now = ctx.now();
        // another player's: greyed by the server, or someone else shot first — no reward for us (the user,
        // 2026-11-12: only the first shooter's group gets it, except cubes and group NPCs)
        if (Targets.taken(c, w, t) && (t.lockedByOther() || !shotAt)) {
            log.info("Цель {} чужая ({}) — бросаю", t.name(), t.lockedByOther() ? "её взял другой игрок" : "её уже бьют");
            endReason = "чужая";
            givenUp.put(t.id(), now + GIVE_UP_MS * 1_000_000);
            if (h.attackTargetId() == t.id()) {
                ctx.actions().stopAttack();
            }
            clear();
            return false;
        }
        double radius = Targets.radius(c, t, h.mapId());
        double dist = t.distanceTo(h.x(), h.y());
        BotConfig.NpcRule rule = Targets.rule(c, t, h.mapId());
        boolean follow = rule != null && rule.has(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW);
        track(t, now);
        boolean chasing = move && dist > radius + CHASE_GAP && (follow || c.general().runModeChase());
        boolean onTheWay = move && dist > radius + APPROACH && h.attackers().isEmpty();
        BotConfig.FightMove mode = mode(c, rule, t, h, now);
        // DarkBot's RUN_CONFIG_IN_CIRCLE: the run configuration to catch a nearly dead target that got away (below
        // 25 % and farther than twice the radius) — not for the fight (live 2026-11-13: fought in the speed
        // configuration, Impulse II lost 20 % in 25 s)
        boolean runCircle = c.loot().runCircle() && dist > 2 * radius && t.health().known()
                && t.health().hpPercent() < 0.25;
        ctx.ship().want(chasing || runCircle ? c.general().run() : onTheWay ? c.general().travel() : c.general().offensive());
        Threats threats = move ? new Threats(ctx, ctx.world().get(), c, t.id()) : null;
        fight.radius(radius, Targets.baseRadius(c, t, h.mapId()), !Targets.ownRadius(c, t, h.mapId()));
        fight.sample(w, t, radius, switch (mode) {
            case KITE -> "отход";
            case STAND -> "стою";
            default -> "кружу";
        }, now);
        boolean holding = mode == BotConfig.FightMove.KITE || mode == BotConfig.FightMove.STAND;
        if (move && dist > (holding ? radius * KITE_APPROACH : radius + 60) && now >= nextApproach) {
            // a fleeing target: aim where it will be and go well inside the radius, re-aiming often (live 2026-11-12:
            // Boss Kristallon ran, the ship stopped at the radius of where it had been and never fired); the
            // «aggressive follow» flag aims at where it flies; one that stays: come in from its safer side
            boolean fleeing = t.moving();
            double[] at = follow && fleeing ? new double[] {t.targetX(), t.targetY()} : ahead(t, h, radius);
            double keep = fleeing ? radius * CHASE_INSIDE : radius;
            double[] to = fleeing ? null : safeSide(threats, at, keep, h);
            if (to == null) {
                double d = Math.max(1, Math.hypot(at[0] - h.x(), at[1] - h.y()));
                double k = Math.max(0, (d - keep) / d);
                to = new double[] {h.x() + (at[0] - h.x()) * k, h.y() + (at[1] - h.y()) * k};
            }
            ctx.nav().moveTo(to[0], to[1], false);
            nextApproach = now + (fleeing ? ctx.pause(300, 500) : ctx.pause(700, 1_200));
        } else if (move && now >= nextApproach) {
            switch (mode) {
                case KITE -> {
                    // hold the radius, away from it as it comes (Blacklight NPCs chase; the user, 2026-11-12)
                    kite(threats, h, t, radius);
                    nextApproach = now + ctx.pause(300, 500);
                }
                case STAND -> {
                    stand(threats, h, t, radius);
                    nextApproach = now + ctx.pause(500, 900);
                }
                case CIRCLE -> {
                    circle(threats, h, t, radius);
                    nextApproach = now + ctx.pause(250, 400);
                }
                default -> {
                }
            }
        }
        if (move) {
            ctx.nav().tick();
        }
        if (h.attackTargetId() == targetId) {
            shotAt = true;
        }
        // close enough to shoot: it must lose something soon, whether the attack started or not (without
        // ammunition the game never starts it)
        if (cannotWin(c, t, h, now)) {
            return false;
        }
        if (dist <= radius + 200) {
            if (noDamage(t, now)) {
                return false;
            }
        } else {
            healthDropAt = now;
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
        // (the item bar comes a few seconds after the login: nothing is missing before it)
        if (ammo != null && !w.items().isEmpty() && (wanted == null || wanted.counter() < MIN_AMMO)
                && !ammo.equals(missingAmmo)) {
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
            launcher(c, w, t, dist, radius, now);
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

    /**
     * Within the radius: the next point a little further round the target, at the radius give or take a
     * few percent, always the same way round for one target.
     */
    /** Farther than this share of the radius the ship flies in as usual; nearer it holds the radius. */
    static final double KITE_APPROACH = 1.5;
    /** Backing off, the target is kept at this share of the radius. */
    static final double KITE_KEEP = 0.85;
    private Double kiteAngle;
    private boolean kiteTold;
    private double[] standAt;
    /** A ship's hull and shield together as a share of their maximum, -1 when unknown. */
    static final class FightShare {
        static double of(WorldSnapshot.Ship t) {
            if (!t.health().known()) {
                return -1;
            }
            double max = t.health().hpMax() + Math.max(0, t.health().shieldMax());
            return max <= 0 ? -1 : (t.health().hp() + Math.max(0, t.health().shield())) / max;
        }
    }

    /** The temporary record of the fight (FightLog). */
    private final FightLog fight = new FightLog();
    private String endReason = "прервал (ремонт, бегство, пауза)";
    /** The circle's side lately turned, and how long a turn for something only worse waits after it. */
    private long sideTurnedAt = Long.MIN_VALUE / 2;
    static final long SIDE_HOLD_NS = 3_000_000_000L;

    /** NPC kinds' top speed seen (units per second): {@link BotConfig.FightMove#AUTO} goes by it. */
    private static final java.util.Map<String, Double> KIND_SPEED = new java.util.concurrent.ConcurrentHashMap<>();
    /** A kind's speed is trusted after this long watching one, or once one was watched that long. */
    static final long SPEED_SURE_NS = 1_500_000_000L;
    private static final java.util.Set<String> KIND_SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** Watched this long without moving: a standing NPC (an invoke, the behemoth). */
    static final long STILL_NS = 4_000_000_000L;
    /** Slower than this share of the ship's speed: a slow NPC, to be kept at the radius. */
    static final double SLOW_SHARE = 0.6;
    private long watchedSince;
    /** The ship's top speed seen: its speed changes with the config (live 2026-11-13: 89 ↔ 444, the mode flipped). */
    private double heroTopSpeed;

    private double heroTop(WorldSnapshot.Hero h) {
        heroTopSpeed = Math.max(heroTopSpeed, h.speed());
        return Math.max(1, heroTopSpeed);
    }
    private BotConfig.FightMove modeTold;

    /**
     * How to move against this target: its own choice in the NPC list, else the config's; «auto» by its speed —
     * a Cubikon or a fast one circled, a slow one kept at the radius, a standing one stood by (docs/FIGHT-MOVEMENT.md
     * Д3; the user, 2026-11-13: the cubes as they are). {@code NO_CIRCLE} turns circling into standing.
     */
    private BotConfig.FightMove mode(BotConfig c, BotConfig.NpcRule rule, WorldSnapshot.Ship t, WorldSnapshot.Hero h,
                                     long now) {
        BotConfig.FightMove m = rule != null ? rule.fightMove(c.loot().fightMove()) : c.loot().fightMove();
        if (m == BotConfig.FightMove.AUTO) {
            double top = KIND_SPEED.getOrDefault(t.name(), 0.0);
            boolean known = now - watchedSince > SPEED_SURE_NS || top > 0 && KIND_SEEN.contains(t.name());
            if (CubikonModule.isCubikon(t) || !known) {
                m = BotConfig.FightMove.CIRCLE; // the first speeds seen are rough (live 2026-11-13: flipped in 0.1 s)
            } else if (top >= SLOW_SHARE * heroTop(h)) {
                m = BotConfig.FightMove.CIRCLE;
            } else if (top > 30) {
                m = BotConfig.FightMove.KITE;
            } else if (now - watchedSince > STILL_NS) {
                m = BotConfig.FightMove.STAND;
            } else {
                m = BotConfig.FightMove.CIRCLE; // not known yet
            }
        }
        if (now - watchedSince > SPEED_SURE_NS && t.name() != null) {
            KIND_SEEN.add(t.name());
        }
        if (m == BotConfig.FightMove.CIRCLE && rule != null && rule.has(BotConfig.NpcFlag.NO_CIRCLE)) {
            m = BotConfig.FightMove.STAND;
        }
        if (m != modeTold) {
            modeTold = m;
            log.info("{}: {}", t.name(), switch (m) {
                case KITE -> "держу дистанцию, отхожу (радиус " + (int) Targets.radius(c, t, h.mapId()) + ")";
                case STAND -> "стою на радиусе в безопасной точке";
                default -> "кружу";
            });
        }
        return m;
    }

    /**
     * The point to come in at: on the radius round the target, at most 60° off the straight way, where the threats
     * are least (docs Д4: not through the other NPCs' radii); {@code null} when nothing is better than straight.
     */
    private double[] safeSide(Threats th, double[] at, double keep, WorldSnapshot.Hero h) {
        if (th == null) {
            return null;
        }
        double base = Math.atan2(h.y() - at[1], h.x() - at[0]);
        double best = -Double.MAX_VALUE;
        double[] to = null;
        for (int i = -3; i <= 3; i++) {
            double a = base + Math.toRadians(20 * i);
            double x = at[0] + Math.cos(a) * keep;
            double y = at[1] + Math.sin(a) * keep;
            double sc = th.score(x, y) - Math.abs(i) * 60;
            if (sc > best) {
                best = sc;
                to = new double[] {x, y};
            }
        }
        return best <= Threats.BLOCKED ? null : to;
    }

    /**
     * One step of holding the radius: of 24 directions a little way off, the one that leaves the target nearest the
     * radius — straight away from it while it is too close — where the threats are least ({@link Threats}), turning as
     * little as may be. Nothing allowed: circling.
     */
    private void kite(Threats th, WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        if (!kiteTold) {
            log.info("Держу дистанцию от {} (радиус {})", t.name(), (int) radius);
            kiteTold = true;
        }
        double[] at = ahead(t, h, radius);
        double step = Math.max(350, Math.min(700, radius));
        // a little inside the radius: backing off at its very edge left Kristallon out of range a third of the fight
        // (live 2026-11-13 on 2-6: 69 s against 37 s circling on 2-7)
        double want = radius * KITE_KEEP;
        double now = Math.hypot(h.x() - at[0], h.y() - at[1]);
        double best = Double.MAX_VALUE;
        double[] to = null;
        double bestAngle = 0;
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            double x = h.x() + Math.cos(a) * step;
            double y = h.y() + Math.sin(a) * step;
            double safe = th.score(x, y);
            if (safe <= Threats.BLOCKED) {
                continue;
            }
            double d = Math.hypot(x - at[0], y - at[1]);
            // too close: as far from it as the step goes; at the radius: along it (no closer, no farther)
            double cost = (now < want ? 2 * want - d : Math.abs(d - want)) - safe;
            if (kiteAngle != null) {
                cost += Math.abs(Math.atan2(Math.sin(a - kiteAngle), Math.cos(a - kiteAngle))) * 120;
            }
            if (cost < best) {
                best = cost;
                to = new double[] {x, y};
                bestAngle = a;
            }
        }
        if (to == null) {
            circle(th, h, t, radius);
            return;
        }
        kiteAngle = bestAngle;
        ctx.nav().moveTo(to[0], to[1], false);
    }

    /**
     * Standing by a still NPC (an invoke, the behemoth): of 24 points on 0.9 of the radius round it, the safest near
     * the ship; kept until a threat comes to it (a better one by 300, or it is no longer allowed).
     */
    private void stand(Threats th, WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        double best = -Double.MAX_VALUE;
        double[] to = null;
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            double x = t.x() + Math.cos(a) * radius * 0.9;
            double y = t.y() + Math.sin(a) * radius * 0.9;
            double sc = th.score(x, y) - 0.2 * Math.hypot(x - h.x(), y - h.y());
            if (sc > best) {
                best = sc;
                to = new double[] {x, y};
            }
        }
        if (to == null || best <= Threats.BLOCKED) {
            return;
        }
        if (standAt != null && Math.hypot(standAt[0] - t.x(), standAt[1] - t.y()) <= radius) {
            double keep = th.score(standAt[0], standAt[1]) - 0.2 * Math.hypot(standAt[0] - h.x(), standAt[1] - h.y());
            if (keep > Threats.BLOCKED && keep >= best - 300) {
                to = standAt;
            }
        }
        standAt = to;
        if (Math.hypot(to[0] - h.x(), to[1] - h.y()) > 120) {
            ctx.nav().moveTo(to[0], to[1], false);
        }
    }

    /**
     * DarkBot's circling with the threats: round where the target will be, the angle step from both ships'
     * speeds, the radius reached gradually, the side chosen by the 5 points ahead each way (switched when one way is
     * blocked and the other not, or better by 300), a blocked point searched round (docs Д1).
     */
    /**
     * The target moves away from the ship (not round it, not towards it): only then is the circle drawn in (live
     * 2026-11-13: Boss Kristallon fought at 520 of its 615, hit the ship half the fight).
     */
    private boolean fleeing(WorldSnapshot.Ship t, WorldSnapshot.Hero h) {
        if (!t.moving()) {
            return false;
        }
        double dx = t.x() - h.x();
        double dy = t.y() - h.y();
        double d = Math.max(1, Math.hypot(dx, dy));
        double away = (vx * dx + vy * dy) / d; // its speed along the line from the ship to it
        return away > 0.3 * Math.max(1, Math.hypot(vx, vy));
    }

    private void circle(Threats th, WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        if (circleDir == 0) {
            circleDir = ctx.random().nextBoolean() ? 1 : -1;
            log.info("Кружу вокруг {} ({}, радиус {})", t.name(), circleDir > 0 ? "по часовой" : "против часовой",
                    (int) radius);
        }
        // where it will be: DarkBot looks 250 ms ahead; a target on the run needs the longer lead of the chase
        // (live 2026-11-12: Boss Kristallon slipped out of range)
        double[] c0 = ahead(t, h, radius);
        double cx = c0[0];
        double cy = c0[1];
        double r = radius;
        if (!t.moving() || t.health().known() && t.health().hpPercent() < 0.25) {
            r = Math.min(r, 600);
        }
        if (fleeing(t, h)) {
            r *= 0.8; // one going away does not leave the radius while the ship goes round
        }
        double dist = Math.hypot(h.x() - cx, h.y() - cy);
        double angle = Math.atan2(h.y() - cy, h.x() - cx);
        double rr = Math.max(100, dist + Math.max(-r / 2, Math.min(r / 2, r - dist)));
        double gap = Math.hypot(cx + Math.cos(angle) * rr - h.x(), cy + Math.sin(angle) * rr - h.y());
        double npcSpeed = Math.max(200, Math.hypot(vx, vy));
        double step = Math.max(0.05, Math.max(0, 0.625 * Math.max(1, h.speed()) + 0.625 * npcSpeed - gap) / rr);
        if (th != null) {
            double ahead = 0;
            double back = 0;
            for (int k = 1; k <= 5; k++) {
                ahead += th.score(cx + Math.cos(angle + circleDir * k * step) * rr, cy + Math.sin(angle + circleDir * k * step) * rr);
                back += th.score(cx + Math.cos(angle - circleDir * k * step) * rr, cy + Math.sin(angle - circleDir * k * step) * rr);
            }
            boolean aheadBlocked = ahead <= Threats.BLOCKED;
            boolean backBlocked = back <= Threats.BLOCKED;
            // a turn for something only worse: not again before SIDE_HOLD_NS (live 2026-11-13: it turned every 2 s,
            // to and fro); a way that is not allowed turns it at once
            long now = ctx.now();
            boolean better = !backBlocked && back - ahead > 300 && now - sideTurnedAt > SIDE_HOLD_NS;
            if (aheadBlocked && !backBlocked || better) {
                circleDir = -circleDir;
                sideTurnedAt = now;
                fight.turned();
                log.info("Кружу в другую сторону: там {}", aheadBlocked ? "нельзя" : "другие NPC или враги");
            }
        }
        double a = angle + circleDir * step;
        double[] to = {cx + Math.cos(a) * rr, cy + Math.sin(a) * rr};
        if (th != null) {
            double[] ok = th.valid(cx, cy, to[0], to[1], circleDir);
            to = ok != null ? ok : new double[] {cx + Math.cos(angle) * 500, cy + Math.sin(angle) * 500};
        }
        ctx.nav().moveTo(to[0], to[1], false);
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

    /** The launcher's own button; its counter is the load (0…5 on the HST, live 2026-11-11). */
    static final String LAUNCHER_PREFIX = "equipment_weapon_rocketlauncher_";
    /** The CPU that fires the launcher by itself when it is loaded (no request from the client). */
    static final String LAUNCHER_CPU = "equipment_extra_cpu_rllb-x";
    static final int LAUNCHER_LOAD = 5;
    private String missingLauncher;
    private long launcherSelectAt = Long.MIN_VALUE / 2;
    private long launcherPressAt;
    private long launcherFiredAt = Long.MIN_VALUE / 2;
    private long launcherFireAt;
    private boolean launcherCpuTold;

    /**
     * Keeps the configured launcher rockets selected (the launcher CPU fires them by itself when loaded, as
     * recorded on 2026-11-11: a salvo with no request from the client); with {@code fireLauncher} and no such CPU
     * on, also fires the launcher when it is fully loaded.
     */
    private void launcher(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t, double dist, double radius, long now) {
        String wanted = launcherRocket(c, w, t);
        if (wanted == null || BotConfig.Loot.NO_ROCKETS.equals(wanted)) {
            return;
        }
        WorldSnapshot.Item launcher = w.items().values().stream().filter(i -> i.id().startsWith(LAUNCHER_PREFIX))
                .findFirst().orElse(null);
        if (launcher == null || !launcher.available()) {
            return; // no launcher on this ship
        }
        WorldSnapshot.Item item = w.items().get(wanted);
        if (item == null || item.counter() <= 0) {
            if (!wanted.equals(missingLauncher)) {
                missingLauncher = wanted;
                log.warn("Нет ракет для ракетомёта {} ({})", orbit.config.ItemCatalog.label(wanted),
                        item == null ? "нет в инвентаре" : "0 шт.");
            }
            return;
        }
        if (!item.selected()) {
            if (now - launcherSelectAt > 2_000_000_000L) {
                if (launcherPressAt == 0) {
                    launcherPressAt = now + ctx.pause(300, 900);
                } else if (now >= launcherPressAt) {
                    log.info("Ракетомёт: {}", orbit.config.ItemCatalog.label(wanted));
                    missingLauncher = null;
                    ctx.actions().useItem(wanted);
                    launcherSelectAt = now;
                    launcherPressAt = 0;
                }
            }
            return;
        }
        if (!c.loot().fireLauncher()) {
            return;
        }
        WorldSnapshot.Item cpu = w.items().get(LAUNCHER_CPU);
        if (cpu != null && cpu.selected()) {
            if (!launcherCpuTold) {
                launcherCpuTold = true;
                log.info("Ракетомёт стреляет CPU сам — залп не жму");
            }
            return;
        }
        int full = launcher.maxCounter() > 0 ? (int) launcher.maxCounter() : LAUNCHER_LOAD;
        if (launcher.counter() >= full && launcher.remainingMs(now) == 0 && dist <= radius + ROCKET_EXTRA
                && now - launcherFiredAt > 2_000_000_000L) {
            if (launcherFireAt == 0) {
                launcherFireAt = now + ctx.pause(200, 800);
            } else if (now >= launcherFireAt) {
                ctx.actions().useItem(launcher.id());
                launcherFiredAt = now;
                launcherFireAt = 0;
            }
        }
    }

    /** The NPC's launcher rockets, else the config's ({@code null} = leave the game's choice). */
    static String launcherRocket(BotConfig c, WorldSnapshot w, WorldSnapshot.Ship t) {
        BotConfig.NpcRule r = Targets.rule(c, t, w.hero().mapId());
        return r != null && r.launcher() != null ? r.launcher() : c.loot().launcher();
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
            for (double least : new double[] {MIN_AMMO, 1}) { // a good stock first, then whatever is left
                for (orbit.config.ItemCatalog.Item l : orbit.config.ItemCatalog.LASERS) {
                    WorldSnapshot.Item li = w.items().get(l.id());
                    if (li != null && li.counter() >= least && !l.id().contains("sab")) {
                        return l.id();
                    }
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
        endReason = "не теряет HP";
        log.warn("{} не теряет HP {} с (патроны {}) — бросаю цель", t.name(), NO_DAMAGE_MS / 1000,
                laser == null ? "?" : orbit.config.ItemCatalog.label(laser.id()) + " ×" + (long) laser.counter());
        givenUp.put(t.id(), now + GIVE_UP_MS * 1_000_000);
        ctx.nav().stop();
        clear();
        return true;
    }
}
