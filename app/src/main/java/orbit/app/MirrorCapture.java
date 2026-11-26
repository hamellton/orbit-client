package orbit.app;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import orbit.protocol.Packet;
import orbit.protocol.gen.BeaconCommand;
import orbit.protocol.gen.MoveCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.VersionCommand;

/**
 * What a client watching the bot needs (docs/PLAN.md «Смотреть»): the server's packets of the bot's last login — the head
 * (the version, the login answer, settings, the UI) and the current map as it is now, not its history: the boxes, ships,
 * mines, assets and gates that are there (a create without its remove), each item's and window's last state, each ship's last
 * move, the last beacon; the passing packets (shots, hits, sounds, keep-alives) left out. A replay of the whole history made
 * the client step through it for half a minute, the ship standing at the login portal (live st2 2026-11-26: 17 387 packets).
 */
public final class MirrorCapture {

    /** Passing packets: only the live stream shows them. */
    static final Set<String> PASSING = Set.of("AttackLaserRunCommand", "AttackHitCommand", "AttackMissedCommand",
            "AttackHitNoLockCommand", "AttackRocketCommand", "AttackHitAssetCommand", "AttributeHitpointUpdateCommand",
            "AttributeShieldUpdateCommand", "HitpointInfoCommand", "PetHitpointsUpdateCommand", "PetShieldUpdateCommand",
            "KeepAlive", "LegacyModule", "AttackAbortLaserCommand", "PetLaserAttackStopCommand", "PlaySoundCommand",
            "StayinAlive", "UpdateSpaceSearchKeyCommand", "HellstormAttackCommand", "AbilityStartCommand",
            "AbilityStopCommand", "RefreshSelectedUICategoryCommand");
    /** Packets whose last one alone matters (per item where they name one). */
    static final Set<String> LATEST = Set.of("MenuItemStatusCommand", "MenuItemTimerCommand", "WindowItemCommand",
            "AttributeOreCountUpdateCommand", "AttributeSkillShieldUpdateCommand", "InventoryItemPack",
            "VisualModifierCommand", "QuestInitializationCommand");
    /** Other packets of one map kept at most (the oldest go). */
    static final int MAP_MAX = 5_000;

    private VersionCommand version;
    private final List<Packet> head = new ArrayList<>();
    private ShipInitializationCommand init;
    /** The map's state: entities and latest states by key, the rest by arrival number. */
    private final LinkedHashMap<String, Packet> state = new LinkedHashMap<>();
    private final Map<Integer, MoveCommand> moves = new LinkedHashMap<>();
    private BeaconCommand beacon;
    private boolean inMap;
    private long other;

    /** Live watchers: {@code (fromServer, packet)}. */
    private final List<BiConsumer<Boolean, Packet>> watchers = new CopyOnWriteArrayList<>();

    /** A packet the server sent to the bot. */
    public void incoming(Packet p) {
        synchronized (this) {
            keep(p);
        }
        for (var w : watchers) {
            w.accept(true, p);
        }
    }

    private void keep(Packet p) {
        String name = p.getClass().getSimpleName();
        if (p instanceof VersionCommand v) {
            // a new connection: the login starts again
            version = v;
            head.clear();
            clearMap();
            inMap = false;
            init = null;
        } else if (p instanceof orbit.protocol.gen.ReloginCommand) {
            // a map change: a client reconnecting now waits for the bot's next login, not the old map
            inMap = false;
        } else if (p instanceof ShipInitializationCommand s) {
            inMap = true;
            init = s;
            clearMap();
        } else if (!inMap) {
            head.add(p);
        } else if (p instanceof MoveCommand m) {
            moves.put(m.userId, m);
        } else if (p instanceof BeaconCommand b) {
            beacon = b;
        } else if (PASSING.contains(name)) {
            // live only
        } else if (!entity(p, name)) {
            if (LATEST.contains(name)) {
                String key = name + ":" + firstOf(p, "menuItemId", "itemId", "userId") + ":" + firstOf(p, "modifier");
                state.remove(key);
                state.put(key, p);
            } else {
                if (other >= MAP_MAX) {
                    String oldest = state.keySet().stream().filter(k -> k.startsWith("#")).findFirst().orElse(null);
                    if (oldest != null) {
                        state.remove(oldest);
                    }
                }
                state.put("#" + other++, p);
            }
        }
    }

    /** The creates and removes of what is on the map; true when {@code p} was one. */
    private boolean entity(Packet p, String name) {
        switch (name) {
            case "AddBoxCommand", "AddOreCommand", "AddMineCommand" -> state.put("c:" + field(p, "hash"), p);
            case "RemoveCollectableCommand", "RemoveMineCommand", "LMMineExplodedCommand" -> state.remove("c:" + field(p, "hash"));
            case "ShipCreateCommand" -> state.put("s:" + field(p, "userId"), p);
            case "ShipRemoveCommand" -> dropShip(field(p, "userId"));
            case "ShipDestroyedCommand" -> dropShip(field(p, "destroyedUserId"));
            case "AssetCreateCommand" -> state.put("a:" + field(p, "assetId"), p);
            case "AssetRemoveCommand" -> state.remove("a:" + field(p, "uid"));
            case "JumpgateCreateCommand" -> state.put("g:" + field(p, "gateId"), p);
            case "JumpgateRemoveCommand" -> state.remove("g:" + field(p, "gateId"));
            default -> {
                return false;
            }
        }
        return true;
    }

    private void dropShip(Object userId) {
        state.remove("s:" + userId);
        if (userId instanceof Integer id) {
            moves.remove(id);
        }
        state.keySet().removeIf(k -> k.startsWith("VisualModifierCommand:" + userId + ":"));
    }

    private void clearMap() {
        state.clear();
        moves.clear();
        beacon = null;
        other = 0;
    }

    /** A public field of the packet or its parents, {@code null} when it has none. */
    static Object field(Packet p, String name) {
        for (Class<?> c = p.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
            try {
                Field f = c.getDeclaredField(name);
                return f.get(p);
            } catch (NoSuchFieldException e) {
                // the parent's
            } catch (IllegalAccessException e) {
                return null;
            }
        }
        return null;
    }

    private static Object firstOf(Packet p, String... names) {
        for (String n : names) {
            Object v = field(p, n);
            if (v != null) {
                return v;
            }
        }
        return "";
    }

    /** A packet the bot sent to the server. */
    public void outgoing(Packet p) {
        for (var w : watchers) {
            w.accept(false, p);
        }
    }

    /** The server's answer to a client's version request ({@code null} before the bot logged in). */
    public synchronized VersionCommand version() {
        return version;
    }

    /** The last beacon of the map (its flags for the hero's fresh one), or {@code null}. */
    public synchronized BeaconCommand beacon() {
        return beacon;
    }

    /** What the client gets after its login: the head, the map's start, its state now, the ships' last moves. */
    public synchronized List<Packet> replay() {
        List<Packet> out = new ArrayList<>(head.size() + state.size() + moves.size() + 1);
        out.addAll(head);
        if (init != null) {
            out.add(init);
        }
        out.addAll(state.values());
        out.addAll(moves.values());
        return out;
    }

    /** The bot is on a map (a client can be shown it). */
    public synchronized boolean ready() {
        return version != null && inMap;
    }

    public void watch(BiConsumer<Boolean, Packet> w) {
        watchers.add(w);
    }

    public void unwatch(BiConsumer<Boolean, Packet> w) {
        watchers.remove(w);
    }
}
