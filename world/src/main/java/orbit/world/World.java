package orbit.world;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import orbit.protocol.Packet;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.AddOreCommand;
import orbit.protocol.gen.AssetCreateCommand;
import orbit.protocol.gen.AssetRemoveCommand;
import orbit.protocol.gen.AttackAbortLaserCommand;
import orbit.protocol.gen.AttackHitCommand;
import orbit.protocol.gen.AttackLaserRunCommand;
import orbit.protocol.gen.AttributeCreditsUpdateCommand;
import orbit.protocol.gen.AttributeHitpointUpdateCommand;
import orbit.protocol.gen.AttributeLevelUpUpdateCommand;
import orbit.protocol.gen.AttributeOreCountUpdateCommand;
import orbit.protocol.gen.AttributeShieldUpdateCommand;
import orbit.protocol.gen.AttributeShipSpeedUpdateCommand;
import orbit.protocol.gen.BeaconCommand;
import orbit.protocol.gen.CloakDeviceActivityModule;
import orbit.protocol.gen.GroupCloseCommand;
import orbit.protocol.gen.GroupInitializationCommand;
import orbit.protocol.gen.GroupMemberInitializationCommand;
import orbit.protocol.gen.GroupMemberUpdateCommand;
import orbit.protocol.gen.HealthModule;
import orbit.protocol.gen.HitpointInfoCommand;
import orbit.protocol.gen.InventoryItemUpdatedCommand;
import orbit.protocol.gen.JumpgateCreateCommand;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.LevelModule;
import orbit.protocol.gen.LocationModule;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.MapRemovePOICommand;
import orbit.protocol.gen.MemberLoggedOutModule;
import orbit.protocol.gen.MenuCategoryInitializationCommand;
import orbit.protocol.gen.MenuInitializationCommand;
import orbit.protocol.gen.MenuItemInitializationCommand;
import orbit.protocol.gen.MenuItemStatusCommand;
import orbit.protocol.gen.MenuItemTimerCommand;
import orbit.protocol.gen.MoveCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.OreCountModule;
import orbit.protocol.gen.PetActivationCommand;
import orbit.protocol.gen.PetDeactivationCommand;
import orbit.protocol.gen.PetHeroActivationCommand;
import orbit.protocol.gen.PlayerAttributeModule;
import orbit.protocol.gen.ReloginCommand;
import orbit.protocol.gen.RemoveCollectableCommand;
import orbit.protocol.gen.ShipCreateCommand;
import orbit.protocol.gen.ShipDeselectionCommand;
import orbit.protocol.gen.ShipDestroyedCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.ShipRemoveCommand;
import orbit.protocol.gen.ShipSelectionCommand;
import orbit.protocol.gen.TimerStateModule;
import orbit.world.WorldSnapshot.Asset;
import orbit.world.WorldSnapshot.Beacon;
import orbit.world.WorldSnapshot.Collectable;
import orbit.world.WorldSnapshot.CollectableKind;
import orbit.world.WorldSnapshot.Group;
import orbit.world.WorldSnapshot.Member;
import orbit.world.WorldSnapshot.Portal;
import orbit.world.WorldSnapshot.ShipKind;
import orbit.world.WorldSnapshot.Zone;

/**
 * State of the map as the client knows it, built from server packets ({@link #apply}) and from
 * the hero's own movement requests ({@link #applyOutgoing}): the server does not send
 * {@code MoveCommand} for the hero, the client moves it and the server confirms the position
 * about once a second in {@code BeaconCommand}.
 *
 * <p>Thread-safe: one thread feeds packets, any thread may take {@link #snapshot}s.
 */
public final class World {

    private static final class ShipState {
        final int id;
        ShipKind kind;
        String name = "";
        String typeId = "";
        int factionId;
        int clanId;
        String clanTag = "";
        int relation;
        boolean cloaked;
        int ownerId;
        Motion motion;
        Health health = Health.UNKNOWN;
        int attackTargetId;
        boolean lockedByOther;

        ShipState(int id, ShipKind kind, Motion motion) {
            this.id = id;
            this.kind = kind;
            this.motion = motion;
        }
    }

    private static final class HeroState {
        int id;
        String name = "";
        String shipType = "";
        int mapId;
        Motion motion = Motion.at(0, 0, 0);
        int speed;
        Health health = Health.UNKNOWN;
        int factionId;
        int clanId;
        int level;
        double credits;
        double uridium;
        int cargoFree = -1;
        int cargoMax = -1;
        int config;
        int enemyWarning = -1;
        boolean premium;
        boolean cloaked;
        boolean alive = true;
        int selectedId;
        int petId;
        Beacon beacon = Beacon.NONE;
    }

    /** Distance between the predicted and the reported hero position that counts as a jump. */
    static final double BEACON_JUMP = 1_000;

    private final HeroState hero = new HeroState();
    private final Map<Integer, ShipState> ships = new HashMap<>();
    private final Map<String, Collectable> collectables = new LinkedHashMap<>();
    private final Map<Integer, Portal> portals = new LinkedHashMap<>();
    private final Map<Integer, Asset> assets = new LinkedHashMap<>();
    private final Map<String, Zone> zones = new LinkedHashMap<>();
    private Group group = Group.NONE;
    private final Map<String, WorldSnapshot.Item> items = new LinkedHashMap<>();
    private final Map<Integer, Double> ores = new LinkedHashMap<>();
    private final Map<String, Double> resources = new LinkedHashMap<>();
    private final Map<String, Integer> bootyKeys = new LinkedHashMap<>();

    /** Applies a packet received from the server at {@code nanos} ({@code System.nanoTime()}). */
    public synchronized void apply(Packet packet, long nanos) {
        switch (packet) {
            case ShipInitializationCommand p -> initHero(p, nanos);
            case ReloginCommand p -> clearMap();
            case BeaconCommand p -> beacon(p, nanos);
            case ShipCreateCommand p -> createShip(p, nanos);
            case MoveCommand p -> moveShip(p, nanos);
            case ShipRemoveCommand p -> removeShip(p.userId);
            case ShipDestroyedCommand p -> destroyed(p.destroyedUserId);
            case PetHeroActivationCommand p -> activatePet(p.petId, p.ownerId, p.petName, p.factionId, p.clanId,
                    p.clanTag, 0, p.x, p.y, nanos);
            case PetActivationCommand p -> activatePet(p.petId, p.ownerId, p.petName, p.petFactionId, p.petClanID,
                    p.clanTag, p.clanRelationship != null ? p.clanRelationship.type : 0, p.x, p.y, nanos);
            case PetDeactivationCommand p -> {
                removeShip(p.petId);
                if (hero.petId == p.petId) {
                    hero.petId = 0;
                }
            }
            case AddBoxCommand p -> collectables.put(p.hash,
                    new Collectable(p.hash, CollectableKind.BOX, p.boxType, -1, p.x, p.y));
            case AddOreCommand p -> collectables.put(p.hash, new Collectable(p.hash, CollectableKind.ORE, "",
                    p.oreType != null ? p.oreType.typeValue : -1, p.x, p.y));
            case RemoveCollectableCommand p -> collectables.remove(p.hash);
            case JumpgateCreateCommand p -> portals.put(p.gateId,
                    new Portal(p.gateId, p.x, p.y, p.factionId, p.designId, p.isVisibleOnMinimap));
            case AssetCreateCommand p -> assets.put(p.assetId, new Asset(p.assetId,
                    p.type != null ? p.type.typeValue : -1, p.userName, p.posX, p.posY, p.factionId, p.clanId,
                    p.clanTag, p.invisible));
            case AssetRemoveCommand p -> assets.remove(p.uid);
            case MapAddPOICommand p -> zones.put(p.poiId, new Zone(p.poiId, p.poiType != null ? p.poiType.typeValue : -1,
                    p.shape, List.copyOf(p.shapeCoordinates), p.inverted, p.active));
            case MapRemovePOICommand p -> zones.remove(p.poiId);
            case AttributeHitpointUpdateCommand p -> hero.health = hero.health.withHp(p.hitpointsNow, p.hitpointsMax);
            case AttributeShieldUpdateCommand p -> hero.health = hero.health.withShield(p.shieldNow, p.shieldMax);
            case HitpointInfoCommand p -> hero.health = hero.health.withHp(p.hitpoints, p.hitpointsMax)
                    .withNanoHull(p.nanoHull, p.nanoHullMax);
            case AttributeShipSpeedUpdateCommand p -> setHeroSpeed(p.newSpeed, nanos);
            case AttributeCreditsUpdateCommand p -> {
                hero.credits = p.credits;
                hero.uridium = p.uridium;
            }
            case AttributeLevelUpUpdateCommand p -> hero.level = p.level;
            case ShipSelectionCommand p -> select(p);
            case ShipDeselectionCommand p -> hero.selectedId = 0;
            case AttackLaserRunCommand p -> {
                ShipState s = ships.get(p.attackerId);
                if (s != null) {
                    s.attackTargetId = p.targetId;
                }
            }
            case AttackAbortLaserCommand p -> {
                ShipState s = ships.get(p.uid);
                if (s != null) {
                    s.attackTargetId = 0;
                }
            }
            case AttackHitCommand p -> hit(p);
            case GroupInitializationCommand p -> initGroup(p);
            case GroupMemberUpdateCommand p -> updateGroupMember(p);
            case GroupCloseCommand p -> group = Group.NONE;
            case MenuInitializationCommand p -> initItems(p, nanos);
            case MenuItemStatusCommand p -> updateItem(p);
            case MenuItemTimerCommand p -> updateTimer(p, nanos);
            case AttributeOreCountUpdateCommand p -> {
                ores.clear();
                for (OreCountModule o : p.oreCountList) {
                    ores.put(o.oreType != null ? (int) o.oreType.typeValue : -1, o.count);
                }
            }
            case InventoryItemUpdatedCommand p -> {
                if (p.item != null) {
                    resources.put(p.item.lootId, p.item.amount);
                }
            }
            case LegacyModule p -> legacy(p.message, nanos);
            default -> {
                // not part of the world model
            }
        }
    }

    /** Applies a packet the bot sent at {@code nanos}; only the hero's movement matters. */
    public synchronized void applyOutgoing(Packet packet, long nanos) {
        if (packet instanceof MoveRequest m) {
            // the client starts from where it shows the ship, which is what it reports as position
            hero.motion = Motion.fromAtSpeed(m.positionX, m.positionY, m.targetX, m.targetY, hero.speed, nanos);
        }
    }

    public synchronized WorldSnapshot snapshot(long nanos) {
        Motion m = hero.motion;
        WorldSnapshot.Hero h = new WorldSnapshot.Hero(hero.id, hero.name, hero.shipType, hero.mapId,
                (int) Math.round(m.x(nanos)), (int) Math.round(m.y(nanos)), m.moving(nanos),
                (int) Math.round(m.toX()), (int) Math.round(m.toY()), hero.speed, hero.health, hero.factionId,
                hero.clanId, hero.level, hero.credits, hero.uridium, hero.cargoFree, hero.cargoMax, hero.premium,
                hero.cloaked, hero.alive, hero.selectedId, hero.petId, hero.config, hero.enemyWarning, hero.beacon);
        Map<Integer, WorldSnapshot.Ship> shipViews = new LinkedHashMap<>();
        for (ShipState s : ships.values()) {
            shipViews.put(s.id, new WorldSnapshot.Ship(s.id, s.kind, s.name, s.typeId, s.factionId, s.clanId,
                    s.clanTag, s.relation, s.cloaked, (int) Math.round(s.motion.x(nanos)),
                    (int) Math.round(s.motion.y(nanos)), s.motion.moving(nanos), (int) Math.round(s.motion.toX()),
                    (int) Math.round(s.motion.toY()), s.health, s.attackTargetId, s.ownerId, s.lockedByOther));
        }
        return new WorldSnapshot(nanos, h, Map.copyOf(shipViews), Map.copyOf(collectables), Map.copyOf(portals),
                Map.copyOf(assets), Map.copyOf(zones), group, Map.copyOf(items),
                new WorldSnapshot.Inventory(Map.copyOf(ores), Map.copyOf(resources), Map.copyOf(bootyKeys)));
    }

    // ---- hero ----

    private void initHero(ShipInitializationCommand p, long nanos) {
        // sent on every login (and on respawn); the server then lists the map contents again
        clearMap();
        hero.id = p.userId;
        hero.name = p.userName;
        hero.shipType = p.typeId;
        hero.mapId = p.mapId;
        hero.motion = Motion.at(p.x, p.y, nanos);
        hero.speed = p.speed;
        hero.health = new Health(p.hitPoints, p.hitMax, p.shield, p.shieldMax, p.nanoHull, p.maxNanoHull);
        hero.factionId = p.factionId;
        hero.clanId = p.clanId;
        hero.level = p.level;
        hero.credits = p.credits;
        hero.uridium = p.uridium;
        // cargoSpace is the free space: it grows by exactly the amount of ore sold (recordings)
        hero.cargoFree = p.cargoSpace;
        hero.cargoMax = p.cargoSpaceMax;
        hero.premium = p.premium;
        hero.cloaked = p.cloaked;
        hero.alive = true;
        hero.selectedId = 0;
    }

    /**
     * The client drives its own ship and ignores small differences to the beacon: beacons arrive
     * with uneven delay, and resetting to them doubles the error (measured on the recordings:
     * median 7 vs 12 units, p99 157 vs 368 against the positions the client reports in its
     * {@code MoveRequest}s). Only a jump far away (portal, teleport, stale state after a map
     * change) is taken over.
     */
    private void beacon(BeaconCommand p, long nanos) {
        hero.beacon = new Beacon(p.inRadiationZone, p.inStation, p.inPeaceArea, p.nearJumpGate, p.repairRobotActive);
        Motion m = hero.motion;
        double dx = m.x(nanos) - p.positionX;
        double dy = m.y(nanos) - p.positionY;
        double ax = m.x(nanos) - p.positionAheadX;
        double ay = m.y(nanos) - p.positionAheadY;
        if (Math.min(Math.hypot(dx, dy), Math.hypot(ax, ay)) > Math.max(BEACON_JUMP, 2.0 * hero.speed)) {
            hero.motion = Motion.at(p.positionX, p.positionY, nanos);
        }
    }

    private void setHeroSpeed(int speed, long nanos) {
        hero.speed = speed;
        Motion m = hero.motion;
        if (m.moving(nanos)) {
            hero.motion = m.moveAtSpeed(nanos, m.toX(), m.toY(), speed);
        }
    }

    private void select(ShipSelectionCommand p) {
        hero.selectedId = p.userId;
        ShipState s = ships.get(p.userId);
        if (s != null) {
            s.health = new Health(p.hitpoints, p.hitpointsMax, p.shield, p.shieldMax, p.nanoHull, p.maxNanoHull);
        }
    }

    // ---- other ships ----

    private void createShip(ShipCreateCommand p, long nanos) {
        ShipState s = new ShipState(p.userId, p.npc ? ShipKind.NPC : ShipKind.PLAYER, Motion.at(p.x, p.y, nanos));
        s.name = p.userName;
        s.typeId = p.typeId;
        s.factionId = p.factionId;
        s.clanId = p.clanId;
        s.clanTag = p.clanTag;
        s.relation = p.clanDiplomacy != null ? p.clanDiplomacy.type : 0;
        s.cloaked = p.cloaked;
        ships.put(s.id, s);
    }

    private void activatePet(int petId, int ownerId, String name, int factionId, int clanId, String clanTag,
                             int relation, int x, int y, long nanos) {
        ShipState s = new ShipState(petId, ShipKind.PET, Motion.at(x, y, nanos));
        s.ownerId = ownerId;
        s.name = name;
        s.factionId = factionId;
        s.clanId = clanId;
        s.clanTag = clanTag;
        s.relation = relation;
        ships.put(petId, s);
        if (ownerId == hero.id) {
            hero.petId = petId;
        }
    }

    private void moveShip(MoveCommand p, long nanos) {
        ShipState s = ships.get(p.userId);
        if (s != null) {
            s.motion = s.motion.moveTo(nanos, p.x, p.y, p.timeToTarget);
        } else if (p.userId == hero.id) {
            hero.motion = hero.motion.moveTo(nanos, p.x, p.y, p.timeToTarget);
        }
    }

    private void removeShip(int id) {
        ships.remove(id);
        if (hero.selectedId == id) {
            hero.selectedId = 0;
        }
        for (ShipState s : ships.values()) {
            if (s.attackTargetId == id) {
                s.attackTargetId = 0;
            }
        }
    }

    private void destroyed(int id) {
        if (id == hero.id) {
            hero.alive = false;
            hero.selectedId = 0;
            return;
        }
        removeShip(id);
    }

    private void hit(AttackHitCommand p) {
        if (p.victimId == hero.id) {
            hero.health = hero.health.withHp(p.victimHitpoints, -1).withShield(p.victimShield, -1)
                    .withNanoHull(p.victimNanoHull, -1);
            return;
        }
        ShipState s = ships.get(p.victimId);
        if (s != null) {
            s.health = s.health.withHp(p.victimHitpoints, -1).withShield(p.victimShield, -1)
                    .withNanoHull(p.victimNanoHull, -1);
        }
    }

    // ---- item bar ----

    private void initItems(MenuInitializationCommand p, long nanos) {
        items.clear();
        for (MenuCategoryInitializationCommand c : p.categories) {
            for (MenuItemInitializationCommand i : c.menuItems) {
                if (i.status == null) {
                    continue;
                }
                MenuItemStatusCommand st = i.status;
                MenuItemTimerCommand t = i.timer;
                // an item can sit in several categories ("buy_now" repeats ammunition): the first one wins
                items.putIfAbsent(st.menuItemId, new WorldSnapshot.Item(st.menuItemId, c.categoryId, st.counterValue,
                        st.maxCounterValue, st.selected, st.available, st.activatable, st.buyable, st.blocked, st.visible,
                        t != null && t.timerState != null ? t.timerState.timerStateValue : TimerStateModule.TIMERSTATE_READY,
                        t != null ? t.time : 0, t != null ? t.maxTime : 0, nanos));
            }
        }
    }

    private void updateItem(MenuItemStatusCommand st) {
        WorldSnapshot.Item old = items.get(st.menuItemId);
        String category = old != null ? old.category() : "";
        if (st.selected && !category.isEmpty()) {
            // one selected item per category, like the client's item bar
            items.replaceAll((k, v) -> v.selected() && category.equals(v.category()) && !k.equals(st.menuItemId)
                    ? withSelected(v, false) : v);
        }
        items.put(st.menuItemId, new WorldSnapshot.Item(st.menuItemId, category, st.counterValue, st.maxCounterValue,
                st.selected, st.available, st.activatable, st.buyable, st.blocked, st.visible,
                old != null ? old.timerState() : TimerStateModule.TIMERSTATE_READY, old != null ? old.timerTime() : 0,
                old != null ? old.timerMax() : 0, old != null ? old.timerNanos() : 0));
    }

    private static WorldSnapshot.Item withSelected(WorldSnapshot.Item i, boolean selected) {
        return new WorldSnapshot.Item(i.id(), i.category(), i.counter(), i.maxCounter(), selected, i.available(),
                i.activatable(), i.buyable(), i.blocked(), i.visible(), i.timerState(), i.timerTime(), i.timerMax(),
                i.timerNanos());
    }

    private void updateTimer(MenuItemTimerCommand t, long nanos) {
        WorldSnapshot.Item i = items.get(t.menuItemId);
        if (i == null) {
            return;
        }
        items.put(t.menuItemId, new WorldSnapshot.Item(i.id(), i.category(), i.counter(), i.maxCounter(), i.selected(),
                i.available(), t.activatable, i.buyable(), i.blocked(), i.visible(),
                t.timerState != null ? t.timerState.timerStateValue : TimerStateModule.TIMERSTATE_READY, t.time,
                t.maxTime, nanos));
    }

    // ---- legacy text commands ("0|A|c|1600"), see docs/recon/STAGE3.md ----

    private void legacy(String message, long nanos) {
        String[] a = message.split("\\|", -1);
        if (a.length < 2) {
            return;
        }
        switch (a[1]) {
            case "A" -> {
                if (a.length < 4) {
                    return;
                }
                String key = a[2];
                switch (key) {
                    case "c" -> hero.cargoMax = parseInt(a[3], hero.cargoMax);
                    case "CC" -> hero.config = parseInt(a[3], hero.config);
                    case "v" -> setHeroSpeed(parseInt(a[3], hero.speed), nanos);
                    default -> {
                        if (key.startsWith("BK")) {
                            bootyKeys.put(key, parseInt(a[3], 0));
                        }
                    }
                }
            }
            case "S" -> {
                if (a.length >= 4 && "CFG".equals(a[2])) {
                    hero.config = parseInt(a[3], hero.config);
                }
            }
            case "n" -> {
                if (a.length < 4) {
                    return;
                }
                switch (a[2]) {
                    case "w" -> hero.enemyWarning = parseInt(a[3], hero.enemyWarning);
                    case "INV" -> {
                        ShipState s = a.length >= 5 ? ships.get(parseInt(a[3], 0)) : null;
                        if (s != null) {
                            s.cloaked = parseInt(a[4], 0) != 0;
                        }
                    }
                    case "LSH" -> {
                        ShipState s = a.length >= 5 ? ships.get(parseInt(a[3], 0)) : null;
                        int attacker = a.length >= 5 ? parseInt(a[4], 0) : 0;
                        if (s != null && attacker != hero.id && !inGroup(attacker)) {
                            s.lockedByOther = true;
                        }
                    }
                    case "USH" -> {
                        ShipState s = ships.get(parseInt(a[3], 0));
                        if (s != null) {
                            s.lockedByOther = false;
                        }
                    }
                    default -> {
                        // titles, drones, messages: not part of the model
                    }
                }
            }
            default -> {
                // other legacy commands are UI only
            }
        }
    }

    private boolean inGroup(int id) {
        return group.members().stream().anyMatch(m -> m.id() == id);
    }

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    // ---- group ----

    private void initGroup(GroupInitializationCommand p) {
        List<Member> members = new ArrayList<>();
        for (GroupMemberInitializationCommand m : p.members) {
            LocationModule loc = m.location;
            HealthModule hp = m.health;
            members.add(new Member(m.id, m.name, m.level, m.faction != null ? m.faction.faction : 0,
                    loc != null ? loc.mapId : 0, loc != null ? loc.x : 0, loc != null ? loc.y : 0,
                    hp != null ? new Health(hp.hp, hp.hpMax, hp.shield, hp.shieldMax, hp.nanoHull, hp.nanoHullMax)
                            : Health.UNKNOWN, m.loggedOut, m.cloaked));
        }
        group = new Group(p.id, p.leaderId, List.copyOf(members));
    }

    private void updateGroupMember(GroupMemberUpdateCommand p) {
        List<Member> members = new ArrayList<>(group.members());
        for (int i = 0; i < members.size(); i++) {
            Member m = members.get(i);
            if (m.id() != p.playerId) {
                continue;
            }
            for (PlayerAttributeModule a : p.updates) {
                m = switch (a) {
                    case LocationModule l -> new Member(m.id(), m.name(), m.level(), m.factionId(), l.mapId, l.x, l.y,
                            m.health(), m.loggedOut(), m.cloaked());
                    case HealthModule h -> new Member(m.id(), m.name(), m.level(), m.factionId(), m.mapId(), m.x(), m.y(),
                            new Health(h.hp, h.hpMax, h.shield, h.shieldMax, h.nanoHull, h.nanoHullMax),
                            m.loggedOut(), m.cloaked());
                    case LevelModule l -> new Member(m.id(), m.name(), l.level, m.factionId(), m.mapId(), m.x(), m.y(),
                            m.health(), m.loggedOut(), m.cloaked());
                    case MemberLoggedOutModule l -> new Member(m.id(), m.name(), m.level(), m.factionId(), m.mapId(),
                            m.x(), m.y(), m.health(), l.loggedOut, m.cloaked());
                    case CloakDeviceActivityModule c -> new Member(m.id(), m.name(), m.level(), m.factionId(), m.mapId(),
                            m.x(), m.y(), m.health(), m.loggedOut(), c.active);
                    default -> m;
                };
            }
            members.set(i, m);
        }
        group = new Group(group.id(), group.leaderId(), List.copyOf(members));
    }

    /** Everything that belongs to the current map; the hero and the group survive a map change. */
    private void clearMap() {
        ships.clear();
        collectables.clear();
        portals.clear();
        assets.clear();
        zones.clear();
        hero.selectedId = 0;
        hero.petId = 0;
        hero.beacon = Beacon.NONE;
    }
}
