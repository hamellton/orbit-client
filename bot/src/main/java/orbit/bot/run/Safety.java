package orbit.bot.run;

import java.util.Comparator;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the ship alive between deaths (the SAFETY part of a config):
 * <ul>
 *   <li>heals on the go: the repair robot when HP is below {@code heal.hpBelow} and nobody shoots;</li>
 *   <li>below {@code repairHpMin} (attacked or fighting) or {@code repairHpNoNpc} (quiet) leaves the fight in
 *       the repair configuration, flies to the nearest portal and repairs there up to {@code repairHpMax}
 *       (and {@code repairToShield});</li>
 *   <li>at the portal, still shot at and nearly dead: jumps through; the runner brings the ship back
 *       after the repair.</li>
 * </ul>
 */
public final class Safety {

    private static final Logger log = LoggerFactory.getLogger(Safety.class);

    static final String REPAIR_BOT = "equipment_extra_repbot_rep";
    static final double AT_PORTAL = 300;
    /** The robot starts repairing some seconds after the click: no second click before this. */
    static final long REPAIR_BOT_GAP_MS = 20_000;
    /** HP that went up this recently means the robot is at work: leave it alone. */
    static final long REPAIR_RISING_MS = 5_000;

    private final BotContext ctx;
    private boolean repairing;
    private int repairMap;
    private long healedAt = Long.MIN_VALUE / 2;
    private double healHp = -1;
    private long hpRoseAt = Long.MIN_VALUE / 2;
    private long jumpAt;
    private boolean oneWayTold;
    /** The last escape jump: from which map, through which portal (checked {@link #JUMP_CHECK_MS} later). */
    private int jumpedFrom;
    private MapCatalog.Portal jumpedVia;
    private int deathsAtJump;
    static final long JUMP_CHECK_MS = 8_000;
    /** The map the last escape jump left: no escape jump back into it this soon. */
    private int escapedFrom;
    static final long BACK_INTO_NS = 120_000_000_000L;
    private long jumpedAt = Long.MIN_VALUE / 2;
    private double lastHp = -1;
    private long hurtAt = Long.MIN_VALUE / 2;
    /** Losing HP counts as being attacked this long (damage whose shooter the world does not know: rockets, mines). */
    static final long HURT_MS = 5_000;

    public Safety(BotContext ctx) {
        this.ctx = ctx;
    }

    public boolean repairing() {
        return repairing;
    }

    /**
     * @param fighting the module has a target
     * @return true while safety steers the ship (the module must not act)
     */
    /**
     * Healing on the go only (abilities, repair robot), no trip to repair and no escape jump: for the way out,
     * which must not wait for the hull (live 2026-11-11: stopped right after a revive, the ship repaired first).
     */
    public void healOnly() {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        double hp = h.health().hpPercent();
        if (hp < 0) {
            return;
        }
        long now = ctx.now();
        double hull = h.health().hp();
        if (lastHp >= 0 && hull < lastHp - 1) {
            hurtAt = now;
        }
        lastHp = hull;
        repairing = false;
        boolean attacked = !h.attackers().isEmpty() || now - hurtAt < HURT_MS * 1_000_000;
        heal(w, ctx.config().get().safety(), now, hp, attacked);
    }

    public boolean tick(boolean fighting) {
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        BotConfig.Safety s = ctx.config().get().safety();
        long now = ctx.now();
        double hp = h.health().hpPercent();
        if (hp < 0) {
            return false;
        }
        double hull = h.health().hp();
        if (lastHp >= 0 && hull < lastHp - 1) {
            hurtAt = now;
        }
        lastHp = hull;
        boolean attacked = !h.attackers().isEmpty() || now - hurtAt < HURT_MS * 1_000_000;
        checkJump(w, h, now);
        heal(w, s, now, hp, attacked);
        noteHp(hp, now);
        if (!repairing) {
            double limit = attacked || fighting ? s.repairHpMin() : s.repairHpNoNpc();
            // losing hull fast: leave the more early, so as to reach the portal (live 2026-11-13: 25 % → 5 % on the
            // 7 s way to it, killed)
            double early = attacked ? Math.min(EARLY_MAX, hpLossPerSecond(now) * EARLY_SECONDS) : 0;
            if (early > 0.02 && hp >= limit && hp < limit + early) {
                log.info("Теряю {}% HP в секунду — ухожу на ремонт раньше", pct(hpLossPerSecond(now)));
            }
            limit += early;
            // «repaired» already (a target not above the start, as the palladium profile: below 15 %, up to 0 %):
            // no trip — it would end at once, every tick (live 2026-11-12 on int1: 447 «repairs», the ship
            // stopped each 100 ms)
            if (hp < limit && !repaired(h, s, hp)) {
                repairing = true;
                oneWayTold = false;
                ctx.stats().repairs.incrementAndGet();
                repairMap = h.mapId();
                ctx.nav().stop();
                SafePlaces.Place to = SafePlaces.nearest(w, ctx.maps(), ctx.stats().closedPortals, s.oneWayJumps(), repairMap);
                log.info("HP {}% — ухожу на ремонт{}", pct(hp), to == null ? "" : ": " + to.name());
            } else {
                return false;
            }
        }
        if (repaired(h, s, hp)) {
            repairing = false;
            log.info("Отремонтирован: HP {}%", pct(hp));
            return false;
        }
        ctx.ship().want(s.repairMode());
        // a base (own or shared) or a portal that lets the ship through (on 5-2: the shared base)
        SafePlaces.Place place = SafePlaces.nearest(w, ctx.maps(), ctx.stats().closedPortals, s.oneWayJumps(), repairMap);
        if (place == null) {
            return true; // nothing known (gate, instance): stay and heal
        }
        if (place.distance(h) > place.reach()) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != place.x() || ctx.nav().destination().y() != place.y()) {
                ctx.nav().moveTo(place.x(), place.y(), true);
            }
            ctx.nav().tick();
            return true;
        }
        MapCatalog.Portal portal = place.portal();
        // on a Blacklight map the portal is no shelter (its NPCs reach it): through it at once, the repair on the
        // other side (the user, 2026-11-13)
        boolean noShelter = orbit.bot.nav.Router.blacklight(h.mapId());
        if (portal != null && (attacked && hp < s.escapeHp() || noShelter) && w.jumpingTo() == 0
                && now - jumpedAt > 10_000_000_000L) {
            boolean backInto = portal.toId() == escapedFrom && now - jumpedAt < BACK_INTO_NS;
            if (!place.jumpable() || backInto) {
                // 5-2 → 5-3 has no way back but the whole loop round the pirate maps (live 2026-11-12): stay in the
                // portal's shelter and heal here
                if (!oneWayTold) {
                    log.warn("На портале под огнём, HP {}%, но {} — не прыгаю, лечусь здесь", pct(hp),
                            backInto ? "на " + portal.toName() + " только что убежал от огня"
                                    : oneWay(ctx.maps(), portal, h) ? "с " + portal.toName() + " обратно не вернуться"
                                    : "портал на " + portal.toName() + " не пускает");
                    oneWayTold = true;
                }
            } else if (jumpAt == 0) {
                jumpAt = now + ctx.pause(200, 700);
            } else if (now >= jumpAt) {
                if (attacked) {
                    log.warn("На портале под огнём, HP {}% — прыгаю на {}", pct(hp), portal.toName());
                } else {
                    log.info("На BL-карте у портала укрытия нет — прыгаю на {}, ремонт там", portal.toName());
                }
                ctx.actions().jump();
                jumpedAt = now;
                jumpAt = 0;
                jumpedFrom = h.mapId();
                jumpedVia = portal;
                deathsAtJump = ctx.stats().deaths.get();
                escapedFrom = h.mapId();
            }
        }
        return true;
    }

    /**
     * A jump asked {@link #JUMP_CHECK_MS} ago and the ship still here, not jumping: the portal does not let it
     * through (closed for the company, an event map) — never again this run.
     */
    private void checkJump(WorldSnapshot w, WorldSnapshot.Hero h, long now) {
        if (jumpedVia != null && (w.death() != null || !h.alive() || ctx.stats().deaths.get() != deathsAtJump)) {
            // killed before the jump went: the portal is not to blame (live 2026-11-13: 2-8 marked closed after a
            // death at it, the next repair went to the far 1-BL portal and died there)
            jumpedVia = null;
            return;
        }
        if (jumpedVia == null || now - jumpedAt < JUMP_CHECK_MS * 1_000_000) {
            return;
        }
        if (h.mapId() == jumpedFrom && w.jumpingTo() == 0) {
            ctx.stats().closedPortals.add(SafePlaces.key(jumpedFrom, jumpedVia));
            log.warn("Портал на {} не пускает — больше туда не прыгаю", jumpedVia.toName());
        }
        jumpedVia = null;
    }

    /** HP at {@code repairHpMax} and the shield at {@code repairToShield} (or no shield): nothing to repair. */
    private static boolean repaired(WorldSnapshot.Hero h, BotConfig.Safety s, double hp) {
        double shield = h.health().shieldPercent();
        return hp >= s.repairHpMax() && (shield < 0 || shield >= s.repairToShield() || h.health().shieldMax() <= 0);
    }

    /** The map to come back to after a repair elsewhere (0 if not repairing). */
    public int repairMap() {
        return repairing ? repairMap : 0;
    }

    /**
     * Healing abilities of ships, in order of preference (facts from the open-source DarkBot SharedPlugin
     * SimpleHealing and DarkBot API ids): HP — Solace Plus, Solace, Aegis/Hammerclaw, Orcus; shield and the
     * repair pod — Aegis/Hammerclaw. They work in combat, unlike the repair robot.
     */
    static final java.util.List<String> HP_ABILITIES = java.util.List.of(
            "ability_solace-plus_nano-cluster-repairer-plus", "ability_solace", "ability_aegis_hp-repair",
            "ability_orcus_assimilate");
    static final java.util.List<String> SHIELD_ABILITIES = java.util.List.of("ability_aegis_shield-repair");
    static final java.util.List<String> POD_ABILITIES = java.util.List.of("ability_aegis_repair-pod");
    static final long ABILITY_GAP_MS = 3_000;

    /** When each ability was last pressed: each has its own pause, so one never blocks the others. */
    private final java.util.Map<String, Long> abilityAt = new java.util.HashMap<>();
    private long anyAbilityAt = Long.MIN_VALUE / 2;

    /** Ship abilities by the config's thresholds ({@code -1} = off), in or out of combat. */
    private void abilities(WorldSnapshot w, BotConfig.Heal heal, long now, double hp) {
        if (now - anyAbilityAt < 400_000_000L) {
            return; // one press at a time, like a player
        }
        double shield = w.hero().health().shieldPercent();
        if (heal.hpBelow() >= 0 && hp < heal.hpBelow() && use(w, HP_ABILITIES, now, "лечение", hp)) {
            return;
        }
        if (heal.shieldBelow() >= 0 && shield >= 0 && shield < heal.shieldBelow()
                && use(w, SHIELD_ABILITIES, now, "щит", shield)) {
            return;
        }
        if (heal.hpPodBelow() >= 0 && hp < heal.hpPodBelow()) {
            use(w, POD_ABILITIES, now, "капсула", hp);
        }
    }

    private boolean use(WorldSnapshot w, java.util.List<String> ids, long now, String what, double level) {
        for (String id : ids) {
            WorldSnapshot.Item a = w.items().get(id);
            long last = abilityAt.getOrDefault(id, Long.MIN_VALUE / 2);
            if (a != null && a.available() && !a.blocked() && a.remainingMs(now) == 0
                    && now - last > ABILITY_GAP_MS * 1_000_000) {
                log.info("Способность: {} ({}, {}%)", id, what, pct(level));
                ctx.actions().useItem(id);
                abilityAt.put(id, now);
                anyAbilityAt = now;
                return true;
            }
        }
        return false;
    }

    private void heal(WorldSnapshot w, BotConfig.Safety s, long now, double hp, boolean attacked) {
        abilities(w, s.heal(), now, hp);
        double below = repairing ? s.repairHpMax() : s.heal().hpBelow();
        if (healHp >= 0 && hp > healHp + 1e-4) {
            hpRoseAt = now;
        }
        healHp = hp;
        if (below < 0 || hp >= below || attacked || now - healedAt < REPAIR_BOT_GAP_MS * 1_000_000
                || now - hpRoseAt < REPAIR_RISING_MS * 1_000_000) {
            return;
        }
        WorldSnapshot.Item bot = w.items().get(REPAIR_BOT);
        if (bot != null && bot.available() && bot.remainingMs(now) == 0) {
            log.info("Ремонт роботом (HP {}%)", pct(hp));
            ctx.actions().useItem(REPAIR_BOT);
            healedAt = now;
        }
    }

    /** The seconds of flight a fast loss of hull is allowed for, and the most the repair moves up for it. */
    static final double EARLY_SECONDS = 6;
    static final double EARLY_MAX = 0.3;
    /** Hull shares of the last seconds: {nanos, share}. */
    private final java.util.ArrayDeque<double[]> hpSeen = new java.util.ArrayDeque<>();

    private void noteHp(double hp, long now) {
        hpSeen.addLast(new double[] {now, hp});
        while (!hpSeen.isEmpty() && now - hpSeen.peekFirst()[0] > 4e9) {
            hpSeen.removeFirst();
        }
    }

    /** The share of hull lost a second over the last 4 seconds (0 when none or rising). */
    private double hpLossPerSecond(long now) {
        if (hpSeen.size() < 2) {
            return 0;
        }
        double[] first = hpSeen.peekFirst();
        double[] last = hpSeen.peekLast();
        double secs = (last[0] - first[0]) / 1e9;
        return secs < 1 ? 0 : Math.max(0, (first[1] - last[1]) / secs);
    }

    /** No portal on the far map leads back here (the pirate maps: 5-2 → 5-3, 5-3 → 4-4). */
    static boolean oneWay(MapCatalog maps, MapCatalog.Portal portal, WorldSnapshot.Hero h) {
        return portal.toId() > 0 && maps.portals(portal.toId()).stream()
                .noneMatch(p -> p.toId() == h.mapId() && (p.faction() == 0 || p.faction() == h.factionId()));
    }

    static int pct(double v) {
        return (int) Math.round(v * 100);
    }
}
