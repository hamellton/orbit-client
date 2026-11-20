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
    /** How many of the wanted ammunition there were at the last press: fewer since means it fired. */
    private double ammoCountAtPress = -1;
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
    static final long JUDGE_NS = 30_000_000_000L;
    /** Kinds killed this run: none of them is «too strong», whatever a moment's judging says. */
    private static final java.util.Set<String> KILLED_KINDS = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private String targetName;
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
        // a kind killed this run is no «too strong» (live 2026-11-13 on 3-BL: Attend IX killed twice, then judged
        // too strong in 15 s while Impulse II hit too)
        if (KILLED_KINDS.contains(t.name())) {
            return false;
        }
        // too strong for the hull left, not for the ship: with a full hull a whole fight would be won — fight on, the
        // repair comes at its threshold, the kind is not skipped (live 2026-11-13 on 3-BL: Attend IX begun at 68 %,
        // judged too strong, the only kind of the config skipped 10 min; one begun at 100 % was killed)
        double fromFull = (1 - c.safety().repairHpMin()) / lossRate;
        if (killRate > 0 && 1 / killRate <= fromFull) {
            return false;
        }
        // the only kind the config fights on this map: skipping it only idles the ship (live 2026-11-13: Attend IX
        // judged in 27 s while Impulse II hit too; the one before was killed in 76 s losing 32 %)
        if (onlyKind(c, t, h.mapId())) {
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

    /** No other kind of the NPC list is fought on the map. */
    private static boolean onlyKind(BotConfig c, WorldSnapshot.Ship t, int map) {
        return c.npcs().entrySet().stream().noneMatch(e -> e.getValue().kill() && e.getValue().appliesOn(map)
                && !e.getKey().equals(t.name()));
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

    /**
     * The galaxy gate's way (SharedPlugin's CustomLootModule): always DarkBot's circle (no kiting, standing or leading
     * off), roam beyond {@link #GATE_ROAM}, the circle's way fixed toward the map's centre ({@code approachToCenter});
     * {@code null} the usual way.
     */
    public void gateStyle(double[] centre) {
        gateStyle(centre, CENTER_ON);
    }

    /** With the gate's tolerance (SharedPlugin's toleranceDistance: 4 000; EBG's Uber Kristallon wave 1 500). */
    public void gateStyle(double[] centre, double tolerance) {
        this.gate = centre;
        this.centerOn = tolerance;
    }

    private double centerOn = CENTER_ON;

    /** For the next {@link #tick}: the repair's circle (SharedPlugin: round the target at 2 000 in this mode). */
    public void repairCircle(BotConfig.ShipMode mode) {
        this.repairMode = mode;
    }

    /** SharedPlugin's setConfig: farther from the target than this — the roam configuration. */
    static final double GATE_ROAM = 2_000;
    /** SharedPlugin's repairRadius. */
    static final double GATE_REPAIR_RADIUS = 2_000;
    /** approachToCenter: on when the ship and the target are both this far from the centre, off below it − 800. */
    static final double CENTER_ON = 4_000;
    static final double CENTER_OFF = 3_200;
    private double[] gate;
    private BotConfig.ShipMode repairMode;
    private boolean towardCentre;

    public int targetId() {
        return targetId;
    }

    /** How long the current target has been the target (ms). */
    public long targetAgeMs() {
        return targetId == 0 ? 0 : (ctx.now() - watchedSince) / 1_000_000;
    }

    /** The current target has been shot at (the attack ran). */
    public boolean shooting() {
        return shotAt;
    }

    public int kills() {
        return kills;
    }

    /** The last NPC killed: where, when, whether its boxes are left ({@code IGNORE_BOXES}). */
    /**
     * The ship's mode for this tick while it goes for a box in the fight (DarkBot's {@code setConfig(box)}: the roam mode
     * for a box over three radii away), {@code null} the fight's own.
     */
    private BotConfig.ShipMode boxShipMode;

    public void boxShipMode(BotConfig.ShipMode mode) {
        boxShipMode = mode;
    }

    public record Kill(String name, double x, double y, long nanos, boolean leaveBoxes) {
    }

    private volatile Kill lastKill;
    /** The list's rule of the target now ({@code null} when none). */
    private volatile BotConfig.NpcRule targetRule;
    private int empUses;
    private int ishUses;
    private long empAt = Long.MIN_VALUE / 2;
    private long ishAt = Long.MIN_VALUE / 2;
    /** The limit last told for each item (once until it changes). */
    private final java.util.Map<String, String> limitTold = new java.util.HashMap<>();
    static final String EMP = "ammunition_specialammo_emp-01";
    static final String ISH = "equipment_extra_cpu_ish-01";
    /** The Energy Leech tech (client's item data). */
    static final String LEECH = "tech_energy-leech";
    /**
     * Ship abilities that help in a fight with NPCs, by the ids the server sends in the item bar (sniffer 2026-11):
     * damage, defence, repair. Not here: travel, cloak, minimap, draw-fire, hologram and alike — no help or harm solo.
     */
    static final java.util.List<String> FIGHT_ABILITIES = java.util.List.of("ability_venom", "ability_diminisher",
            "ability_sentinel", "ability_spectrum", "ability_spectrum-plus_prismatic-reflecting", "ability_lightning",
            "ability_solace", "ability_solace-plus_nano-cluster-repairer-plus", "ability_aegis_hp-repair",
            "ability_aegis_shield-repair", "ability_tartarus_rapid-fire", "ability_tartarus-plus_rapid-fire-plus",
            "ability_hecate_particle-beam", "ability_hecate-plus_particle-beam-plus", "ability_retiarus_supercharge",
            "ability_solaris_incinerate", "ability_solaris-plus_incinerate-plus", "ability_zephyr_triple-barrage",
            "ability_berserker_berserk", "ability_citadel_fortify", "ability_liberator-plus_self-repair",
            "ability_tempest_volt-discharge", "ability_basilisk_heightened-valour", "ability_keres_spread",
            "ability_goliath-x_frozen-claw", "ability_paladin_ripper");
    /** Prismatic shielding (Spectrum, Spectrum+, Citadel+) — AstraBot «usePrismatic». */
    static final java.util.List<String> PRISMATIC = java.util.List.of("ability_spectrum",
            "ability_spectrum-plus_prismatic-reflecting", "ability_citadel-plus_prismatic-endurance");
    /** Spearhead's JAM-X (and the Spearhead+'s) — AstraBot «useSpearheadJamx». */
    static final java.util.List<String> JAMX = java.util.List.of("ability_spearhead_jam-x",
            "ability_spearhead-plus_jamx-creed");
    /** An ability or tech asked for: not again this soon (the game's cooldown comes back a moment later). */
    static final long ITEM_ASK_NS = 3_000_000_000L;
    private final java.util.Map<String, Long> askedItem = new java.util.HashMap<>();

    public Kill lastKill() {
        return lastKill;
    }

    /** True while the target is one whose rule has the flag. */
    public boolean fighting(BotConfig.NpcFlag flag) {
        BotConfig.NpcRule r = targetRule;
        return targetId != 0 && r != null && r.has(flag);
    }

    /**
     * A module's own use of EMP-01 and ISH-01 over the NPC list's flags (the behemoth module: AstraBot's useEmp /
     * empPercent, useIsh / ishPercent): each below its hull fraction ({@code -1} = EMP at once, ISH never).
     */
    public record AbilityPlan(boolean emp, double empBelow, boolean ish, double ishBelow, double prismaticBelow,
                              double jamxWithin, int threatPlayers, boolean off, boolean prismaticAtStart,
                              long empAfterIshMs, boolean empOnlyPlayers, boolean abilityAtStart,
                              boolean empThenIsh) {

        public AbilityPlan(boolean emp, double empBelow, boolean ish, double ishBelow, double prismaticBelow,
                           double jamxWithin, int threatPlayers, boolean off, boolean prismaticAtStart,
                           long empAfterIshMs, boolean empOnlyPlayers) {
            this(emp, empBelow, ish, ishBelow, prismaticBelow, jamxWithin, threatPlayers, off, prismaticAtStart,
                    empAfterIshMs, empOnlyPlayers, false, false);
        }

        public AbilityPlan(boolean emp, double empBelow, boolean ish, double ishBelow, double prismaticBelow,
                           double jamxWithin, int threatPlayers, boolean off, boolean prismaticAtStart,
                           long empAfterIshMs) {
            this(emp, empBelow, ish, ishBelow, prismaticBelow, jamxWithin, threatPlayers, off, prismaticAtStart,
                    empAfterIshMs, true);
        }

        public AbilityPlan(boolean emp, double empBelow, boolean ish, double ishBelow) {
            this(emp, empBelow, ish, ishBelow, -1, -1, 0, false, false, ISH_HOLDS_NS / 1_000_000);
        }

        public AbilityPlan(boolean emp, double empBelow, boolean ish, double ishBelow, double prismaticBelow,
                           double jamxWithin) {
            this(emp, empBelow, ish, ishBelow, prismaticBelow, jamxWithin, 0, false, false, ISH_HOLDS_NS / 1_000_000);
        }

        public AbilityPlan(boolean emp, double empBelow, boolean ish, double ishBelow, double prismaticBelow,
                           double jamxWithin, int threatPlayers, boolean off) {
            this(emp, empBelow, ish, ishBelow, prismaticBelow, jamxWithin, threatPlayers, off, false,
                    ISH_HOLDS_NS / 1_000_000);
        }

        /** Nothing at all — not the NPC list's flags either (the behemoth module off its behemoth). */
        public static final AbilityPlan OFF = new AbilityPlan(false, -1, false, -1, -1, -1, 0, true);
    }

    /** ISH-01's shield lasts about this: EMP-01 not while it holds (never both at once). */
    static final long ISH_HOLDS_NS = 3_000_000_000L;
    private long abilityAt = Long.MIN_VALUE / 2;

    private AbilityPlan plan;

    /** {@code null}: the NPC list's flags again. */
    public void setAbilityPlan(AbilityPlan p) {
        plan = p;
    }

    /**
     * The NPC list's abilities (AstraBot's «Use EMP», «Use ISH»): EMP-01 once a fight when in range and shooting, ISH-01
     * while the hull is below the threshold; only when the game shows the item ready.
     */
    private void abilities(BotConfig c, BotConfig.NpcRule rule, WorldSnapshot w, WorldSnapshot.Hero h, double dist,
                           double radius, long now) {
        if (rule == null && plan == null || plan != null && plan.off()) {
            return;
        }
        // the behemoth's staged defence holds on the way in too: the train shoots from 1 600 (live 2026-11-14 on the VPS,
        // 3-BL: ISH at 11 %, EMP at 1 % — nothing while out of reach)
        boolean staged = plan != null && plan.threatPlayers() > 0;
        if (plan == null && rule != null && rule.has(BotConfig.NpcFlag.RUSH)) {
            rush(c, w, h, dist, now);
        }
        if (!staged && (dist > radius + 100 || !shotAt)) {
            return;
        }
        BotConfig.FightOptions fo = c.loot().fight();
        double hp = h.health().hpPercent();
        if (plan != null && plan.threatPlayers() > 0) {
            if (rule != null && rule.has(BotConfig.NpcFlag.USE_LEECH)) {
                useFirstReady(w, java.util.List.of(LEECH), now, "Энергетическая пиявка");
            }
            staged(w, h, hp, now);
            return;
        }
        boolean emp = plan != null ? plan.emp() && (plan.empBelow() < 0 || hp >= 0 && hp < plan.empBelow())
                : rule.has(BotConfig.NpcFlag.USE_EMP);
        double ishBelow = plan != null ? (plan.ish() ? plan.ishBelow() : -1)
                : rule.has(BotConfig.NpcFlag.USE_ISH) ? fo.ishBelow() : -1;
        if (emp && may(w, h, EMP, fo.empSpend(), empUses, empAt, now)) {
            log.info("EMP-01 по {} (за сутки {})", w.ships().get(targetId) != null ? w.ships().get(targetId).name() : "цели",
                    AbilityUse.today(h.id(), EMP, System.currentTimeMillis()) + 1);
            ctx.actions().useItem(EMP);
            pressed(w, EMP, now);
            empUses++;
            empAt = now;
        }
        if (ishBelow > 0 && hp >= 0 && hp < ishBelow
                && may(w, h, ISH, fo.ishSpend(), ishUses, ishAt, now)) {
            log.info("ISH-01: HP {}% (за сутки {})", Math.round(hp * 100),
                    AbilityUse.today(h.id(), ISH, System.currentTimeMillis()) + 1);
            ctx.actions().useItem(ISH);
            pressed(w, ISH, now);
            ishUses++;
            ishAt = now;
        }
        if (rule != null && rule.has(BotConfig.NpcFlag.USE_LEECH)) {
            useFirstReady(w, java.util.List.of(LEECH), now, "Энергетическая пиявка");
        }
        if (rule != null && rule.has(BotConfig.NpcFlag.USE_ABILITY)) {
            useFirstReady(w, FIGHT_ABILITIES, now, "способность корабля");
        }
        if (plan != null && plan.prismaticBelow() > 0 && hp >= 0 && hp < plan.prismaticBelow()) {
            useFirstReady(w, PRISMATIC, now, "призматический щит, HP " + Math.round(hp * 100) + "%");
        }
        if (plan != null && plan.jamxWithin() > 0 && dist <= plan.jamxWithin()) {
            useFirstReady(w, JAMX, now, "JAM-X");
        }
    }

    /**
     * A target flagged {@code RUSH} (push through with EMP and ISH, as at the behemoth):
     * EMP-01 once it is nearer than {@code modules.kill.rushEmpWithin} and an enemy player is about (shooting the ship or
     * within {@link #RUSH_ENEMY_NEAR}) — their locks gone on the way in; ISH-01 when the hull falls fast ({@link
     * #RUSH_FAST_LOSS} within {@link #RUSH_LOSS_NS}) or is below the config's ISH threshold under players' fire. Each only
     * when ready, within the config's spend limits; not both at once.
     */
    private void rush(BotConfig c, WorldSnapshot w, WorldSnapshot.Hero h, double dist, long now) {
        BotConfig.FightOptions fo = c.loot().fight();
        double hp = h.health().hpPercent();
        if (hp >= 0) {
            hpSeen.addLast(new double[] {now, hp});
            while (hpSeen.size() > 1 && now - hpSeen.peekFirst()[0] > RUSH_LOSS_NS) {
                hpSeen.removeFirst();
            }
        }
        int shooting = 0;
        boolean near = false;
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.kind() != WorldSnapshot.ShipKind.PLAYER || !orbit.bot.run.Flee.enemy(s, h) && !h.attackers().contains(s.id())) {
                continue;
            }
            if (h.attackers().contains(s.id())) {
                shooting++;
            }
            if (Math.hypot(s.x() - h.x(), s.y() - h.y()) <= RUSH_ENEMY_NEAR) {
                near = true;
            }
        }
        int within = c.moduleSettings("kill").path("rushEmpWithin").asInt(BotConfig.DEFAULT_RUSH_EMP_WITHIN);
        if ((shooting > 0 || near) && dist <= within && now - ishAt > ISH_HOLDS_NS
                && may(w, h, EMP, fo.empSpend(), empUses, empAt, now)) {
            log.info("Напролом: EMP-01 — до цели {}, врагов рядом{} (за сутки {})", (int) dist,
                    shooting > 0 ? ", стреляют " + shooting : "",
                    AbilityUse.today(h.id(), EMP, System.currentTimeMillis()) + 1);
            ctx.actions().useItem(EMP);
            pressed(w, EMP, now);
            empUses++;
            empAt = now;
            return;
        }
        double lost = hpSeen.isEmpty() || hp < 0 ? 0 : hpSeen.peekFirst()[1] - hp;
        boolean fast = lost >= RUSH_FAST_LOSS;
        boolean low = shooting > 0 && hp >= 0 && hp < fo.ishBelow();
        if ((fast || low) && now - empAt > EMP_THEN_ISH_NS && may(w, h, ISH, fo.ishSpend(), ishUses, ishAt, now)) {
            log.info("Напролом: ISH-01 — HP {}%{} (за сутки {})", Math.round(hp * 100),
                    fast ? ", теряю " + Math.round(lost * 100) + "% за " + RUSH_LOSS_NS / 1_000_000_000L + " с" : "",
                    AbilityUse.today(h.id(), ISH, System.currentTimeMillis()) + 1);
            ctx.actions().useItem(ISH);
            pressed(w, ISH, now);
            ishUses++;
            ishAt = now;
        }
    }

    /** A rush's EMP-01 counts enemy players this near the ship even when they do not shoot yet. */
    static final double RUSH_ENEMY_NEAR = 1_500;
    /** A rush's ISH-01: this much of the hull lost within {@link #RUSH_LOSS_NS}. */
    static final double RUSH_FAST_LOSS = 0.15;
    static final long RUSH_LOSS_NS = 2_000_000_000L;
    private final java.util.ArrayDeque<double[]> hpSeen = new java.util.ArrayDeque<>();

    /**
     * The behemoth's fight under a train (the ability, then ISH lower on HP, then EMP — never at once, and only while
     * the behemoth is fought): the ship's abilities (prismatic first) once {@code threatPlayers}
     * players shoot it or the hull is below the prismatic threshold; then ISH-01 below its threshold; then EMP-01 below
     * its threshold and not while the ISH shield holds — one a tick, none at once.
     */
    private void staged(WorldSnapshot w, WorldSnapshot.Hero h, double hp, long now) {
        int players = 0;
        for (int id : h.attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.kind() == WorldSnapshot.ShipKind.PLAYER) {
                players++;
            }
        }
        boolean threat = players >= plan.threatPlayers();
        // CARNAGE's behemoth (BeheSolo: the ship's ability by the NPC's flag, EblExtraFlags' offensive EMP and ISH):
        // the ability on arrival at the behemoth, EMP once other ships (not NPCs) start shooting, ISH right after it
        // the prismatic shield only: Citadel's Fortify roots the ship, used 1 700 out it stood 11 s short of the behemoth
        // (live 2026-11-14 on the VPS, 2-BL 13:57; Citadel+ is to use the prismatic shield)
        if (plan.abilityAtStart() && useFirstReady(w, PRISMATIC, now, "призматический щит (подлетел к бегемоту)")) {
            abilityAt = now;
            return;
        }
        if (plan.empThenIsh() && players > 0) {
            BotConfig.FightOptions fo = ctx.config().get().loot().fight();
            if (plan.emp() && may(w, h, EMP, fo.empSpend(), empUses, empAt, now)) {
                log.info("EMP-01: стреляют {} игроков, HP {}% — следом стенка (за сутки {})", players,
                        Math.round(hp * 100), AbilityUse.today(h.id(), EMP, System.currentTimeMillis()) + 1);
                ctx.actions().useItem(EMP);
                pressed(w, EMP, now);
                empUses++;
                empAt = now;
                return;
            }
            if (plan.ish() && now - empAt < EMP_THEN_ISH_NS && may(w, h, ISH, fo.ishSpend(), ishUses, ishAt, now)) {
                log.info("ISH-01 сразу после EMP: стреляют {} игроков, HP {}% (за сутки {})", players,
                        Math.round(hp * 100), AbilityUse.today(h.id(), ISH, System.currentTimeMillis()) + 1);
                ctx.actions().useItem(ISH);
                pressed(w, ISH, now);
                ishUses++;
                ishAt = now;
                return;
            }
        }
        // the prismatic shield from the fight's first moment at the behemoth; the other abilities when the train shoots
        if (plan.prismaticAtStart() && useFirstReady(w, PRISMATIC, now, "призматический щит (начало боя)")) {
            abilityAt = now;
            return;
        }
        if (threat || plan.prismaticBelow() > 0 && hp >= 0 && hp < plan.prismaticBelow()) {
            String why = threat ? players + " игроков стреляют" : "HP " + Math.round(hp * 100) + "%";
            if (useFirstReady(w, PRISMATIC, now, "призматический щит (" + why + ")")
                    || useFirstReady(w, FIGHT_ABILITIES, now, "способность корабля (" + why + ")")) {
                abilityAt = now;
                return;
            }
        }
        if (now - abilityAt < 1_000_000_000L) {
            return; // not two at once
        }
        BotConfig.FightOptions fo = ctx.config().get().loot().fight();
        if (plan.ish() && plan.ishBelow() > 0 && hp >= 0 && hp < plan.ishBelow()
                && may(w, h, ISH, fo.ishSpend(), ishUses, ishAt, now)) {
            log.info("ISH-01: HP {}%, стреляют {} игроков (за сутки {})", Math.round(hp * 100), players,
                    AbilityUse.today(h.id(), ISH, System.currentTimeMillis()) + 1);
            ctx.actions().useItem(ISH);
            pressed(w, ISH, now);
            ishUses++;
            ishAt = now;
            return;
        }
        // EMP only breaks players' locks: with none shooting it is wasted (live 2026-11-14 on the VPS, 3-BL: come at 29 %
        // after a train on the way, EMP at once with nobody shooting); {@code empOnlyPlayers} off — by HP alone
        if (plan.emp() && !plan.empThenIsh() && plan.empBelow() > 0 && hp >= 0 && hp < plan.empBelow()
                && (players > 0 || !plan.empOnlyPlayers())
                && now - ishAt > plan.empAfterIshMs() * 1_000_000
                && may(w, h, EMP, fo.empSpend(), empUses, empAt, now)) {
            log.info("EMP-01: HP {}%, стреляют {} игроков (за сутки {})", Math.round(hp * 100), players,
                    AbilityUse.today(h.id(), EMP, System.currentTimeMillis()) + 1);
            ctx.actions().useItem(EMP);
            pressed(w, EMP, now);
            empUses++;
            empAt = now;
        }
    }

    /** {@code empThenIsh}: ISH-01 follows an EMP-01 within this (a tick or two, the item bar's answer between). */
    static final long EMP_THEN_ISH_NS = 5_000_000_000L;

    /**
     * Players shoot the ship off a fight (on the way to the behemoth's map or spawn): EMP-01 breaks their locks and the
     * way to the spawn point goes on. True when used.
     */
    public boolean empUnderFire(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int players = 0;
        for (int id : h.attackers()) {
            WorldSnapshot.Ship s = w.ships().get(id);
            if (s != null && s.kind() == WorldSnapshot.ShipKind.PLAYER) {
                players++;
            }
        }
        long now = ctx.now();
        if (players == 0 || !may(w, h, EMP, ctx.config().get().loot().fight().empSpend(), empUses, empAt, now)) {
            return false;
        }
        log.info("EMP-01 в пути: стреляют {} игроков, HP {}% — лечу дальше (за сутки {})", players,
                Math.round(h.health().hpPercent() * 100), AbilityUse.today(h.id(), EMP, System.currentTimeMillis()) + 1);
        ctx.actions().useItem(EMP);
        pressed(w, EMP, now);
        empAt = now;
        return true;
    }

    /** The first of these items the game shows ready (an ability, a tech), used; false when none is. */
    private boolean useFirstReady(WorldSnapshot w, java.util.List<String> ids, long now, String what) {
        for (String id : ids) {
            Long asked = askedItem.get(id);
            if (asked != null && now - asked < ITEM_ASK_NS || !ready(w, id, now)) {
                continue;
            }
            log.info("{}: {}", what, orbit.config.ItemCatalog.friendly(id));
            ctx.actions().useItem(id);
            askedItem.put(id, now);
            return true;
        }
        return false;
    }

    /**
     * The item may be used now: ready in the game, the fight's count, the pause, the hour's and the day's limits and
     * the stock kept (the config's spending section); a limit reached is told once.
     */
    /** EMP and ISH pressed and not yet answered by the game: id → {when, how many there were}. */
    private final java.util.Map<String, double[]> pendingUse = new java.util.HashMap<>();
    /** No new press of these before this (the game did not answer the last one). */
    private final java.util.Map<String, Long> unansweredUntil = new java.util.HashMap<>();
    /** The game answers a use within this: the cooldown starts or a charge goes. */
    static final long USE_ANSWER_NS = 4_000_000_000L;
    /** After an unanswered use the next one waits this (live 2026-11-16 on gb2: ISH every 5 s for a minute while the
     *  connection lagged, each counted as used). */
    static final long UNANSWERED_PAUSE_NS = 20_000_000_000L;

    private void pressed(WorldSnapshot w, String id, long now) {
        WorldSnapshot.Item i = w.items().get(id);
        pendingUse.put(id, new double[] {now, i == null ? -1 : i.counter()});
    }

    /** A use counts for the day once the game answers it; none in {@link #USE_ANSWER_NS} — a pause before the next. */
    private void settle(WorldSnapshot w, long now) {
        for (var it = pendingUse.entrySet().iterator(); it.hasNext(); ) {
            var e = it.next();
            WorldSnapshot.Item i = w.items().get(e.getKey());
            double[] p = e.getValue();
            if (i != null && (i.remainingMs(now) > 0 || p[1] >= 0 && i.counter() < p[1])) {
                AbilityUse.used(w.hero().id(), e.getKey(), System.currentTimeMillis());
                it.remove();
            } else if (now - (long) p[0] > USE_ANSWER_NS) {
                it.remove();
                unansweredUntil.put(e.getKey(), now + UNANSWERED_PAUSE_NS);
                log.warn("{}: игра не ответила на применение (связь тормозит?) — не считаю, снова через {} с",
                        orbit.config.ItemCatalog.friendly(e.getKey()), UNANSWERED_PAUSE_NS / 1_000_000_000L);
            }
        }
    }

    private boolean may(WorldSnapshot w, WorldSnapshot.Hero h, String id, BotConfig.Spend s, int usesThisFight,
                        long lastAt, long now) {
        settle(w, now);
        if (pendingUse.containsKey(id) || now < unansweredUntil.getOrDefault(id, Long.MIN_VALUE)) {
            return false;
        }
        if (s.perFight() > 0 && usesThisFight >= s.perFight() || now - lastAt < s.gapSeconds() * 1_000_000_000L
                || !ready(w, id, now)) {
            return false;
        }
        WorldSnapshot.Item i = w.items().get(id);
        String why = s.keep() > 0 && i.counter() <= s.keep()
                ? "осталось " + (long) i.counter() + " — это запас (" + s.keep() + ")"
                : AbilityUse.limited(h.id(), id, s, System.currentTimeMillis());
        if (why != null) {
            if (!why.equals(limitTold.get(id))) {
                limitTold.put(id, why);
                log.info("{}: не применяю — {}", orbit.config.ItemCatalog.friendly(id), why);
            }
            return false;
        }
        limitTold.remove(id);
        return true;
    }

    private static boolean ready(WorldSnapshot w, String id, long now) {
        WorldSnapshot.Item i = w.items().get(id);
        return i != null && i.available() && !i.blocked() && i.remainingMs(now) == 0
                && (i.maxCounter() <= 0 || i.counter() > 0);
    }

    public void target(WorldSnapshot.Ship s) {
        if (s.id() == targetId) {
            return;
        }
        targetId = s.id();
        targetName = s.name();
        ammoTries = 0; // a new target: the switches start counting afresh
        empUses = 0;
        ishUses = 0;
        shotAt = false;
        circleDir = 0;
        kiteAngle = null;
        kiteTold = false;
        leadingFrom = null;
        ledFrom.clear();
        standAt = null;
        modeTold = null;

        watchedSince = ctx.now();
        judgeTarget0 = -1;
        judgeHull0 = -1;
        t0Cubikon = CubikonModule.isCubikon(s);
        if (t0Cubikon) {
            ctx.stats().cubeFought(s.id());
        }
        selectAt = ctx.now() + ctx.react(300, 900);
        pressAt = 0;
        lastHealth = -1;
        healthDropAt = ctx.now();
        WorldSnapshot.Hero h = ctx.world().get().hero();
        log.info("Цель: {} в {} (у {}/{}, мы у {}/{})", s.name(), (int) s.distanceTo(h.x(), h.y()), s.x() / 100, s.y() / 100,
                (int) h.x() / 100, (int) h.y() / 100);
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
        targetRule = null;
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
        // DarkBot's moveToAnSafePosition: destinationInTime(400) in a gate
        double lead = gate != null ? 0.4
                : Math.min(LEAD_MAX_S, LEAD_MIN_S + Math.max(0, dist - radius) / Math.max(200, h.speed()));
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
        settle(w, ctx.now());
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Ship t = w.ships().get(targetId);
        if (t == null && !shotAt && w.destroyed().containsKey(targetId) && seenAt != 0
                && Math.hypot(h.x() - seenX, h.y() - seenY) <= KILL_NEAR) {
            // gone in the first volley before the server confirmed our attack (live 2026-11-13 on 2-3: Lordakia
            // dies in one or two volleys, the reward came, the fight was logged as lost from sight)
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
                if (targetName != null) {
                    KILLED_KINDS.add(targetName);
                }
                lastKill = new Kill(targetName, seenX, seenY, ctx.now(), targetRule != null
                        && targetRule.has(BotConfig.NpcFlag.IGNORE_BOXES));
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
        // another player's: greyed by the server, or someone else shot first — no reward for us (only the first
        // shooter's group gets it, except cubes and group NPCs)
        if (Targets.taken(c, w, t)) {
            log.info("Цель {} чужая (сервер отдал её другому игроку) — бросаю", t.name());
            endReason = "чужая";
            givenUp.put(t.id(), now + GIVE_UP_MS * 1_000_000);
            if (h.attackTargetId() == t.id()) {
                ctx.actions().stopAttack();
            }
            clear();
            return false;
        }
        BotConfig.ShipMode repair = repairMode;
        repairMode = null;
        double radius = gate != null && repair != null ? GATE_REPAIR_RADIUS : Targets.radius(c, t, h.mapId());
        double dist = t.distanceTo(h.x(), h.y());
        BotConfig.NpcRule rule = Targets.rule(c, t, h.mapId());
        targetRule = rule; // the pet's HP Link and the boxes go by it from the first moment of the fight
        track(t, now);
        // the run mode to catch up is the config's «chase» only: DarkBot's AGGRESSIVE_FOLLOW changes the radius alone
        // (exactly as DarkBot)
        // a target flagged RUSH: the run mode to it too (invokes through the enemy trains)
        boolean chasing = move && dist > radius + CHASE_GAP
                && (c.general().runModeChase() || rule != null && rule.has(BotConfig.NpcFlag.RUSH));
        boolean onTheWay = move && dist > radius + APPROACH && h.attackers().isEmpty();
        BotConfig.FightMove mode = mode(c, rule, t, h, now);
        // DarkBot's RUN_CONFIG_IN_CIRCLE: the run configuration to catch a nearly dead target that got away (below
        // 25 % and farther than twice the radius) — not for the fight (live 2026-11-13: fought in the speed
        // configuration, Impulse II lost 20 % in 25 s)
        boolean runCircle = c.loot().runCircle() && dist > 2 * radius && t.health().known()
                && t.health().hpPercent() < 0.25;
        BotConfig.ShipMode fightMode = c.general().offensive();
        if (rule != null && rule.formation() != null && !rule.formation().isBlank() && fightMode != null) {
            // the NPC's own formation (AstraBot: Invoke XVI in Drill), the config's configuration
            fightMode = new BotConfig.ShipMode(fightMode.config(), rule.formation());
        }
        BotConfig.ShipMode boxMode = boxShipMode;
        boxShipMode = null;
        if (gate != null) {
            // SharedPlugin's setConfig: the repair's mode as it is, beyond 2 000 roam, else the fight's
            ctx.ship().want(repair != null ? repair : dist > GATE_ROAM ? c.general().roam() : fightMode);
        } else {
            ctx.ship().want(boxMode != null ? boxMode
                    : chasing || runCircle ? c.general().run() : onTheWay ? c.general().travel() : fightMode);
        }
        Threats threats = move ? new Threats(ctx, ctx.world().get(), c, t.id()) : null;
        double baseRadius = Targets.baseRadius(c, t, h.mapId());
        learnedFarther = radius > baseRadius + 1;
        fight.radius(radius, baseRadius, true);
        fight.sample(w, t, radius, switch (mode) {
            case KITE -> "отход";
            case STAND -> "стою";
            default -> "кружу";
        }, now);
        boolean holding = mode == BotConfig.FightMove.KITE || mode == BotConfig.FightMove.STAND;
        if (gate != null && move) {
            // SharedPlugin's moveToAnSafePosition: the circle near and far alike (its pull to the radius brings the
            // ship in), NO_CIRCLE stands
            if (now >= nextApproach) {
                if (mode == BotConfig.FightMove.STAND) {
                    stand(threats, h, t, radius);
                    nextApproach = now + ctx.pause(500, 900);
                } else {
                    circle(threats, h, t, radius, repair != null);
                    nextApproach = now + ctx.pause(250, 400);
                }
            }
        } else if (move && dist > (holding ? radius * KITE_APPROACH : radius + 60) && now >= nextApproach) {
            // a fleeing target: aim where it will be and go well inside the radius, re-aiming often (live 2026-11-12:
            // Boss Kristallon ran, the ship stopped at the radius of where it had been and never fired); one that stays:
            // come in from its safer side
            boolean fleeing = t.moving();
            double[] at = ahead(t, h, radius);
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
                    // hold the radius, away from it as it comes (Blacklight NPCs chase)
                    kite(threats, h, t, radius);
                    nextApproach = now + ctx.pause(300, 500);
                }
                case STAND -> {
                    stand(threats, h, t, radius);
                    nextApproach = now + ctx.pause(500, 900);
                }
                case CIRCLE -> {
                    // an NPC that attacks on sight by the target: the circle would pass by it — lead the target off
                    // instead, it follows the ship and the slow one stays behind (2026-11-13 on 3-BL:
                    // Impulse II fought by Attend IX, the ship flew right up to it)
                    // never the cube: its Protegits stay by it wherever it goes, leading it off only kept the ship out
                    // of its reach (2026-11-16: it did not circle the cube); DarkBot just circles it
                    WorldSnapshot.Ship danger = threats != null && !CubikonModule.isCubikon(t)
                            ? threats.dangerNear(t, radius) : null;
                    if (danger != null) {
                        if (ledFrom.add(danger.name())) {
                            log.info("Увожу {} от {}", t.name(), danger.name()); // once a fight for each
                        }
                        leadingFrom = danger.name();
                        kite(threats, h, t, radius);
                        nextApproach = now + ctx.pause(300, 450);
                    } else {
                        leadingFrom = null;
                        circle(threats, h, t, radius, false);
                        nextApproach = now + ctx.pause(250, 400);
                    }
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
        abilities(c, rule, w, h, dist, radius, now);
        // close enough to shoot: it must lose something soon, whether the attack started or not (without
        // ammunition the game never starts it)
        if (gate == null && cannotWin(c, t, h, now)) {
            return false; // in a gate DarkBot never gives a target up as too strong
        }
        if (dist <= radius + 200) {
            if ((rule == null || !rule.has(BotConfig.NpcFlag.HOLD_TARGET)) && noDamage(t, now)) {
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
        // and the lasers' part of it: the bar comes in parts (live 2026-11-15 on gb2: MCB-50 reported missing a second
        // after the login, 1 113 049 of them on the account)
        boolean lasersKnown = w.items().keySet().stream().anyMatch(k -> k.startsWith("ammunition_laser_"));
        if (ammo != null && lasersKnown && (wanted == null || wanted.counter() < MIN_AMMO)
                && !ammo.equals(missingAmmo)) {
            missingAmmo = ammo;
            log.warn("Нет патронов {} ({}) — стреляю тем, что выбрано в игре ({})", orbit.config.ItemCatalog.label(ammo),
                    wanted == null ? "нет в инвентаре" : (long) wanted.counter() + " шт.",
                    selected == null ? "?" : orbit.config.ItemCatalog.label(selected.id()));
        }
        if (wanted != null && (wanted.selected() || wanted.remainingMs(now) > 0)) {
            // selected, or its cooldown runs: it fired (RSB-75 shoots once and the game goes back to the ammunition
            // before — live 2026-11-13 on the VPS, a false "does not switch" a minute without RSB)
            ammoTries = 0;
        }
        if (wanted != null && ammo.equals(triedAmmo) && ammoCountAtPress >= 0 && wanted.counter() < ammoCountAtPress) {
            // fewer than at the press: it fired, though the bar never showed it selected (live 2026-11-16 on gb2:
            // RSB-75 shot and the game went back to A-BL between two looks — a false "does not switch" at a Behemoth)
            ammoTries = 0;
            ammoCountAtPress = wanted.counter();
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
                pressAt = now + ctx.react(800, 1_500);
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
                ammoCountAtPress = wanted.counter();
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
            pressAt = now + ctx.react(800, 1_500);
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
    /** DarkBot's: a side this much better turns the circle. */
    static final double SIDE_BETTER = 300;

    /** NPC kinds' top speed seen (units per second): {@link BotConfig.FightMove#AUTO} goes by it. */
    private static final java.util.Map<String, Double> KIND_SPEED = new java.util.concurrent.ConcurrentHashMap<>();

    /** The top speed seen of an NPC kind fought, 0 when unknown. */
    static double kindSpeed(String name) {
        return name == null ? 0 : KIND_SPEED.getOrDefault(name, 0.0);
    }
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
     * item D3; the cubes as they are). {@code NO_CIRCLE} turns circling into standing.
     */
    private BotConfig.FightMove mode(BotConfig c, BotConfig.NpcRule rule, WorldSnapshot.Ship t, WorldSnapshot.Hero h,
                                     long now) {
        if (gate != null) {
            BotConfig.FightMove g = rule != null && rule.has(BotConfig.NpcFlag.NO_CIRCLE) ? BotConfig.FightMove.STAND
                    : BotConfig.FightMove.CIRCLE;
            modeTold = g;
            return g;
        }
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
        // by the map's edge the circle goes on as DarkBot's: its points beyond the edge score «cannot», the way turns
        // and the ship goes to and fro on the map's side (2026-11-17: it stood still by the radiation with the
        // «hold the distance from the centre's side» of 2026-11-15 — now exactly as DarkBot)
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
     * are least (docs item D4: not through the other NPCs' radii); {@code null} when nothing is better than straight.
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
    /** The NPC the target is led away from now, {@code null} while circling. */
    private String leadingFrom;
    /** The kinds the target was led away from this fight (told once each). */
    private final java.util.Set<String> ledFrom = new java.util.HashSet<>();

    private void kite(Threats th, WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius) {
        if (!kiteTold && leadingFrom == null) {
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
            circle(th, h, t, radius, false);
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
     * blocked and the other not, or better by 300), a blocked point searched round (docs item D1).
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

    /** The radius of this fight was learned farther than the base one (the target hit the ship nearer). */
    private boolean learnedFarther;

    private void circle(Threats th, WorldSnapshot.Hero h, WorldSnapshot.Ship t, double radius, boolean raw) {
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
        if (gate != null && !raw) {
            // a gate's NPC beyond the map's edge (Sibelonits at 210/135 on the 21 000 × 13 100 GG β, live 2026-11-15):
            // no circle round it fits the map — the nearest point of the map to it, the laser reaches from there
            orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(h.mapId());
            double ex = Math.max(1, Math.min(m.width() - 1, cx));
            double ey = Math.max(1, Math.min(m.height() - 1, cy));
            if (Math.hypot(ex - cx, ey - cy) > 100) {
                if (Math.hypot(ex - h.x(), ey - h.y()) > 30) {
                    ctx.nav().moveTo(ex, ey, false);
                }
                return;
            }
        }
        double r = radius;
        // DarkBot comes nearer to a still or nearly dead NPC — not when its kind was learned to hit the ship nearer
        // (live 2026-11-13 on 2-7: Boss Kristallon learned 675, fought at 593, the learning took it for 675)
        if (gate != null) {
            if (!raw) {
                // DarkBot's NpcAttacker.modifyRadius exactly: not shooting it yet — 550 at most, so the first shot
                // reaches; a still or nearly dead one — 600; a nearly dead follower ×0.75
                boolean low = t.health().known() && t.health().hpPercent() < 0.25;
                BotConfig.NpcRule rr0 = Targets.rule(ctx.config().get(), t, h.mapId());
                if (low && rr0 != null && rr0.has(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW)) {
                    r *= 0.75;
                }
                if (h.attackTargetId() != t.id()) {
                    r = Math.min(550, r);
                } else if (!t.moving() || low) {
                    r = Math.min(600, r);
                }
            }
        } else if (!raw) {
            // DarkBot's modifyRadius: a nearly dead NPC with «aggressive follow» — the radius × 0.75, on every map, not
            // in the gates only
            boolean low = t.health().known() && t.health().hpPercent() < 0.25;
            BotConfig.NpcRule rr0 = Targets.rule(ctx.config().get(), t, h.mapId());
            if (low && rr0 != null && rr0.has(BotConfig.NpcFlag.AGGRESSIVE_FOLLOW)) {
                r *= 0.75;
            }
            if (!learnedFarther && (!t.moving() || low)) {
                r = Math.min(r, 600); // DarkBot's modifyRadius; the repair's 2 000 is taken raw
            }
        }
        if (gate == null && fleeing(t, h)) {
            r *= 0.8; // one going away does not leave the radius while the ship goes round
        }
        double dist = Math.hypot(h.x() - cx, h.y() - cy);
        double angle = Math.atan2(h.y() - cy, h.x() - cx);
        double rr = Math.max(100, dist + Math.max(-r / 2, Math.min(r / 2, r - dist)));
        double gap = Math.hypot(cx + Math.cos(angle) * rr - h.x(), cy + Math.sin(angle) * rr - h.y());
        double npcSpeed = Math.max(200, Math.hypot(vx, vy));
        double step = Math.max(0.05, Math.max(0, 0.625 * Math.max(1, h.speed()) + 0.625 * npcSpeed - gap) / rr);
        if (gate != null) {
            // DarkBot's moveToAnSafePosition exactly: the point radius + clamp(radius − distance, ±radius / 2) from the
            // target — from afar half the radius off it, straight in (no step: the gap eats it); the NPC's speed at most
            // 200 (live 2026-11-15 on gb2, GG β: Sibelonits in a corner 6 600 off, the ship went round far from them)
            rr = Math.max(1, r + Math.max(-r / 2, Math.min(r / 2, r - dist)));
            gap = Math.hypot(cx + Math.cos(angle) * rr - h.x(), cy + Math.sin(angle) * rr - h.y());
            step = Math.max(0, 0.625 * Math.max(1, h.speed()) + 0.625 * Math.min(200, Math.hypot(vx, vy)) - gap) / rr;
        }
        // approachToCenter: the way fixed, no scoring (the point is still searched valid)
        boolean fixedWay = gate != null && towardCentre(h, t);
        if (th != null && !fixedWay) {
            // DarkBot's getBestDir (as in DarkBot): up to N points each way (the config's
            // «smart circling», DarkBot's MAX_CIRCLE_ITERATIONS, 0 = never turn); stop as soon as one side is clear and
            // the other not, or one is better by 300; turn to the better side then, else keep the way
            int max = ctx.config().get().loot().circleIterations();
            double plus = 0;
            double minus = 0;
            int k = 1;
            boolean decided = false;
            do {
                plus += th.darkScore(cx + Math.cos(angle + k * step) * rr, cy + Math.sin(angle + k * step) * rr);
                minus += th.darkScore(cx + Math.cos(angle - k * step) * rr, cy + Math.sin(angle - k * step) * rr);
                if (plus < 0 != minus < 0 || Math.abs(plus - minus) > SIDE_BETTER) {
                    decided = true;
                    break;
                }
            } while (k++ < max);
            if (decided && max > 0) {
                int dir = minus > plus ? -1 : 1;
                if (dir != circleDir) {
                    circleDir = dir;
                    fight.turned();
                    log.info("Кружу в другую сторону: там {}", Math.min(plus, minus) <= -Threats.CANNOT ? "нельзя"
                            : "другие NPC или враги");
                }
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

    /**
     * SharedPlugin's approachToCenter: with the ship and the target both over {@link #CENTER_ON} from the gate's centre
     * (till both are under {@link #CENTER_OFF}) and the target above 30 % HP, the circle's way is fixed: when the ship
     * and the target lie on (nearly) the same ray from the centre, the way is set by the side the ship is on.
     */
    private boolean towardCentre(WorldSnapshot.Hero h, WorldSnapshot.Ship t) {
        double heroC = Math.hypot(h.x() - gate[0], h.y() - gate[1]);
        double tC = Math.hypot(t.x() - gate[0], t.y() - gate[1]);
        double off = centerOn - (CENTER_ON - CENTER_OFF);
        if (heroC > centerOn && tC > centerOn) {
            towardCentre = true;
        } else if (heroC < off && tC < off) {
            towardCentre = false;
        }
        if (!towardCentre || t.health().known() && t.health().hpPercent() <= 0.3) {
            return false;
        }
        double diff = Math.toDegrees(Math.atan2(gate[1] - h.y(), gate[0] - h.x()) - Math.atan2(gate[1] - t.y(), gate[0] - t.x()));
        diff = ((diff + 540) % 360) - 180;
        if (Math.abs(diff) < 2) {
            circleDir = diff < 0 ? -1 : 1;
        }
        return true;
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
                    rocketPressAt = now + ctx.react(300, 900);
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
                rocketFireAt = now + ctx.react(200, 800);
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
                    launcherPressAt = now + ctx.react(300, 900);
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
                launcherFireAt = now + ctx.react(200, 800);
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
        // RSB when it is ready (the config's «RSB»; AstraBot «Use RSB»): one salvo, then the usual ammunition while it
        // reloads (its cooldown is the item's timer)
        // AstraBot «Use RSB»: once the config flags some NPC with it, only those get it
        boolean rsbHere = r != null && r.has(BotConfig.NpcFlag.USE_RSB) || !c.anyNpc(BotConfig.NpcFlag.USE_RSB);
        if (rsbHere && l.rsb() != null && ready(w, l.rsb(), w.nanos()) && w.items().get(l.rsb()).counter() >= MIN_AMMO) {
            return l.rsb();
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
