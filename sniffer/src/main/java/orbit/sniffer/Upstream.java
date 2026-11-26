package orbit.sniffer;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

/** How the relay reaches the outside: straight, or through an account's proxy (the app passes its connector). */
@FunctionalInterface
public interface Upstream {

    /** A connected socket to {@code host:port}. */
    Socket open(String host, int port) throws IOException;

    Upstream DIRECT = (host, port) -> {
        Socket s = new Socket();
        s.connect(new InetSocketAddress(host, port), 15_000);
        return s;
    };
}
