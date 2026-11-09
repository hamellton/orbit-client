package orbit.sniffer;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Masks secrets inside packets before they reach logs or recordings. */
final class Secrets {

    static final short LOGIN_REQUEST_ID = 7;

    private Secrets() {
    }

    /**
     * Returns a copy of a {@code LoginRequest} payload with the sessionID bytes replaced
     * by {@code 'x'} (same length, so the frame stays byte-compatible), or the original
     * array for any other packet.
     *
     * <p>Layout: {@code [id:i16][userID:i32][factionID:i16][sessionID:utf][version:utf]...}
     */
    static byte[] maskSession(byte[] packet) {
        if (packet.length < 10 || id(packet) != LOGIN_REQUEST_ID) {
            return packet;
        }
        int lenPos = 2 + 4 + 2;
        int len = (packet[lenPos] & 0xFF) << 8 | packet[lenPos + 1] & 0xFF;
        if (lenPos + 2 + len > packet.length) {
            return packet;
        }
        byte[] copy = packet.clone();
        Arrays.fill(copy, lenPos + 2, lenPos + 2 + len, (byte) 'x');
        return copy;
    }

    /** Reads the {@code version} string of a {@code LoginRequest}, or {@code null}. */
    static String loginVersion(byte[] packet) {
        if (packet.length < 10 || id(packet) != LOGIN_REQUEST_ID) {
            return null;
        }
        int p = 2 + 4 + 2;
        int sidLen = (packet[p] & 0xFF) << 8 | packet[p + 1] & 0xFF;
        p += 2 + sidLen;
        if (p + 2 > packet.length) {
            return null;
        }
        int vLen = (packet[p] & 0xFF) << 8 | packet[p + 1] & 0xFF;
        if (p + 2 + vLen > packet.length) {
            return null;
        }
        return new String(packet, p + 2, vLen, StandardCharsets.UTF_8);
    }

    private static short id(byte[] packet) {
        return (short) ((packet[0] & 0xFF) << 8 | packet[1] & 0xFF);
    }
}
