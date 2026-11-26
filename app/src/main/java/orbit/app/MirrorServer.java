package orbit.app;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import orbit.net.Frames;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.LoginRequest;
import orbit.protocol.gen.MoveCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.VersionRequest;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game server a watching client meets (docs/PLAN.md «Смотреть»): on loopback at the game ports, it answers the client's
 * version and login with the bot's own login ({@link MirrorCapture#replay}), then passes on every packet the server sends the
 * bot. The client's own packets are read and dropped — nothing it does reaches the game. The bot's ship moves are shown as
 * the server shows any ship's: a {@code MoveCommand} for the hero to the bot's {@code MoveRequest} target.
 */
final class MirrorServer implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(MirrorServer.class);
    private static final byte[] POLICY = ("<?xml version=\"1.0\"?><cross-domain-policy><allow-access-from domain=\"*\""
            + " to-ports=\"*\" /></cross-domain-policy>\0").getBytes(StandardCharsets.US_ASCII);

    private final MirrorCapture capture;
    private final Supplier<WorldSnapshot> world;
    private final List<ServerSocket> sockets = new ArrayList<>();
    private final List<Socket> clients = new java.util.concurrent.CopyOnWriteArrayList<>();

    MirrorServer(MirrorCapture capture, Supplier<WorldSnapshot> world, List<Integer> ports) throws IOException {
        this.capture = capture;
        this.world = world;
        try {
            for (int port : ports) {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port));
                sockets.add(ss);
                Thread.ofVirtual().name("mirror-" + port).start(() -> accept(ss));
            }
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    private void accept(ServerSocket ss) {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                clients.add(s);
                Thread.ofVirtual().start(() -> serve(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void serve(Socket s) {
        BiConsumer<Boolean, Packet> live = null;
        try (s) {
            s.setTcpNoDelay(true);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = new BufferedOutputStream(s.getOutputStream());
            int hello = in.read();
            if (hello < 0) {
                return;
            }
            if (hello == '<') {
                // Flash asks the game port for its socket policy first
                while (in.read() > 0) {
                    // up to the zero byte
                }
                out.write(POLICY);
                out.flush();
                return;
            }
            byte[] frame;
            boolean loggedIn = false;
            while ((frame = Frames.read(in)) != null) {
                if (frame.length == 0) {
                    continue;
                }
                Packet p;
                try {
                    p = PacketCodec.decode(frame);
                } catch (RuntimeException e) {
                    continue; // the client's packets are not passed on anyway
                }
                if (p instanceof VersionRequest) {
                    waitReady();
                    send(out, capture.version());
                } else if (p instanceof LoginRequest && !loggedIn) {
                    loggedIn = true;
                    OutputStream o = out;
                    live = (fromServer, packet) -> {
                        try {
                            if (fromServer) {
                                send(o, packet);
                            } else if (packet instanceof MoveRequest m) {
                                send(o, heroMove(m));
                            }
                        } catch (IOException e) {
                            close(s);
                        }
                    };
                    int shown;
                    // watching before the replay is taken, under the stream's lock: nothing between the two is lost,
                    // the live packets wait for the replay to be written
                    synchronized (out) {
                        capture.watch(live);
                        List<Packet> replay = capture.replay();
                        for (Packet r : replay) {
                            write(out, r);
                        }
                        write(out, heroAt());
                        write(out, heroBeacon());
                        out.flush();
                        shown = replay.size();
                    }
                    LOG.info("Смотреть: клиент вошёл в зеркало — показано {} пакетов входа ({}), дальше вживую", shown,
                            capture.describe());
                }
                // anything else the client sends (clicks, keys, its UI) is dropped: the bot plays
            }
        } catch (IOException e) {
            LOG.debug("Смотреть: соединение клиента закрыто ({})", e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (live != null) {
                capture.unwatch(live);
            }
            clients.remove(s);
        }
    }

    /** Waits for the bot to be on a map (a map change under way). */
    private void waitReady() throws InterruptedException {
        long until = System.currentTimeMillis() + 60_000;
        while (!capture.ready() && System.currentTimeMillis() < until) {
            Thread.sleep(200);
        }
    }

    /** The hero where the bot's ship is now (the replay ends at the login's place). */
    private Packet heroAt() {
        WorldSnapshot w = world.get();
        MoveCommand m = new MoveCommand();
        if (w == null || w.hero() == null) {
            return m;
        }
        m.userId = w.hero().id();
        m.x = w.hero().x();
        m.y = w.hero().y();
        m.timeToTarget = 0;
        return m;
    }

    /**
     * The server's beacon with the bot's ship where it is now and where it heads: the client takes the server's place of
     * its ship from beacons, so it does not stand at the login's place till the next one.
     */
    private Packet heroBeacon() {
        WorldSnapshot w = world.get();
        orbit.protocol.gen.BeaconCommand last = capture.beacon();
        if (w == null || w.hero() == null) {
            return last;
        }
        orbit.protocol.gen.BeaconCommand b = new orbit.protocol.gen.BeaconCommand();
        if (last != null) {
            b.nearJumpGate = last.nearJumpGate;
            b.inRadiationZone = last.inRadiationZone;
            b.inStation = last.inStation;
            b.inPeaceArea = last.inPeaceArea;
            b.repairBotSkilled = last.repairBotSkilled;
            b.isAlarmEffectActive = last.isAlarmEffectActive;
            b.repairRobotLootId = last.repairRobotLootId;
            b.repairRobotActive = last.repairRobotActive;
        }
        b.positionX = w.hero().x();
        b.positionY = w.hero().y();
        b.positionAheadX = w.hero().moving() ? w.hero().targetX() : w.hero().x();
        b.positionAheadY = w.hero().moving() ? w.hero().targetY() : w.hero().y();
        return b;
    }

    /** The bot's move as the server shows a ship's: to the target in the time its speed takes. */
    private Packet heroMove(MoveRequest r) {
        WorldSnapshot w = world.get();
        MoveCommand m = new MoveCommand();
        m.x = r.targetX;
        m.y = r.targetY;
        if (w == null || w.hero() == null) {
            return m;
        }
        m.userId = w.hero().id();
        double speed = Math.max(1, w.hero().speed());
        m.timeToTarget = (int) Math.round(Math.hypot(r.targetX - r.positionX, r.targetY - r.positionY) / speed * 1000);
        return m;
    }

    private static void send(OutputStream out, Packet p) throws IOException {
        if (p == null) {
            return;
        }
        synchronized (out) {
            write(out, p);
            out.flush();
        }
    }

    private static void write(OutputStream out, Packet p) throws IOException {
        if (p == null) {
            return;
        }
        Frames.write(out, PacketCodec.encode(p));
    }

    private static void close(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // closed
        }
    }

    @Override
    public void close() {
        for (ServerSocket ss : sockets) {
            try {
                ss.close();
            } catch (IOException ignored) {
                // closed
            }
        }
        sockets.clear();
        clients.forEach(MirrorServer::close);
    }
}
