package orbit.bot.mod;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import orbit.bot.run.BotContext;
import orbit.config.BotConfig;
import orbit.protocol.gen.PetGearTypeModule;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The pet's kamikaze in a gate as DarkBot's SharedPlugin does it ({@code KamikazeHandler}, read from its classes on the
 * VPS, 2026-11-15; the user: «камикадзе делаем как у даркбота 1 в 1, и поведение корабля тоже»):
 * <ol>
 *   <li>the NPCs flagged {@code KAMIKAZE} (ABG: Kristallin, Saimon, Sibelonit) that aim at the ship now or did within
 *       1 s, and are near the kamikaze point {@code K} (the map's centre (10 000, 6 000) less (1 500, 1 000): within
 *       3 000) or the wave has just begun (primed); fewer than {@code minNpcs} (5) — nothing, the fight as usual;</li>
 *   <li>gathering: the pet passive, no shooting at them, the ship circling the nearest of them (or {@code K}) at 1 500
 *       — they chase it and bunch up;</li>
 *   <li>the blast: no cooldown, the hull and shield at 95 % or more, every two of them within 300 of each other (and
 *       so for 5 s at a wave's start) — the ship stops, the pet's gear «Kamikaze»;</li>
 *   <li>active: the hull at 60 % or less or the shield at 40 % or less — given up with the cooldown; the pet blown up —
 *       the cooldown (30 / 60 / 120 s by the gear's level) and the fight as usual; no blast in 10 s — gathering again.</li>
 * </ol>
 * The nearest of them is kept locked (selected, not shot at) while gathering and for the blast — SharedPlugin locks
 * the closest valid one before it turns the gear on; the pet flies at the locked one (the user, 2026-11-15: «берёт
 * одного в лок и включает камика»).
 * SharedPlugin also shoots a rocket at its own pet to bring its HP under 20 % when solo; that is not done here (not
 * known to be allowed by the server): the pet goes in at its HP and is shot down by the bunch, as KekkaReloaded does.
 */
final class GateKamikaze {

    private static final Logger log = LoggerFactory.getLogger(GateKamikaze.class);

    enum Stage { INACTIVE, PRIMED, GATHERING, ACTIVE }

    /** The kamikaze point off the map's centre (SharedPlugin: centre (10 000, 6 000) + (−1 500, −1 000)). */
    static final int K_X = 10_000 - 1_500;
    static final int K_Y = 6_000 - 1_000;
    static final double NEAR_K = 3_000;
    static final double ORBIT = 1_500;
    static final double TIGHT = 300;
    static final long AIMED_MS = 1_000;
    static final long PRIMED_DELAY_MS = 5_000;
    static final long ACTIVE_TIMEOUT_MS = 10_000;
    /** The kamikaze gear's button on the item bar (its timer is the game's cooldown). */
    static final String KAMIKAZE_ITEM = "equipment_petgear_g-kk1";

    private final BotContext ctx;
    private Stage stage = Stage.INACTIVE;
    private final Map<Integer, Long> aimedAt = new HashMap<>();
    private long cooldownUntil;
    private long activeSince;
    private long tightSince;
    private boolean petSeenAlive;
    private double orbitAngle = Double.NaN;
    private int gear = -1;
    private int lockedId;
    private long toldAt = Long.MIN_VALUE / 2;
    /** The wave started with nothing about (between waves): kept till the blast, as SharedPlugin's PRIMED. */
    private boolean primed;
    /** The flagged ones within this of the ship count for the blast when they do not all aim at it yet. */
    static final double NEAR_SHIP = 2_000;
    private long lockAskedAt = Long.MIN_VALUE / 2;

    GateKamikaze(BotContext ctx) {
        this.ctx = ctx;
    }

    /** The pet's gear the kamikaze wants now ({@code -1} none). */
    int gear() {
        return gear;
    }

    Stage stage() {
        return stage;
    }

    /** Between waves (nothing to fight or pick up): the next wave's start counts as primed (SharedPlugin). */
    void waiting() {
        if (stage == Stage.INACTIVE) {
            stage = Stage.PRIMED;
        }
    }

    /**
     * One step; true while the kamikaze steers the ship (gathering or waiting for the blast) — the module does not
     * fight then.
     */
    boolean tick(JsonNode s, BotConfig c, WorldSnapshot w, Attacker attacker) {
        gear = -1;
        JsonNode k = s.path("kamikaze");
        WorldSnapshot.Pet pet = w.pet();
        // the gear: listed among the pet's modes, or its button on the item bar — the game drops the mode from the list
        // while it cools down (live 2026-11-15 on gb2: «нет режима №10» 14 s after a blast)
        boolean hasGear = pet.gears().isEmpty() || pet.gears().contains(PetGearTypeModule.PETGEARTYPE_KAMIKAZE)
                || w.items().containsKey(KAMIKAZE_ITEM);
        if (!k.path("enabled").asBoolean(false) || !c.pet().enabled() || !pet.owned() || !hasGear) {
            stage = Stage.INACTIVE;
            return false;
        }
        long now = ctx.now();
        WorldSnapshot.Hero h = w.hero();
        if (stage == Stage.ACTIVE) {
            return active(k, w, h, pet, now);
        }
        // the wave is a kamikaze wave when so many flagged ones are on the map — DarkBot knows it by the wave (the user,
        // 2026-11-15: «на кристаллинах он начал их бить, даркбот не бьёт их»): they are not shot then, but gathered,
        // even before they aim at the ship; the blast counts the ones bunched about the ship
        List<WorldSnapshot.Ship> flagged = flagged(c, w, h);
        List<WorldSnapshot.Ship> valid = valid(c, w, h, now);
        int min = Math.max(1, k.path("minNpcs").asInt(5));
        if (flagged.size() >= min && valid.size() < min) {
            // a bunch of them about the ship; scattered over the map one by one they are fought as usual (live
            // 2026-11-15 on gb2: 6 left, none aiming, 5 942 apart — circled for minutes, none shot)
            valid = flagged.stream().filter(sh -> Math.hypot(sh.x() - h.x(), sh.y() - h.y()) <= NEAR_SHIP).toList();
        }
        if (valid.size() < min) {
            if (stage == Stage.GATHERING) {
                log.info("Камикадзе: NPC для взрыва меньше {} — обычный бой", min);
            }
            stage = stage == Stage.PRIMED ? Stage.PRIMED : Stage.INACTIVE;
            primed = stage == Stage.PRIMED;
            tightSince = 0;
            return false;
        }
        if (stage != Stage.GATHERING) {
            log.info("Камикадзе: {} NPC для взрыва — собираю их в кучу (питомец пассивный, по ним не стреляю)", valid.size());
            orbitAngle = Double.NaN;
        }
        if (stage == Stage.PRIMED) {
            primed = true; // SharedPlugin keeps the wave's start till the blast
        }
        stage = Stage.GATHERING;
        if (attacker.targetId() != 0) {
            attacker.clear();
        }
        if (h.attackTargetId() != 0) {
            ctx.actions().stopAttack();
        }
        WorldSnapshot.Item button = w.items().get(KAMIKAZE_ITEM);
        boolean gameReady = (pet.gears().isEmpty() || pet.gears().contains(PetGearTypeModule.PETGEARTYPE_KAMIKAZE))
                && (button == null || button.remainingMs(now) == 0);
        // the pet out and alive: a dead or docked one «blew up» 0.1 s after the gear (live 2026-11-15 on gb2)
        boolean petOut = pet.alive() && pet.active();
        boolean ready = petOut && gameReady && now >= cooldownUntil && share(h.health().hpPercent()) >= k.path("hpMin").asDouble(0.95)
                && share(h.health().shieldPercent()) >= k.path("shieldMin").asDouble(0.95);
        boolean tight = spread(valid) <= k.path("tight").asDouble(TIGHT);
        if (tight) {
            if (tightSince == 0) {
                tightSince = now;
            }
        } else {
            tightSince = 0;
        }
        boolean waited = !primed || tightSince != 0 && ms(now - tightSince) >= PRIMED_DELAY_MS;
        WorldSnapshot.Ship near = nearest(valid, h);
        lock(h, near, now);
        if (ready && tight && waited) {
            primed = false;
            stage = Stage.ACTIVE;
            activeSince = now;
            petSeenAlive = pet.alive() && pet.active();
            log.info("Камикадзе: {} NPC в куче (разброс {}), HP {}% — стою, питомец «Камикадзе»", valid.size(),
                    (int) spread(valid), Math.round(share(h.health().hpPercent()) * 100));
            ctx.nav().stop();
            ctx.ship().want(mode(k, c));
            gear = PetGearTypeModule.PETGEARTYPE_KAMIKAZE;
            return true;
        }
        gear = PetGearTypeModule.PETGEARTYPE_PASSIVE;
        if (ms(now - toldAt) >= 10_000) {
            // why no blast yet, every 10 s (live 2026-11-15 on gb2: circling Kristallins with nothing said)
            toldAt = now;
            log.info("Камикадзе: жду — с флагом {}, стреляют {}, в счёт {}, разброс {} (нужно ≤ {}), ближний в {}, HP {}%, щит {}%,"
                            + " перезарядка {}, режим питомца {}", flagged.size(), aiming(w, h, now), valid.size(),
                    (int) spread(valid), (int) k.path("tight").asDouble(TIGHT),
                    near == null ? -1 : (int) Math.hypot(near.x() - h.x(), near.y() - h.y()),
                    Math.round(share(h.health().hpPercent()) * 100), Math.round(share(h.health().shieldPercent()) * 100),
                    now < cooldownUntil ? "своя" : !gameReady ? "игры" : "нет", gameReady ? "есть" : "нет");
            if (!petOut) {
                log.info("Камикадзе: питомец не выпущен — жду его");
            }
        }
        ctx.ship().want(mode(k, c));
        gather(w, h, near, k.path("orbit").asDouble(ORBIT));
        return true;
    }

    private boolean active(JsonNode k, WorldSnapshot w, WorldSnapshot.Hero h, WorldSnapshot.Pet pet, long now) {
        double hp = share(h.health().hpPercent());
        double sh = share(h.health().shieldPercent());
        boolean dead = !pet.alive() || petSeenAlive && !pet.active();
        if (pet.alive() && pet.active()) {
            petSeenAlive = true;
        }
        if (dead || hp <= k.path("hpAbort").asDouble(0.6) || sh <= k.path("shieldAbort").asDouble(0.4)) {
            long cd = cooldownMs(k);
            cooldownUntil = now + cd * 1_000_000;
            stage = Stage.INACTIVE;
            log.info(dead ? "Камикадзе: питомец взорвался — следующий через {} с, обычный бой"
                    : "Камикадзе: HP/щит упали — бросаю, следующий через {} с", cd / 1000);
            return false;
        }
        if (ms(now - activeSince) > ACTIVE_TIMEOUT_MS) {
            stage = Stage.INACTIVE;
            log.info("Камикадзе: за {} с взрыва нет — снова собираю", ACTIVE_TIMEOUT_MS / 1000);
            return false;
        }
        ctx.nav().stop();
        ctx.ship().want(mode(k, ctx.config().get()));
        WorldSnapshot.Ship locked = w.ships().get(lockedId);
        if (locked == null) {
            locked = nearest(valid(ctx.config().get(), w, h, now), h);
        }
        lock(h, locked, now);
        gear = PetGearTypeModule.PETGEARTYPE_KAMIKAZE;
        return true;
    }

    /**
     * The ship's mode while gathering and for the blast: the config's run mode (the user, 2026-11-15: «собирать НПС для
     * камикадзе нужно на беговой конфигурации на Ring»), or the fight's with {@code mode: "fight"}.
     */
    static BotConfig.ShipMode mode(JsonNode k, BotConfig c) {
        return "fight".equals(k.path("mode").asText("run")) ? c.general().offensive() : c.general().run();
    }

    /** Selects {@code s} (a click, no shot), once a second at most while another is selected. */
    private void lock(WorldSnapshot.Hero h, WorldSnapshot.Ship s, long now) {
        if (s == null) {
            return;
        }
        lockedId = s.id();
        if (h.selectedId() != s.id() && ms(now - lockAskedAt) >= 1_000) {
            ctx.actions().select(s);
            lockAskedAt = now;
        }
    }

    /** The cooldown by the gear's level (SharedPlugin LEVEL_1/2/3 = 120/60/30 s), the setting {@code level}. */
    static long cooldownMs(JsonNode k) {
        return switch (k.path("level").asInt(3)) {
            case 1 -> 120_000;
            case 2 -> 60_000;
            default -> 30_000;
        };
    }

    /** Every flagged NPC on the map. */
    private static List<WorldSnapshot.Ship> flagged(BotConfig c, WorldSnapshot w, WorldSnapshot.Hero h) {
        List<WorldSnapshot.Ship> out = new ArrayList<>();
        for (WorldSnapshot.Ship s : w.ships().values()) {
            BotConfig.NpcRule r = s.npc() ? Targets.rule(c, s, h.mapId()) : null;
            if (r != null && r.has(BotConfig.NpcFlag.KAMIKAZE)) {
                out.add(s);
            }
        }
        return out;
    }

    /** The flagged NPCs aiming at the ship (now or within 1 s), near K or at a primed wave's start. */
    private List<WorldSnapshot.Ship> valid(BotConfig c, WorldSnapshot w, WorldSnapshot.Hero h, long now) {
        List<WorldSnapshot.Ship> out = new ArrayList<>();
        boolean primed = this.primed || stage == Stage.PRIMED;
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (!s.npc()) {
                continue;
            }
            BotConfig.NpcRule r = Targets.rule(c, s, h.mapId());
            if (r == null || !r.has(BotConfig.NpcFlag.KAMIKAZE)) {
                continue;
            }
            if (s.attackTargetId() == h.id()) {
                aimedAt.put(s.id(), now);
            }
            Long at = aimedAt.get(s.id());
            boolean aims = at != null && ms(now - at) <= AIMED_MS;
            boolean nearK = Math.hypot(s.x() - K_X, s.y() - K_Y) <= NEAR_K;
            if (aims && (primed || nearK)) {
                out.add(s);
            }
        }
        aimedAt.keySet().removeIf(id -> !w.ships().containsKey(id));
        return out;
    }

    /** How many NPCs aimed at the ship within the last second. */
    private int aiming(WorldSnapshot w, WorldSnapshot.Hero h, long now) {
        return (int) aimedAt.values().stream().filter(at -> ms(now - at) <= AIMED_MS).count();
    }

    /** The largest distance between two of them. */
    static double spread(List<WorldSnapshot.Ship> ships) {
        double max = 0;
        for (int i = 0; i < ships.size(); i++) {
            for (int j = i + 1; j < ships.size(); j++) {
                max = Math.max(max, Math.hypot(ships.get(i).x() - ships.get(j).x(), ships.get(i).y() - ships.get(j).y()));
            }
        }
        return max;
    }

    private static WorldSnapshot.Ship nearest(List<WorldSnapshot.Ship> ships, WorldSnapshot.Hero h) {
        return ships.stream().min(java.util.Comparator.comparingDouble(s -> Math.hypot(s.x() - h.x(), s.y() - h.y())))
                .orElse(null);
    }

    /**
     * Round the nearest flagged NPC (else K) at {@link #ORBIT}, DarkBot's circling: the distance pulled toward the radius
     * by at most 750 a step, the angle stepped by (0.625·speed + 125) / radius.
     */
    private void gather(WorldSnapshot w, WorldSnapshot.Hero h, WorldSnapshot.Ship around, double orbitRadius) {
        double cx = around != null ? around.x() : K_X;
        double cy = around != null ? around.y() : K_Y;
        double d = Math.hypot(h.x() - cx, h.y() - cy);
        double a = Math.atan2(h.y() - cy, h.x() - cx);
        if (Double.isNaN(orbitAngle)) {
            orbitAngle = 1; // one way round, as DarkBot keeps its direction
        }
        // the radius: SharedPlugin's 1 500, or closer ({@code orbit}) — Kristallins at 1 330 did not aim nor follow
        // (live 2026-11-15 on gb2), so they never bunched
        double dist = orbitRadius + Math.max(-750, Math.min(750, orbitRadius - d));
        double speed = Math.max(200, h.speed());
        double step = (0.625 * speed + 125) / Math.max(1, dist);
        double na = a + orbitAngle * step;
        orbit.world.maps.MapCatalog.MapInfo m = ctx.maps().info(h.mapId());
        double x = Math.max(300, Math.min(m.width() - 300, cx + Math.cos(na) * dist));
        double y = Math.max(300, Math.min(m.height() - 300, cy + Math.sin(na) * dist));
        if (Math.hypot(x - (cx + Math.cos(na) * dist), y - (cy + Math.sin(na) * dist)) > 50) {
            orbitAngle = -orbitAngle; // the map's edge that way: the other way round
        }
        ctx.nav().moveTo(x, y, false);
        ctx.nav().tick();
    }

    private static double share(double v) {
        return v < 0 ? 1 : v; // no shield (the ship has none): not the reason to wait
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
