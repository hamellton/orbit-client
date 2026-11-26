package orbit.sniffer;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The relay for one official client the app starts (the hand play, docs/PLAN.md «Играть руками»): a SOCKS5 port for the
 * site and the game ports on loopback for the Flash sockets (the client maps {@code *.prod.darkorbit.com} to 127.0.0.1),
 * everything out through {@link Upstream} (the account's proxy), the game packets to {@code tap}, recorded in
 * {@code recordDir} with the session masked.
 */
public final class HandRelay implements AutoCloseable {

    private static final Pattern GAME_TARGET = Pattern.compile("gameserver-.*\\.darkorbit\\.com:\\d+|.*:60\\d\\d");

    private final List<ServerSocket> sockets = new ArrayList<>();
    private int socksPort;

    /**
     * @param gameIp    the game cluster's address (its hosts share one); the client's game sockets go there by port
     * @param gamePorts the ports to listen on (cluster 22: 6001–6007)
     */
    public HandRelay(String gameIp, List<Integer> gamePorts, Path recordDir, Upstream upstream, PacketTap tap)
            throws IOException {
        java.nio.file.Files.createDirectories(recordDir);
        try {
            sockets.addAll(new DirectForwarder(gameIp, gamePorts, recordDir, upstream, tap, true).start());
            Socks5Server socks = new Socks5Server(0, recordDir, t -> GAME_TARGET.matcher(t).matches(), upstream, tap, true);
            ServerSocket ss = socks.bind();
            sockets.add(ss);
            socksPort = ss.getLocalPort();
            Thread.ofVirtual().name("hand-socks").start(() -> {
                try {
                    socks.serve(ss);
                } catch (IOException e) {
                    // closed
                }
            });
        } catch (IOException e) {
            close();
            throw e;
        }
    }

    /** The SOCKS5 port for the client's {@code --proxy-server}. */
    public int socksPort() {
        return socksPort;
    }

    @Override
    public void close() {
        for (ServerSocket s : sockets) {
            try {
                s.close();
            } catch (IOException ignored) {
                // closed
            }
        }
        sockets.clear();
    }
}
