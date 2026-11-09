package orbit.sniffer;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import orbit.protocol.Packet;

/**
 * Receives every packet the sniffer relays, decoded (client packets with the sessionID masked).
 * Lets another component, e.g. the bot window in observer mode, follow the official client's
 * session. Called from relay threads.
 */
@FunctionalInterface
public interface PacketTap {

    /**
     * @param upstream client → server
     * @param nanos    {@code System.nanoTime()} when the packet was relayed
     */
    void onPacket(boolean upstream, Packet packet, long nanos);

    /** Registered taps. */
    List<PacketTap> TAPS = new CopyOnWriteArrayList<>();

    static void register(PacketTap tap) {
        TAPS.add(tap);
    }
}
