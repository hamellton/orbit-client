package orbit.game;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import orbit.net.Frames;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.LoginRequest;
import orbit.protocol.gen.LoginResponse;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.VersionCommand;
import orbit.protocol.gen.VersionRequest;

/** Minimal imitation of a game server for connection tests. */
final class FakeGameServer implements AutoCloseable {

    enum Mode { NORMAL, OUTDATED, REJECT_LOGIN }

    final ServerSocket ss;
    final List<Packet> received = new CopyOnWriteArrayList<>();
    final AtomicInteger policyRequests = new AtomicInteger();
    final AtomicInteger gameConnections = new AtomicInteger();
    final AtomicInteger badHandshakes = new AtomicInteger();
    volatile Mode mode = Mode.NORMAL;
    /** Rejects this many logins (closing the socket), then accepts. */
    final AtomicInteger rejectsLeft = new AtomicInteger();
    /** Called after a successful login with a sender for extra server packets. */
    volatile Consumer<Consumer<Packet>> afterLogin = send -> { };

    FakeGameServer() throws IOException {
        ss = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(this::acceptLoop);
    }

    int port() {
        return ss.getLocalPort();
    }

    private void acceptLoop() {
        while (!ss.isClosed()) {
            try {
                Socket s = ss.accept();
                Thread.ofVirtual().start(() -> handle(s));
            } catch (IOException e) {
                return;
            }
        }
    }

    private void handle(Socket s) {
        try (s) {
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            int first = in.read();
            if (first == '<') {
                byte[] rest = in.readNBytes("policy-file-request/>\0".length());
                if (new String(rest, StandardCharsets.US_ASCII).equals("policy-file-request/>\0")) {
                    policyRequests.incrementAndGet();
                    out.write("<cross-domain-policy><allow-access-from domain=\"*\" to-ports=\"*\"/></cross-domain-policy>\0"
                            .getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
                return;
            }
            if (first != 0) {
                badHandshakes.incrementAndGet();
                return;
            }
            gameConnections.incrementAndGet();
            Consumer<Packet> send = p -> {
                try {
                    synchronized (out) {
                        Frames.write(out, PacketCodec.encode(p));
                    }
                } catch (IOException e) {
                    // client went away
                }
            };
            byte[] payload;
            while ((payload = Frames.read(in)) != null) {
                Packet p = PacketCodec.decode(payload);
                received.add(p);
                if (p instanceof VersionRequest vr) {
                    VersionCommand vc = new VersionCommand();
                    vc.version = vr.version;
                    vc.equal = mode != Mode.OUTDATED;
                    send.accept(vc);
                } else if (p instanceof LoginRequest) {
                    if (mode == Mode.REJECT_LOGIN && rejectsLeft.getAndDecrement() > 0) {
                        return;
                    }
                    ShipInitializationCommand si = new ShipInitializationCommand();
                    si.userName = "Tester";
                    si.mapId = 1;
                    send.accept(si);
                    send.accept(new LoginResponse());
                    afterLogin.accept(send);
                }
            }
        } catch (IOException e) {
            // connection closed
        }
    }

    <T extends Packet> List<T> receivedOf(Class<T> type) {
        return received.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Override
    public void close() throws IOException {
        ss.close();
    }
}
