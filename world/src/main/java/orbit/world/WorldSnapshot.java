package orbit.world;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Immutable view of the map around the hero at one moment ({@code nanos}, {@code System.nanoTime()}).
 * Positions of moving objects are interpolated to that moment.
 */
public record WorldSnapshot(long nanos, Hero hero, Map<Integer, Ship> ships, Map<String, Collectable> collectables,
                            Map<Integer, Portal> portals, Map<Integer, Asset> assets, Map<String, Zone> zones,
                            Group group) {

    /** The player's own ship. {@code id == 0} until {@code ShipInitializationCommand} arrives. */
    public record Hero(int id, String name, String shipType, int mapId, int x, int y, boolean moving, int targetX,
                       int targetY, int speed, Health health, int factionId, int clanId, int level, double credits,
                       double uridium, int cargoLeft, boolean premium, boolean cloaked, boolean alive,
                       int selectedId, int petId, Beacon beacon) {
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
     */
    public record Ship(int id, ShipKind kind, String name, String typeId, int factionId, int clanId, String clanTag,
                       int relation, boolean cloaked, int x, int y, boolean moving, int targetX, int targetY,
                       Health health, int attackTargetId, int ownerId) {

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

    /** Ships of the given kind sorted by distance to the hero, nearest first. */
    public List<Ship> nearest(ShipKind kind) {
        return ships.values().stream().filter(s -> s.kind() == kind)
                .sorted(Comparator.comparingDouble(s -> s.distanceTo(hero.x(), hero.y()))).toList();
    }
}
