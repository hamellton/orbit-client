package orbit.game;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.MapServers;
import orbit.protocol.Packet;
import orbit.protocol.gen.KeepAlive;
import orbit.protocol.gen.LoginRequest;
import orbit.protocol.gen.ProtocolVersion;
import orbit.protocol.gen.ReadyRequest;
import orbit.protocol.gen.ReloginCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.VersionRequest;
import org.junit.jupiter.api.Test;

class GameConnectionTest {

    private static final String READY = "2D 1920x1022 .root1.test";

    private static ConnectionSettings fast() {
        return new ConnectionSettings(Proxy.NO_PROXY, Duration.ofMillis(200), Duration.ZERO, true,
                Duration.ofSeconds(2), Duration.ofSeconds(2), List.of(Duration.ofMillis(50)), 3);
    }

    /** Session source pointing at fake servers; map 12 lives on {@code other}. */
    private static final class Source implements SessionSource {
        final int port;
        final int otherPort;
        final AtomicInteger logins = new AtomicInteger();

        Source(int port, int otherPort) {
            this.port = port;
            this.otherPort = otherPort;
        }

        @Override
        public GameSession fresh() {
            int n = logins.incrementAndGet();
            return new GameSession("https://int1.example", "dosid", 166225758, "sess-" + n, 22, 1,
                    "127.0.0.1", port, "PC_CLIENT", "https://cdn.example/spacemap/xml/maps.php");
        }

        @Override
        public MapServers.Address serverForMap(GameSession s, int mapId) throws AuthException {
            if (mapId == 12) {
                return new MapServers.Address("127.0.0.1", otherPort);
            }
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "unknown map");
        }
    }

    private static final class Listener implements GameListener {
        final List<Packet> packets = new CopyOnWriteArrayList<>();
        final List<String> states = new CopyOnWriteArrayList<>();

        @Override
        public void onPacket(Packet packet) {
            packets.add(packet);
        }

        @Override
        public void onState(State state, String reason) {
            states.add(state + ": " + reason);
        }
    }

    @Test
    void logsInSendsStartupAndKeepsAlive() throws Exception {
        try (FakeGameServer gs = new FakeGameServer()) {
            Source src = new Source(gs.port(), 0);
            Listener l = new Listener();
            GameConnection c = new GameConnection(src, l, fast(), StartupSequence.minimal(READY));
            Thread t = Thread.ofVirtual().start(c::run);

            // startup packets come ~1 s after login, like the client; keep-alives keep ticking meanwhile
            await(() -> gs.receivedOf(ReadyRequest.class).size() >= 2 && gs.receivedOf(KeepAlive.class).size() >= 3, 6);

            assertEquals(1, gs.policyRequests.get(), "policy is requested before connecting");
            assertEquals(0, gs.badHandshakes.get());
            assertEquals(VersionRequest.class, gs.received.get(0).getClass());
            assertEquals(ProtocolVersion.VERSION_HASH, ((VersionRequest) gs.received.get(0)).version);
            LoginRequest lr = (LoginRequest) gs.received.get(1);
            assertEquals(166225758, lr.userID);
            assertEquals("sess-1", lr.sessionID);
            assertEquals(ProtocolVersion.COMPRESSION, lr.version);
            assertEquals(22, lr.instanceId);
            assertTrue(lr.isMiniClient);
            List<ReadyRequest> ready = gs.receivedOf(ReadyRequest.class);
            assertEquals(2, ready.size());
            assertEquals(0, ready.get(0).readyType);
            assertEquals(2, ready.get(1).readyType);
            assertEquals(READY, ready.get(0).readyTypeStr);
            assertTrue(l.packets.stream().anyMatch(p -> p instanceof ShipInitializationCommand));
            assertTrue(l.states.stream().anyMatch(s -> s.startsWith("ONLINE")), l.states.toString());

            c.close();
            t.join(TimeUnit.SECONDS.toMillis(3));
            assertTrue(l.states.getLast().startsWith("STOPPED"), l.states.toString());
        }
    }

    @Test
    void followsReloginToAnotherServer() throws Exception {
        try (FakeGameServer a = new FakeGameServer(); FakeGameServer b = new FakeGameServer()) {
            a.afterLogin = send -> {
                ReloginCommand rc = new ReloginCommand();
                rc.mapID = 12;
                rc.delayInMillis = 10;
                send.accept(rc);
            };
            Source src = new Source(a.port(), b.port());
            Listener l = new Listener();
            GameConnection c = new GameConnection(src, l, fast(), StartupSequence.minimal(READY));
            Thread t = Thread.ofVirtual().start(c::run);

            await(() -> !b.receivedOf(LoginRequest.class).isEmpty(), 5);
            assertEquals(1, src.logins.get(), "a server change keeps the website session");
            assertEquals("sess-1", b.receivedOf(LoginRequest.class).getFirst().sessionID);
            assertTrue(l.states.stream().anyMatch(s -> s.startsWith("CHANGING_SERVER")), l.states.toString());
            c.close();
            t.join(3000);
        }
    }

    @Test
    void outdatedProtocolStopsWithClearMessage() throws Exception {
        try (FakeGameServer gs = new FakeGameServer()) {
            gs.mode = FakeGameServer.Mode.OUTDATED;
            Listener l = new Listener();
            GameConnection c = new GameConnection(new Source(gs.port(), 0), l, fast(), StartupSequence.minimal(READY));
            Thread t = Thread.ofVirtual().start(c::run);
            t.join(5000);
            assertTrue(!t.isAlive(), "connection must stop on an outdated protocol");
            String last = l.states.getLast();
            assertTrue(last.startsWith("STOPPED") && last.contains("устарела"), last);
            assertEquals(1, gs.gameConnections.get(), "no reconnect attempts");
            assertTrue(gs.receivedOf(LoginRequest.class).isEmpty(), "never logs in with an outdated protocol");
        }
    }

    @Test
    void rejectedLoginTriggersFreshWebsiteLogin() throws Exception {
        try (FakeGameServer gs = new FakeGameServer()) {
            gs.mode = FakeGameServer.Mode.REJECT_LOGIN;
            gs.rejectsLeft.set(1);
            Source src = new Source(gs.port(), 0);
            Listener l = new Listener();
            GameConnection c = new GameConnection(src, l, fast(), StartupSequence.minimal(READY));
            Thread t = Thread.ofVirtual().start(c::run);

            await(() -> l.states.stream().anyMatch(s -> s.startsWith("ONLINE")), 6);
            assertEquals(2, src.logins.get(), "stale session is replaced by a fresh login");
            List<LoginRequest> logins = gs.receivedOf(LoginRequest.class);
            assertEquals("sess-2", logins.getLast().sessionID);
            assertTrue(l.states.stream().anyMatch(s -> s.startsWith("RECONNECTING")), l.states.toString());
            c.close();
            t.join(3000);
        }
    }

    private static void await(BooleanSupplier cond, int seconds) throws InterruptedException {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (!cond.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("timeout waiting for condition");
            }
            Thread.sleep(20);
        }
    }
}
