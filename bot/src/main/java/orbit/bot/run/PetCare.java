package orbit.bot.run;

import orbit.config.BotConfig;
import orbit.protocol.gen.PetGearTypeModule;
import orbit.protocol.gen.PetRequest;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The P.E.T., as the client does it (recorded on ru1 2026-11-11):
 * <ul>
 *   <li>launch ({@code PetRequest 0}) a moment after the login, a revive or a map change; the server then lists
 *       the pet's modes ({@code PetGearAddCommand}) and puts it in Passive;</li>
 *   <li>the config's mode ({@code PetGearActivationRequest}; the enemy locator with the NPC number as
 *       {@code optParam}). The mode works at once, but the server names it ({@code PetGearSelectCommand}) only
 *       12–32 s later, so a request is not repeated for want of an answer — only when the server names another
 *       mode after it (it puts Guard itself after the combo repair);</li>
 *   <li>the combo ship repair (mode 11) when the hero is low and nothing has hit it for a while: hit 0.2–8 s
 *       before, the server refused (Guard at once); 13.5 s after, it repaired for ~3.5 s, then set Guard;</li>
 *   <li>a destroyed pet is repaired ({@code PetRequest 4}) unless the config says not to;</li>
 *   <li>kamikaze (mode 10) while NPCs crowd round the pet — so many of them, or one flagged {@code KAMIKAZE} —
 *       the config's mode again when they are gone; after the pet blew up, not again for a while.</li>
 * </ul>
 */
public final class PetCare {

    private static final Logger log = LoggerFactory.getLogger(PetCare.class);

    /** Not hit this long before the combo repair (refused at 8.3 s, worked at 13.5 s). */
    static final long COMBO_QUIET_MS = 11_000;
    /** The combo repair again at most this often. */
    static final long COMBO_EVERY_MS = 20_000;
    static final long LAUNCH_RETRY_MS = 15_000;
    static final long REPAIR_RETRY_MS = 60_000;
    /** Buying fuel: again after this long when the game sent no fuel, and no more tries than this in a row. */
    static final long FUEL_RETRY_MS = 60_000;
    static final int FUEL_TRIES = 5;
    private long fuelAskedAt = Long.MIN_VALUE / 2;
    private int fuelTries;
    /** A mode asked again only this long after the last request. */
    static final long GEAR_RETRY_MS = 3_000;
    /** After this many requests the server keeps turning down within {@link #GEAR_BACKOFF_MS}: wait. */
    static final int GEAR_TRIES = 3;
    static final long GEAR_BACKOFF_MS = 60_000;

    private final BotContext ctx;
    private long launchAt;
    private long launchedAt = Long.MIN_VALUE / 2;
    private long repairedAt = Long.MIN_VALUE / 2;
    private int askedGear = -1;
    private long askedAt = Long.MIN_VALUE / 2;
    private long gearAt;
    private int tries;
    private long firstTryAt;
    private long comboAt = Long.MIN_VALUE / 2;
    private long hurtAt = Long.MIN_VALUE / 2;
    private double lastHp = -1;
    private String told = "";
    /** The mode last told missing (told once). */
    private int missingGear = -1;
    private boolean kamikazeOn;
    private long kamikazeSince;
    private long boomAt = Long.MIN_VALUE / 2;
    /** Kamikaze is kept this long after the crowd thinned, so a moving fight does not flip the mode. */
    static final long KAMIKAZE_HOLD_MS = 5_000;
    /** The kamikaze gear's button; its timer is the game's cooldown. */
    static final String KAMIKAZE_ITEM = "equipment_petgear_g-kk1";
    /**
     * The gear's cooldown by level in the client's item data (2026-11-04): 120, 60, 30 s; the pet blows up below
     * 20% of its HP, within a radius of 163/228/293. The shortest is waited also when the button shows no timer.
     */
    static final long KAMIKAZE_MIN_COOLDOWN_MS = 30_000;
    private long crowdSeenAt = Long.MIN_VALUE / 2;
    private boolean cooldownTold;
    /** Selling ore through the pet's trade pod ({@link CargoCare}): its mode while it lasts. */
    private volatile boolean trade;

    /** The trade pod wanted (true) or the config's mode again (false). */
    void setTrade(boolean on) {
        if (on != trade) {
            log.info(on ? "Питомец: включаю «Торговый модуль» — продаю руду" : "Питомец: продажа окончена, возвращаю {}",
                    gearName(ctx.config().get().pet().gear()));
        }
        trade = on;
    }

    /** Asks for the trade pod again at once (the server did not answer the first request). */
    void askTradeAgain() {
        askedGear = -1;
        askedAt = Long.MIN_VALUE / 2;
        gearAt = 0;
    }

    /** The pet is out with a trade pod: selling through it can work. */
    boolean canTrade() {
        WorldSnapshot.Pet p = ctx.world().get().pet();
        return ctx.config().get().pet().enabled() && p.active() && p.gears().contains(PetGearTypeModule.PETGEARTYPE_TRADE_POD);
    }

    public PetCare(BotContext ctx) {
        this.ctx = ctx;
    }

    /** A mode's name for the log: «Поиск врагов», not «Поиск врагов (Enemy Locator)». */
    static String gearName(String gear) {
        return "«" + orbit.config.ItemCatalog.label(gear).replaceFirst("\\s*\\(.*\\)$", "") + "»";
    }

    /** What the locator looks for, by the game's NPC number (13 = Cubikon): its name from the game's table. */
    public static String locatorTarget(int npc) {
        String name = orbit.bot.mod.KillModule.npcName(npc);
        return name != null ? name : "NPC №" + npc + " (в игре нет такого номера)";
    }

    /** The {@code PetGearTypeModule} number of a config name ({@code ENEMY_LOCATOR}…), -1 for none. */
    static int gearId(String name) {
        if (name == null) {
            return -1;
        }
        try {
            return PetGearTypeModule.class.getField("PETGEARTYPE_" + name).getInt(null);
        } catch (ReflectiveOperationException e) {
            return -1;
        }
    }

    /** Call every tick while the ship is alive. */
    public void tick() {
        BotConfig.Pet cfg = ctx.config().get().pet();
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.Pet pet = w.pet();
        long now = ctx.now();
        double hull = h.health().hp();
        if (lastHp >= 0 && hull < lastHp - 1) {
            hurtAt = now;
        }
        lastHp = hull;
        if (!cfg.enabled() || h.id() == 0 || !h.alive()) {
            return;
        }
        if (!pet.owned()) {
            tell("Питомца на аккаунте нет");
            return;
        }
        if (!pet.alive()) {
            if (kamikazeOn) {
                kamikazeOn = false;
                boomAt = now;
                cooldownTold = false;
                log.info("Питомец взорвался в режиме «Камикадзе»");
            }
            if (cfg.noRepair()) {
                tell("Питомец уничтожен — починка выключена в конфиге");
            } else if (ms(now - repairedAt) > REPAIR_RETRY_MS) {
                log.info("Питомец уничтожен — чиню{}", pet.repairCost() >= 0 ? " за " + pet.repairCost() : "");
                ctx.actions().pet(PetRequest.PETREQUESTTYPE_REPAIR_DESTROYED_PET);
                repairedAt = now;
            }
            return;
        }
        if (!pet.hasFuel()) {
            if (cfg.buyFuel()) {
                buyFuel(now);
            } else {
                tell("У питомца нет топлива");
            }
            return;
        }
        fuelTries = 0;
        if (Boolean.FALSE.equals(moduleOut.get())) {
            // AstraBot's petOnlyOnBehemoth: in until the behemoth is here
            launchAt = 0;
            if (pet.active() && ms(now - recalledAt) > LAUNCH_RETRY_MS) {
                if (!recallTold) {
                    log.info("Убираю питомца — он нужен только у бегемота");
                    recallTold = true;
                }
                ctx.actions().pet(PetRequest.PETREQUESTTYPE_DEACTIVATE);
                recalledAt = now;
            }
            return;
        }
        recallTold = false;
        if (!pet.active()) {
            // a moment after the login, a revive or a jump, like a player pressing the key
            if (launchAt == 0) {
                launchAt = now + ctx.pause(2_000, 5_000);
            }
            if (now >= launchAt && ms(now - launchedAt) > LAUNCH_RETRY_MS) {
                log.info("Выпускаю питомца");
                ctx.actions().pet(PetRequest.PETREQUESTTYPE_LAUNCH);
                launchedAt = now;
                launchAt = 0;
                askedGear = -1; // a launch puts it in Passive: the mode is asked for again
                gearAt = 0;
            }
            return;
        }
        launchAt = 0;
        told = "";
        if (!trade && combo(cfg, h, pet, now)) {
            return;
        }
        if (!trade) {
            kamikaze(cfg, w, pet, now);
        } else {
            kamikazeOn = false;
        }
        gear(cfg, pet, now);
    }

    /** Whether the kamikaze mode is wanted now; logs the switches. */
    private void kamikaze(BotConfig.Pet cfg, WorldSnapshot w, WorldSnapshot.Pet pet, long now) {
        BotConfig.Kamikaze k = cfg.kamikaze();
        // the game's own cooldown: the gear's button on the item bar (its timer), and the config's limit on top
        WorldSnapshot.Item button = w.items().get(KAMIKAZE_ITEM);
        if (button != null && button.timerMax() > 0 && button.timerNanos() > boomAt && !cooldownTold) {
            cooldownTold = true; // the game's own number, once after a blast (the client's data: 120/60/30 s by level)
            log.info("Камикадзе снова будет доступен через {} с — так считает игра", Math.round(button.timerMax() / 1000));
        }
        boolean ready = button == null || button.activatable() && button.remainingMs(now) == 0;
        boolean can = k.enabled() && pet.gears().contains(PetGearTypeModule.PETGEARTYPE_KAMIKAZE) && ready
                && ms(now - boomAt) >= Math.max(KAMIKAZE_MIN_COOLDOWN_MS, k.everyMinutes() * 60_000L);
        String crowd = can ? crowd(k, w, pet) : null;
        if (crowd != null) {
            crowdSeenAt = now;
        }
        boolean want = can && (crowd != null || kamikazeOn && ms(now - crowdSeenAt) < KAMIKAZE_HOLD_MS);
        if (want && !kamikazeOn) {
            log.info("Питомец: включаю «Камикадзе» — рядом {}", crowd);
            kamikazeSince = now;
        } else if (!want && kamikazeOn) {
            log.info("Питомец: «Камикадзе» больше не нужен — рядом нет целей, возвращаю {}", gearName(cfg.gear()));
        }
        kamikazeOn = want;
    }

    /**
     * NPCs round the pet (round the ship while the pet is not seen): enough of them, or a flagged one —
     * said for the log («Cubikon (флаг KAMIKAZE)», «5 NPC»), {@code null} when neither.
     */
    private String crowd(BotConfig.Kamikaze k, WorldSnapshot w, WorldSnapshot.Pet pet) {
        WorldSnapshot.Ship own = w.ships().get(pet.id());
        WorldSnapshot.Hero h = w.hero();
        int cx = own != null ? own.x() : h.x();
        int cy = own != null ? own.y() : h.y();
        BotConfig c = ctx.config().get();
        int n = 0;
        for (WorldSnapshot.Ship s : w.ships().values()) {
            if (s.kind() != WorldSnapshot.ShipKind.NPC || s.distanceTo(cx, cy) > k.radius()) {
                continue;
            }
            n++;
            if (k.flagged()) {
                BotConfig.NpcRule r = orbit.bot.mod.Targets.rule(c, s, h.mapId());
                if (r != null && r.has(BotConfig.NpcFlag.KAMIKAZE)) {
                    return s.name().replaceAll("^[^A-Za-zА-Яа-я]+|[^A-Za-zА-Яа-я]+$", "") + " (у него флаг KAMIKAZE)";
                }
            }
        }
        return k.minNpcs() > 0 && n >= k.minNpcs() ? n + " NPC (нужно от " + k.minNpcs() + ")" : null;
    }

    private boolean combo(BotConfig.Pet cfg, WorldSnapshot.Hero h, WorldSnapshot.Pet pet, long now) {
        int combo = PetGearTypeModule.PETGEARTYPE_COMBO_SHIP_REPAIR;
        if (cfg.comboRepairBelow() <= 0 || !pet.gears().contains(combo)) {
            return false;
        }
        if (askedGear == combo && ms(now - askedAt) < 5_000) {
            return true; // repairing: the server ends it with Guard after ~3.5 s
        }
        double hp = h.health().hpPercent();
        if (hp < 0 || hp >= cfg.comboRepairBelow() || !h.attackers().isEmpty() || ms(now - hurtAt) < COMBO_QUIET_MS
                || ms(now - comboAt) < COMBO_EVERY_MS) {
            return false;
        }
        log.info("Питомец: чиню корабль комбо-ремонтом — HP {}%, по кораблю не стреляют {} с",
                Math.round(hp * 100), ms(now - hurtAt) / 1000);
        ctx.actions().petGear(combo, 0);
        comboAt = now;
        askedGear = combo;
        askedAt = now;
        return true;
    }

    /**
     * An NPC the module wants the enemy locator on now ({@code BotModule#petLocator}: a quest's NPC), 0 none: it
     * takes the place of the config's mode while it lasts — never of the trade pod or the kamikaze.
     */
    private java.util.function.IntSupplier moduleLocator = () -> 0;
    private int askedParam;
    private boolean noLocatorTold;
    private int missingFightGear = -1;

    public void setModuleLocator(java.util.function.IntSupplier target) {
        moduleLocator = target;
    }

    /** A mode the module wants over the config's (HP Link while fighting an NPC flagged so), -1 for none. */
    private volatile java.util.function.IntSupplier moduleGear = () -> -1;

    /** The module's word on the pet being out: {@code null} = the config's, false = keep it in (the behemoth module). */
    private volatile java.util.function.Supplier<Boolean> moduleOut = () -> null;
    private long recalledAt = Long.MIN_VALUE / 2;
    private boolean recallTold;

    public void setModuleOut(java.util.function.Supplier<Boolean> out) {
        moduleOut = out;
    }

    /** A fight mode's name for the log. */
    static String fightName(int gear) {
        return gear == PetGearTypeModule.PETGEARTYPE_HP_LINK ? "«HP Link»"
                : gear == PetGearTypeModule.PETGEARTYPE_COMBAT_BEACON ? "«Боевой маяк»" : "№" + gear;
    }

    public void setModuleGear(java.util.function.IntSupplier gear) {
        moduleGear = gear;
    }

    private void gear(BotConfig.Pet cfg, WorldSnapshot.Pet pet, long now) {
        int quest = trade || kamikazeOn ? 0 : moduleLocator.getAsInt();
        boolean questLocator = quest > 0
                && (pet.gears().isEmpty() || pet.gears().contains(PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR));
        if (quest > 0 && !questLocator && !noLocatorTold) {
            noLocatorTold = true;
            log.warn("Питомец: у него нет «Пеленгатора врагов» — цель модуля (NPC № {}) ищу без него", quest);
        }
        int fightGear = trade || kamikazeOn ? -1 : moduleGear.getAsInt();
        boolean byFight = fightGear >= 0 && (pet.gears().isEmpty() || pet.gears().contains(fightGear));
        if (fightGear >= 0 && !byFight && missingFightGear != fightGear) {
            missingFightGear = fightGear; // once: the NPC's flag asks for a mode the pet does not have
            log.warn("Питомец: у него нет режима {} — в бою не включаю (режимы питомца: {})", fightName(fightGear),
                    pet.gears());
        }
        int want = trade ? PetGearTypeModule.PETGEARTYPE_TRADE_POD
                : kamikazeOn ? PetGearTypeModule.PETGEARTYPE_KAMIKAZE
                : byFight ? fightGear
                : questLocator ? PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR : gearId(cfg.gear());
        String label = trade ? "«Торговый модуль»" : kamikazeOn ? "«Камикадзе»"
                : byFight ? fightName(fightGear) + " (бой)"
                : questLocator ? "«Пеленгатор врагов» (для модуля)" : gearName(cfg.gear());
        if (want < 0) {
            return;
        }
        int param = want == PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR ? (questLocator ? quest : cfg.locatorTarget()) : 0;
        if (!pet.gears().isEmpty() && !pet.gears().contains(want)) {
            // once per missing mode (live 2026-11-11: the sell ship's pet, 1 939 lines in 6 minutes)
            if (missingGear != want) {
                missingGear = want;
                log.warn("У питомца нет режима {} — оставляю тот, что включён в игре", label);
            }
            return;
        }
        // the server's word after our request: another mode means it was turned down or changed (Guard)
        boolean overruled = pet.gearNanos() > askedAt && pet.gear() >= 0 && pet.gear() != want;
        if (askedGear == want && askedParam == param && !overruled) {
            return;
        }
        if (ms(now - askedAt) < GEAR_RETRY_MS) {
            return;
        }
        if (askedGear == want && tries >= GEAR_TRIES && ms(now - firstTryAt) < GEAR_BACKOFF_MS) {
            return;
        }
        if (gearAt == 0) {
            gearAt = now + ctx.pause(800, 2_000);
            return;
        }
        if (now < gearAt) {
            return;
        }
        gearAt = 0;
        if (askedGear == want && askedParam == param) {
            if (tries == 0 || ms(now - firstTryAt) >= GEAR_BACKOFF_MS) {
                tries = 0;
                firstTryAt = now;
            }
            tries++;
            if (tries == GEAR_TRIES) {
                log.warn("Питомец: игра не включает режим {} — попробую снова через минуту", label);
            }
        } else {
            tries = 0;
            firstTryAt = now;
        }
        if (trade) {
            log.info("Питомец: прошу режим «Торговый модуль»");
        } else if (kamikazeOn) {
            log.debug("Питомец: режим {}", label); // the reason is in the line before
        } else {
            log.info("Питомец: включаю режим {}{}", label, param != 0 ? " — ищет " + locatorTarget(param) : "");
        }
        ctx.actions().petGear(want, param);
        askedGear = want;
        askedParam = param;
        askedAt = now;
    }

    /**
     * Out of fuel and the config buys it (AstraBot's {@code buyFuel}): the game's own «buy fuel» key, as a player
     * presses it in the pet window; the game takes the price itself. Given up after {@link #FUEL_TRIES} tries with no
     * fuel coming (no money, or the game refused).
     */
    private void buyFuel(long now) {
        if (fuelTries >= FUEL_TRIES) {
            tell("У питомца нет топлива — купить не вышло (" + FUEL_TRIES + " попыток), проверьте баланс");
            return;
        }
        if (ms(now - fuelAskedAt) < FUEL_RETRY_MS) {
            return;
        }
        fuelTries++;
        fuelAskedAt = now;
        log.info("У питомца нет топлива — покупаю (попытка {} из {})", fuelTries, FUEL_TRIES);
        ctx.actions().pet(PetRequest.PETREQUESTTYPE_HOTKEY_BUY_FUEL);
    }

    private void tell(String s) {
        if (!s.equals(told)) {
            told = s;
            log.warn(s);
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
