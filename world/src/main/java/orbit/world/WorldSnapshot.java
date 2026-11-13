package orbit.world;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the map around the hero at one moment ({@code nanos}, {@code System.nanoTime()}).
 * Positions of moving objects are interpolated to that moment.
 *
 * <p>{@code activatable}: map assets (portals, stations…) whose action the server offers now
 * ({@code MapAssetActionAvailableCommand}), id → since when; a portal becomes jumpable about
 * 300–500 units away. {@code jumpingTo}: map of a jump the server started, 0 if none.
 * {@code slotItems}: items on the slot bars (used with a key; others are clicked in the item bar).
 */
public record WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                            Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                            Group group, Map<String, Item> items, Inventory inventory, Pet pet,
                            Map<Integer, Long> activatable, int jumpingTo, Death death,
                            java.util.Set<String> slotItems, Map<Integer, Kill> destroyed, Rewards rewards,
                            List<Invitation> invitations, List<Contact> contacts, Map<String, Mine> mines,
                            Boosters boosters, JumpCpu jumpCpu, BoxCheck boxCheck, Quests quests) {

    /** Without quests (older callers and tests). */
    public WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                         Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                         Group group, Map<String, Item> items, Inventory inventory, Pet pet,
                         Map<Integer, Long> activatable, int jumpingTo, Death death,
                         java.util.Set<String> slotItems, Map<Integer, Kill> destroyed, Rewards rewards,
                         List<Invitation> invitations, List<Contact> contacts, Map<String, Mine> mines,
                         Boosters boosters, JumpCpu jumpCpu, BoxCheck boxCheck) {
        this(nanos, hero, ships, collectables, portals, assets, zones, group, items, inventory, pet, activatable, jumpingTo,
                death, slotItems, destroyed, rewards, invitations, contacts, mines, boosters, jumpCpu, boxCheck, Quests.NONE);
    }

    /**
     * The game's box check ({@code CaptchaTriggerCommand}, live 2026-11-12 on gb2): pick some or all of the
     * black or red boxes laid round the ship within the time; the ship is held inside the circle meanwhile. Solved —
     * {@code CaptchaResolvedCommand}, failed — {@code CaptchaExplodeCommand}. {@code null} when there is none.
     *
     * @param type    {@code CaptchaTriggerCommand.ECAPTCHATYPE_*}: some black, all black, some red, all red
     * @param amount  how many to pick (the «some» kinds)
     * @param seconds the time given
     * @param black   black boxes laid out (indexes 0…black−1), {@code red} red ones (the indexes after them)
     * @param boxes   the boxes as the client lays them out ({@code BoxCaptchaTrait.addBoxes}); the server only
     *                hears the index of each one picked ({@code CaptchaCollectRequest})
     */
    public record BoxCheck(int type, int amount, int seconds, int x, int y, int radius, int black, int red, long sinceNanos,
                           List<CheckBox> boxes) {

        public BoxCheck(int type, int amount, int seconds, int x, int y, int radius, int black, int red, long sinceNanos) {
            this(type, amount, seconds, x, y, radius, black, red, sinceNanos, List.of());
        }

        /** One box of the check: its index for the server, its colour, where the client put it, picked or not. */
        public record CheckBox(int index, boolean red, int x, int y, boolean picked) {
        }

        /** «All» kinds (types 1, 3). */
        public boolean all() {
            return type == 1 || type == 3;
        }

        /** How many boxes of the asked colour are still to pick (0 = done, waiting for the server). */
        public int left() {
            long picked = boxes.stream().filter(b -> b.picked() && b.red() == pickRed()).count();
            int want = all() ? (pickRed() ? red : black) : amount;
            return (int) Math.max(0, want - picked);
        }

        /** The boxes of the asked colour not picked yet. */
        public List<CheckBox> targets() {
            return boxes.stream().filter(b -> !b.picked() && b.red() == pickRed()).toList();
        }

        /** Red boxes to pick (types 2, 3), else black ones. */
        public boolean pickRed() {
            return type >= 2;
        }

        /** Seconds left, never below 0. */
        public long secondsLeft(long nowNanos) {
            return Math.max(0, seconds - (nowNanos - sinceNanos) / 1_000_000_000L);
        }

        /** «соберите 3 чёрных ящика за 40 с» / «соберите все красные ящики». */
        public String task() {
            String colour = pickRed() ? "красн" : "чёрн";
            if (all()) {
                return "соберите все " + colour + "ые ящики (" + (pickRed() ? red : black) + ")";
            }
            return "соберите " + amount + " " + colour + (amount % 10 == 1 && amount % 100 != 11 ? "ый ящик" : "ых ящика")
                    + " из " + (pickRed() ? red : black);
        }

        /** «ПРОВЕРКА: соберите 3 чёрных ящика из 5, осталось 40 с» */
        public String text(long nowNanos) {
            return "ПРОВЕРКА: " + task() + (seconds > 0 ? ", осталось " + secondsLeft(nowNanos) + " с" : "");
        }
    }

    /**
     * The jump CPU (live 2026-11-11, ru1): {@code CPUInitializationCommand.advancedJumpCPUEquipped}; vouchers
     * {@code 0|A|JV|n}; the price table {@code 0|A|JCPU|I|<price>|<maps;>|…} (uridium, -1 = not there) after
     * {@code JCPU|GET}; {@code 0|A|JCPU|C|<map>|<price>|…} confirms a chosen map; {@code 0|A|JCPU|S|<s>|<map>}
     * starts the countdown. The server takes a voucher while there are any, uridium after.
     *
     * @param vouchers     jump vouchers, -1 before the server says
     * @param prices       map → uridium price; -1 = not reachable by jump
     * @param confirmedMap the map the server confirmed for the jump, 0 = none (with its price and time)
     * @param countdownMap the map the countdown runs to, 0 = none (with its seconds and start)
     */
    public record JumpCpu(boolean equipped, int vouchers, Map<Integer, Integer> prices, long pricesNanos, int confirmedMap,
                          int confirmedPrice, long confirmedNanos, int countdownMap, int countdownSeconds,
                          long countdownNanos) {

        public static final JumpCpu NONE = new JumpCpu(false, -1, Map.of(), 0, 0, 0, 0, 0, 0, 0);
    }

    /**
     * Boosters ({@code AttributeBoosterUpdateCommand}): the active ones by loot id ({@code booster_dmg-b01})
     * and the total bonus per attribute ({@code DAMAGE} → 13 %). {@code known} once the server has sent them.
     */
    public record Boosters(boolean known, Map<String, Booster> active, Map<String, Double> totals) {
        public static final Boosters NONE = new Boosters(false, Map.of(), Map.of());
    }

    /**
     * One booster: the player's own ones have a time left ({@code secondsLeft} counted from {@code nanos}),
     * equipment ones an amount (lasers that boost), shared ones a count.
     */
    public record Booster(String lootId, String kind, int secondsLeft, long nanos, int amount) {
        /** Seconds left at a moment; -1 for boosters without a time (equipment, shared, station). */
        public long secondsLeftAt(long now) {
            return secondsLeft < 0 ? -1 : Math.max(0, secondsLeft - (now - nanos) / 1_000_000_000L);
        }
    }

    /** A mine on the map ({@code AddMineCommand}); the client knows its type, not whose it is. */
    public record Mine(String hash, int typeId, int x, int y) {
    }

    /** An entry of the contact list ({@code ContactListInitializationCommand} and its updates). */
    public record Contact(int id, String name, String clanTag, boolean online, boolean friend, boolean clanMember) {
    }

    /**
     * A group invitation ({@code InvitationInitializationCommand}): {@code incoming} when the hero is the
     * candidate, else one the hero (or the group) sent.
     */
    public record Invitation(int inviterId, String inviterName, int candidateId, String candidateName, boolean incoming) {
    }

    /**
     * What the server's reward log ({@code 0|LM|ST|…}) has granted since the world model started:
     * currencies and points, and items by id (resources, ammunition, rockets, mines, extra energy, log disks).
     */
    public record Rewards(double credits, double uridium, double honor, double experience, Map<String, Double> items) {

        public static final Rewards NONE = new Rewards(0, 0, 0, 0, Map.of());
    }

    /**
     * A ship the server reported destroyed ({@code ShipDestroyedCommand}) in the last
     * {@code World.KILL_MEMORY_NANOS}: unlike a ship that just left the view, it is really dead.
     */
    public record Kill(int id, String name, int x, int y, long nanos) {
    }

    /** The player's own ship. {@code id == 0} until {@code ShipInitializationCommand} arrives. */
    /**
     * The player's own ship. {@code id == 0} until {@code ShipInitializationCommand} arrives.
     *
     * @param cargoFree    free cargo space ({@code ShipInitializationCommand.cargoSpace}: it grows when ore is sold)
     * @param config       selected configuration (1 or 2), 0 if unknown
     * @param enemyWarning number of enemy players the client warns about ({@code 0|n|w}), -1 if unknown
     * @param selectedId     ship selected as target (may be selected without shooting), 0 if none
     * @param attackTargetId ship the hero shoots lasers at ({@code AttackLaserRunCommand}), 0 if none
     * @param attackers      ships shooting at the hero now or that hit it in the last 5 seconds
     * @param minimapScale   the client's minimap zoom; a minimap click lands on a grid of
     *                       {@code 10 × minimapScale × map scale} units ({@code MiniMap.handleMouseDown})
     * @param formation      drone formation id ({@code DroneFormationChangeCommand}), -1 before the server says;
     *                       {@code f-11-he} is 11 (seen in recordings)
     * @param formationNanos when the server last reported it
     */
    public record Hero(int id, String name, String shipType, int mapId, int x, int y, boolean moving, int targetX,
                       int targetY, int speed, Health health, int factionId, int clanId, int level, double credits,
                       double uridium, double experience, double honor, int cargoFree, int cargoMax, boolean premium, boolean cloaked, boolean alive,
                       int selectedId, int attackTargetId, List<Integer> attackers, int petId, int config,
                       int enemyWarning, int minimapScale, Beacon beacon, int formation, long formationNanos) {
    }

    /**
     * An entry of the item bar: ammunition, rockets, mines, CPUs, techs, abilities…
     *
     * @param category    {@code MenuInitializationCommand} category, e.g. {@code lasers}, {@code rockets}
     * @param counter     amount (ammunition) or charges
     * @param timerState    {@code TimerStateModule} value (ready, active, cooling down)
     * @param timerTime     remaining milliseconds when received (the client feeds it to a flash {@code Timer})
     * @param timerNanos    when the timer was received
     * @param cooldownGroup {@code CooldownTypeModule} value shared with other items, -1 if none;
     *                      {@code MenuItemCooldownGroupTimerCommand} restarts the timer of the whole group
     */
    public record Item(String id, String category, double counter, double maxCounter, boolean selected,
                       boolean available, boolean activatable, boolean buyable, boolean blocked, boolean visible,
                       int timerState, double timerTime, double timerMax, long timerNanos, int cooldownGroup) {

        /** Milliseconds until the item is ready again (active effect or cooldown), 0 when ready. */
        public long remainingMs(long nowNanos) {
            if (timerState == orbit.protocol.gen.TimerStateModule.TIMERSTATE_READY || timerTime <= 0) {
                return 0;
            }
            return Math.max(0, (long) timerTime - (nowNanos - timerNanos) / 1_000_000);
        }
    }

    /**
     * What the hero carries outside the item bar.
     *
     * @param ores       ore type ({@code OreTypeModule}) → amount, from {@code AttributeOreCountUpdateCommand}
     * @param resources  loot id → amount, from {@code InventoryItemUpdatedCommand}
     * @param bootyKeys  legacy key code ({@code BK}, {@code BKB}, …) → count
     */
    /**
     * @param tradePrices ore → price from the last trade window ({@code TradeReplyCommand}: the ores it buys), with
     * @param tradeNanos  when it came (0 never)
     * @param petTradeNanos when the pet's trade pod opened the trade window ({@code PetTradeWindowShowCommand})
     */
    public record Inventory(Map<Integer, Double> ores, Map<String, Double> resources, Map<String, Integer> bootyKeys,
                            Map<Integer, Integer> tradePrices, long tradeNanos, long petTradeNanos) {

        /** Xenomit does not take room in the hold (cargoSpace = max − the other ores, both recordings agree). */
        public static final int XENOMIT = 3;

        /** Ore in the hold that takes room. */
        public long cargoUsed() {
            return Math.round(ores.entrySet().stream().filter(e -> e.getKey() != XENOMIT).mapToDouble(Map.Entry::getValue).sum());
        }
    }

    /** Free room in the hold now: the capacity less the ore in it (-1 before the game said). */
    public long cargoFree() {
        return hero.cargoMax() < 0 || inventory.ores().isEmpty() ? hero.cargoFree() : hero.cargoMax() - inventory.cargoUsed();
    }

    /**
     * The hero's own P.E.T.
     *
     * @param owned   the account has a pet ({@code PetInitializationCommand.hasPet})
     * @param active  launched and on the map ({@code PetHeroActivationCommand} … {@code PetDeactivationCommand})
     * @param gear    selected mode ({@code PetGearTypeModule}, e.g. {@code PETGEARTYPE_GUARD}), -1 if unknown
     * @param fuel    current fuel, -1 if unknown
     * @param heat    overheating of the combo gears, -1 if unknown
     * @param gearNanos when the server last named the mode ({@code PetGearSelectCommand}; it comes 12–32 s late
     *                  after a change, at once when the server sets Guard itself), 0 never
     * @param gears   the modes the pet has ({@code PetGearAddCommand} after a launch)
     * @param repairCost what repairing the destroyed pet costs ({@code PetUIRepairButtonCommand}), -1 unknown or
     *                   not offered
     * @param comboRepair the combo ship repair is working now ({@code PetBuffCommand} effect 6)
     * @param locatorX where the enemy locator last pointed ({@code PetGearLocatorUpdate}: its target, every ~2 s
     *                 while it finds one; recorded 2026-11-11, the points led to the Cubikon), with
     * @param locatorNanos when — 0 none on this map
     * @param locatorNoneNanos when the locator last said «nothing» (its point in the map's corner, -1/-1): its target is
     *                         not on this map now; 0 never
     */
    public record Pet(boolean owned, boolean alive, boolean hasFuel, boolean active, int id, String name, int level,
                      Health health, int fuel, int fuelMax, double heat, double heatMax, int gear, long gearNanos,
                      java.util.Set<Integer> gears, int repairCost, boolean comboRepair, int locatorX, int locatorY,
                      long locatorNanos, long locatorNoneNanos) {

        static final Pet NONE = new Pet(false, false, false, false, 0, "", 0, Health.UNKNOWN, -1, -1, -1, -1, -1, 0,
                java.util.Set.of(), -1, false, 0, 0, 0, 0);

        /** The locator's point is this fresh (ms) or newer. */
        public boolean locatorWithin(long nowNanos, long ms) {
            return locatorNanos != 0 && nowNanos - locatorNanos <= ms * 1_000_000;
        }

        /** The locator said «nothing» this fresh (ms) or newer, and found nothing since. */
        public boolean locatorNoneWithin(long nowNanos, long ms) {
            return locatorNoneNanos != 0 && locatorNoneNanos >= locatorNanos && nowNanos - locatorNoneNanos <= ms * 1_000_000;
        }

        public Pet {
            gears = gears == null ? java.util.Set.of() : java.util.Set.copyOf(gears);
        }
    }

    /**
     * The kill screen ({@code KillScreenPostCommand}): who destroyed the hero and how it can be
     * repaired. {@code null} while the ship is alive.
     */
    public record Death(String killer, int cause, List<RepairOption> options) {

        /** The first option that costs nothing and is available now, or {@code null}. */
        public RepairOption freeRepair() {
            return options.stream().filter(o -> o.affordable() && o.price() == 0 && o.cooldownSeconds() == 0)
                    .findFirst().orElse(null);
        }
    }

    /**
     * One way to come back ({@code KillScreenOptionModule}).
     *
     * @param type      {@code KillScreenOptionTypeModule} value, e.g. {@code OPTIONTYPE_BASIC_REPAIR}
     * @param priceType {@code PriceModule.type} (currency), {@code price} its amount
     */
    public record RepairOption(int type, int priceType, int price, boolean affordable, int cooldownSeconds, String button) {
    }

    /** Area flags from the last {@code BeaconCommand} (sent about once a second). */
    public record Beacon(boolean inRadiationZone, boolean inStation, boolean inPeaceArea, boolean nearJumpGate,
                         boolean repairRobotActive) {

        static final Beacon NONE = new Beacon(false, false, false, false, false);
    }

    public enum ShipKind { PLAYER, NPC, PET }

    /**
     * Another ship, NPC or pet.
     *
     * @param relation        {@code ClanRelationModule} type ({@code TYPE_ALLIED}, {@code TYPE_AT_WAR}, …)
     * @param attackTargetId  id it is shooting lasers at, or 0
     * @param ownerId         owner of a pet, 0 otherwise
     * @param lockedByOther   another player (not in the group) attacks it; the client shows it grey
     */
    public record Ship(int id, ShipKind kind, String name, String typeId, int factionId, int clanId, String clanTag,
                       int relation, boolean cloaked, int x, int y, boolean moving, int targetX, int targetY,
                       Health health, int attackTargetId, int ownerId, boolean lockedByOther) {

        public boolean npc() {
            return kind == ShipKind.NPC;
        }

        public double distanceTo(int px, int py) {
            return Math.hypot(x - px, y - py);
        }
    }

    public enum CollectableKind { BOX, ORE }

    /**
     * A box or an ore field.
     *
     * @param boxType  box type name for boxes (e.g. {@code BONUS_BOX}), empty for ores
     * @param oreType  {@code OreTypeModule} value for ores, -1 for boxes
     */
    public record Collectable(String hash, CollectableKind kind, String boxType, int oreType, int x, int y) {
    }

    public record Portal(int id, int x, int y, int factionId, int designId, boolean visibleOnMinimap) {
    }

    /**
     * Station, turret, healing pod, battle station… ({@code AssetTypeModule} type).
     *
     * @param clanRelation the owner clan to the hero's ({@code ClanRelationModule}: 0 none, 1 allied, 2 non-aggression
     *                     pact, 3 at war)
     */
    public record Asset(int id, int type, String name, int x, int y, int factionId, int clanId, String clanTag,
                        boolean invisible, int clanRelation) {

        public Asset(int id, int type, String name, int x, int y, int factionId, int clanId, String clanTag,
                     boolean invisible) {
            this(id, type, name, x, y, factionId, clanId, clanTag, invisible, 0);
        }

        /** The client shows it friendly: the owner clan is allied or has a non-aggression pact with the hero's. */
        public boolean friendlyClan() {
            return clanRelation == 1 || clanRelation == 2;
        }
    }

    /**
     * Point of interest: radiation, no-access, healing and other zones.
     *
     * @param type   {@code POITypeModule} value
     * @param shape  {@code MapAddPOICommand.SHAPETYPE_CIRCLE} (coords x, y, radius), {@code SHAPETYPE_POLYGON}
     *               or {@code SHAPETYPE_RECTANGLE} (coords as x/y pairs), as the client's {@code MapAddPOIHandler} reads them
     * @param inverted the area outside the shape is the zone (e.g. the radiation border of a map)
     */
    public record Zone(String id, int type, int shape, List<Integer> coords, boolean inverted, boolean active) {
    }

    /**
     * A group member as the group packets tell him; {@code targetName}/{@code targetHealth} — what he has selected
     * ({@code TargetModule}: the client shows it in the group window), "" and {@link Health#UNKNOWN} when nothing.
     */
    public record Member(int id, String name, int level, int factionId, int mapId, int x, int y, Health health,
                         boolean loggedOut, boolean cloaked, String targetName, Health targetHealth) {

        public Member(int id, String name, int level, int factionId, int mapId, int x, int y, Health health,
                      boolean loggedOut, boolean cloaked) {
            this(id, name, level, factionId, mapId, x, y, health, loggedOut, cloaked, "", Health.UNKNOWN);
        }

        Member with(Health h) {
            return new Member(id, name, level, factionId, mapId, x, y, h, loggedOut, cloaked, targetName, targetHealth);
        }

        Member withTarget(String n, Health h) {
            return new Member(id, name, level, factionId, mapId, x, y, health, loggedOut, cloaked, n, h);
        }

        Member at(int map, int nx, int ny) {
            return new Member(id, name, level, factionId, map, nx, ny, health, loggedOut, cloaked, targetName, targetHealth);
        }

        Member level(int l) {
            return new Member(id, name, l, factionId, mapId, x, y, health, loggedOut, cloaked, targetName, targetHealth);
        }

        Member out(boolean o) {
            return new Member(id, name, level, factionId, mapId, x, y, health, o, cloaked, targetName, targetHealth);
        }

        Member cloak(boolean c) {
            return new Member(id, name, level, factionId, mapId, x, y, health, loggedOut, c, targetName, targetHealth);
        }
    }

    public record Group(int id, int leaderId, List<Member> members) {
        static final Group NONE = new Group(0, 0, List.of());

        public boolean exists() {
            return id != 0;
        }
    }

    /** The selected item of a category (e.g. the laser ammunition in use), or {@code null}. */
    public Item selected(String category) {
        return items.values().stream().filter(i -> i.selected() && category.equals(i.category())).findFirst().orElse(null);
    }

    /** Ships of the given kind sorted by distance to the hero, nearest first. */
    public List<Ship> nearest(ShipKind kind) {
        return ships.values().stream().filter(s -> s.kind() == kind)
                .sorted(Comparator.comparingDouble(s -> s.distanceTo(hero.x(), hero.y()))).toList();
    }
}
