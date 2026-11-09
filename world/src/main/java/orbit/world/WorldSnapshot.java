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
                            java.util.Set<String> slotItems) {

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
     */
    public record Hero(int id, String name, String shipType, int mapId, int x, int y, boolean moving, int targetX,
                       int targetY, int speed, Health health, int factionId, int clanId, int level, double credits,
                       double uridium, double experience, double honor, int cargoFree, int cargoMax, boolean premium, boolean cloaked, boolean alive,
                       int selectedId, int attackTargetId, List<Integer> attackers, int petId, int config,
                       int enemyWarning, int minimapScale, Beacon beacon) {
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
    public record Inventory(Map<Integer, Double> ores, Map<String, Double> resources, Map<String, Integer> bootyKeys) {
    }

    /**
     * The hero's own P.E.T.
     *
     * @param owned   the account has a pet ({@code PetInitializationCommand.hasPet})
     * @param active  launched and on the map ({@code PetHeroActivationCommand} … {@code PetDeactivationCommand})
     * @param gear    selected mode ({@code PetGearTypeModule}, e.g. {@code PETGEARTYPE_GUARD}), -1 if unknown
     * @param fuel    current fuel, -1 if unknown
     * @param heat    overheating of the combo gears, -1 if unknown
     */
    public record Pet(boolean owned, boolean alive, boolean hasFuel, boolean active, int id, String name, int level,
                      Health health, int fuel, int fuelMax, double heat, double heatMax, int gear) {

        static final Pet NONE = new Pet(false, false, false, false, 0, "", 0, Health.UNKNOWN, -1, -1, -1, -1, -1);
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

    /** Station, turret, healing pod, battle station… ({@code AssetTypeModule} type). */
    public record Asset(int id, int type, String name, int x, int y, int factionId, int clanId, String clanTag,
                        boolean invisible) {
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

    public record Member(int id, String name, int level, int factionId, int mapId, int x, int y, Health health,
                         boolean loggedOut, boolean cloaked) {
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
