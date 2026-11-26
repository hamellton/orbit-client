package orbit.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import orbit.protocol.Packet;
import orbit.protocol.gen.MoveCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.VersionCommand;

/**
 * What a client watching the bot needs (docs/PLAN.md «Смотреть»): the server's packets of the bot's last login — the head
 * (the version, the login answer, settings, the UI) and the current map since its {@code ShipInitializationCommand}
 * (entities, their last move per ship; the passing ones — moves, shots, hits — left out) — and the live packets both ways
 * for the watchers. Fed by the account's {@link WorldFeed} from the bot's own connection.
 */
public final class MirrorCapture {

    /** Passing packets: not kept for the replay (only the live stream shows them). */
    static final Set<String> PASSING = Set.of("AttackLaserRunCommand", "AttackHitCommand", "AttackMissedCommand",
            "AttackHitNoLockCommand", "AttackRocketCommand", "AttackHitAssetCommand", "AttributeHitpointUpdateCommand",
            "AttributeShieldUpdateCommand", "HitpointInfoCommand", "PetHitpointsUpdateCommand", "PetShieldUpdateCommand",
            "KeepAlive", "LegacyModule");
    /** Packets of one map kept at most (a long stay: the oldest go). */
    static final int MAP_MAX = 40_000;

    private VersionCommand version;
    private final List<Packet> head = new ArrayList<>();
    private final List<Packet> map = new ArrayList<>();
    private final Map<Integer, MoveCommand> moves = new LinkedHashMap<>();
    private boolean inMap;

    /** Live watchers: {@code (fromServer, packet)}. */
    private final List<BiConsumer<Boolean, Packet>> watchers = new CopyOnWriteArrayList<>();

    /** A packet the server sent to the bot. */
    public void incoming(Packet p) {
        synchronized (this) {
            if (p instanceof VersionCommand v) {
                // a new connection: the login starts again
                version = v;
                head.clear();
                map.clear();
                moves.clear();
                inMap = false;
            } else if (p instanceof orbit.protocol.gen.ReloginCommand) {
                // a map change: a client reconnecting now waits for the bot's next login, not the old map
                inMap = false;
            } else if (p instanceof ShipInitializationCommand) {
                inMap = true;
                map.clear();
                moves.clear();
                map.add(p);
            } else if (!inMap) {
                head.add(p);
            } else if (p instanceof MoveCommand m) {
                moves.put(m.userId, m);
            } else if (!PASSING.contains(p.getClass().getSimpleName())) {
                if (map.size() >= MAP_MAX) {
                    map.remove(1); // the ShipInitializationCommand stays first
                }
                map.add(p);
            }
        }
        for (var w : watchers) {
            w.accept(true, p);
        }
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

    /** What the client gets after its login: the head, the map, the ships' last moves. */
    public synchronized List<Packet> replay() {
        List<Packet> out = new ArrayList<>(head.size() + map.size() + moves.size());
        out.addAll(head);
        out.addAll(map);
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
