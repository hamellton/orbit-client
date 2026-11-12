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
        seenAt = 0;
    }

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
        // another player's: greyed by the server, or someone else shot first — no reward for us (the user,
        // 2026-11-12: only the first shooter's group gets it, except cubes and group NPCs)
        if (Targets.taken(c, w, t) && (t.lockedByOther() || !shotAt)) {
            log.info("Цель {} чужая ({}) — бросаю", t.name(), t.lockedByOther() ? "её взял другой игрок" : "её уже бьют");
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
        ctx.ship().want(chasing ? c.general().run() : onTheWay ? c.general().travel() : c.general().offensive());
        boolean kite = c.loot().kite() && (rule == null || !rule.has(BotConfig.NpcFlag.NO_CIRCLE));
        if (move && kite && dist <= radius * KITE_APPROACH && now >= nextApproach) {
            // hold the radius, away from it as it comes (Blacklight NPCs chase; the user, 2026-11-12)
            kite(c, ctx.world().get(), h, t, radius);
            nextApproach = now + ctx.pause(300, 500);
        } else if (move && dist > radius + 60 && now >= nextApproach) {
            // a fleeing target: aim where it will be and go well inside the radius, re-aiming often (live 2026-11-12:
            // Boss Kristallon ran, the ship stopped at the radius of where it had been and never fired); the
            // «aggressive follow» flag aims at where it flies
            boolean fleeing = t.moving();
            double[] at = follow && fleeing ? new double[] {t.targetX(), t.targetY()} : ahead(t, h, radius);
            double keep = fleeing ? radius * CHASE_INSIDE : radius;
            double d = Math.max(1, Math.hypot(at[0] - h.x(), at[1] - h.y()));
            double k = Math.max(0, (d - keep) / d);
            ctx.nav().moveTo(h.x() + (at[0] - h.x()) * k, h.y() + (at[1] - h.y()) * k, false);
            nextApproach = now + (fleeing ? ctx.pause(300, 500) : ctx.pause(700, 1_200));
        } else if (move && c.loot().circle() && (rule == null || !rule.has(BotConfig.NpcFlag.NO_CIRCLE))
                && now >= nextApproach) {
            circle(h, t, radius);
            nextApproach = now + (t.moving() ? ctx.pause(400, 700) : ctx.pause(900, 1_500));
        }
        if (move) {
            ctx.nav().tick();
        }
        if (h.attackTargetId() == targetId) {
            shotAt = true;
        }
        // close enough to shoot: it must lose something soon, whether the attack started or not (without
        // ammunition the game never starts it)
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
    static final double CIRCLE_STEP = Math.toRadians(35);

    /**
     * Within the radius: the next point a little further round the target, at the radius give or take a
     * few percent, always the same way round for one target.
     */
    /** Farther than this share of the radius the ship flies in as usual; nearer it holds the radius. */
    static final double KITE_APPROACH = 1.5;
    /** The ship keeps this far from the map's edge while backing off. */
    static final double KITE_MARGIN = 500;
    private Double kiteAngle;
    private boolean kiteTold;

    /**
     * One step of holding the radius: of 24 directions a little way off, the one that leaves the target nearest the
     * radius — never closer when it is already too close, inside the preferred zone, out of the avoided zone, clear
     * of the danger circles (offenders, clan stations), away from other NPCs, and turning as little as may be.
     * Nothing allowed: circling as before.
     */
    private void kite(BotConfig c, WorldSnapshot w, WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        if (!kiteTold) {
            log.info("Держу дистанцию от {} (радиус {})", t.name(), (int) radius);
            kiteTold = true;
        }
        double[] at = ahead(t, h, radius);
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(h.mapId());
        BotConfig.Zones z = c.zones().get(h.mapId());
        BotConfig.ZoneGrid pref = z == null || z.preferred() == null || z.preferred().isEmpty() ? null : z.preferred();
        BotConfig.ZoneGrid avoid = z == null || z.avoided() == null || z.avoided().isEmpty() ? null : z.avoided();
        double step = Math.max(350, Math.min(700, radius));
        double want = radius * 0.95;
        double now = Math.hypot(h.x() - at[0], h.y() - at[1]);
        double best = Double.MAX_VALUE;
        double[] to = null;
        double bestAngle = 0;
        for (int i = 0; i < 24; i++) {
            double a = i * Math.PI / 12;
            double x = h.x() + Math.cos(a) * step;
            double y = h.y() + Math.sin(a) * step;
            if (x < KITE_MARGIN || y < KITE_MARGIN || x > m.width() - KITE_MARGIN || y > m.height() - KITE_MARGIN
                    || avoid != null && avoid.contains(x, y, m.width(), m.height()) || ctx.nav().inDanger(x, y)) {
                continue;
            }
            double d = Math.hypot(x - at[0], y - at[1]);
            // too close: as far from it as the step goes; at the radius: along it (no closer, no farther)
            double cost = now < want ? 2 * want - d : Math.abs(d - want);
            if (pref != null && !pref.contains(x, y, m.width(), m.height())) {
                cost += 1_500;
            }
            for (WorldSnapshot.Ship o : w.ships().values()) {
                if (o.kind() == WorldSnapshot.ShipKind.NPC && o.id() != t.id() && Math.hypot(o.x() - x, o.y() - y) < 900) {
                    cost += 500;
                }
            }
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
            circle(h, t, radius);
            return;
        }
        kiteAngle = bestAngle;
        ctx.nav().moveTo(to[0], to[1], false);
    }

    private void circle(WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        if (circleDir == 0) {
            circleDir = ctx.random().nextBoolean() ? 1 : -1;
            log.info("Кружу вокруг {} ({}, радиус {})", t.name(), circleDir > 0 ? "по часовой" : "против часовой",
                    (int) radius);
        }
        // round where it will be: a moving target is not left behind while circling
        double[] c = ahead(t, h, radius);
        double angle = Math.atan2(h.y() - c[1], h.x() - c[0])
                + circleDir * CIRCLE_STEP * (0.8 + 0.4 * ctx.random().nextDouble());
        double r = radius * (t.moving() ? 0.8 : 0.92 + 0.1 * ctx.random().nextDouble());
        ctx.nav().moveTo(c[0] + Math.cos(angle) * r, c[1] + Math.sin(angle) * r, false);
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
        log.warn("{} не теряет HP {} с (патроны {}) — бросаю цель", t.name(), NO_DAMAGE_MS / 1000,
                laser == null ? "?" : orbit.config.ItemCatalog.label(laser.id()) + " ×" + (long) laser.counter());
        givenUp.put(t.id(), now + GIVE_UP_MS * 1_000_000);
        ctx.nav().stop();
        clear();
        return true;
    }
}
