package orbit.net;

import java.util.ArrayList;
import java.util.List;
import java.util.zip.CRC32;

/**
 * Splits one server-to-client frame payload into individual packets.
 *
 * <p>A payload is either a single plain packet ({@code [id:int16][fields]}) or,
 * when it starts with {@code FF FF}, a compressed envelope:
 * <pre>
 *   FF FF | magic:4 | rawSize:u32 | crc32(raw):u32 | lz4 block...
 * </pre>
 * {@code DOB3} envelopes decompress to a sequence of {@code [len:u24][packet]};
 * legacy {@code DOL4} envelopes decompress to exactly one packet.
 */
public final class Downstream {

    public static final String COMPRESSION_CAPABILITY = "do-lz4-block-v3";
    public static final int MAX_RAW_BYTES = 3 * 1024 * 1024;

    static final int MAGIC_BLOCK = 0x444F4233;  // "DOB3"
    static final int MAGIC_LEGACY = 0x444F4C34; // "DOL4"
    static final int ENVELOPE_HEADER = 14;

    private Downstream() {
    }

    /** Returns {@code true} if the payload is a compressed envelope. */
    public static boolean isEnvelope(byte[] payload) {
        return payload.length >= 2 && (payload[0] & 0xFF) == 0xFF && (payload[1] & 0xFF) == 0xFF;
    }

    /**
     * @param compressionOffered whether the client advertised compression in its login request;
     *                           envelopes are rejected otherwise, like the official client does
     * @return packets, each starting with its int16 id
     */
    public static List<byte[]> split(byte[] payload, boolean compressionOffered) {
        if (payload.length < 2) {
            throw new ProtocolException("downstream payload too short: " + payload.length);
        }
        if (!isEnvelope(payload)) {
            return List.of(payload);
        }
        if (!compressionOffered) {
            throw new ProtocolException("compressed envelope received but compression was not offered");
        }
        if (payload.length <= ENVELOPE_HEADER) {
            throw new ProtocolException("envelope too short: " + payload.length);
        }
        int magic = readInt(payload, 2);
        int rawSize = readInt(payload, 6);
        int crc = readInt(payload, 10);
        if (rawSize < 0 || rawSize > MAX_RAW_BYTES) {
            throw new ProtocolException("envelope raw size out of range: " + Integer.toUnsignedString(rawSize));
        }
        byte[] raw = Lz4Block.decompress(payload, ENVELOPE_HEADER, payload.length - ENVELOPE_HEADER, rawSize);
        CRC32 c = new CRC32();
        c.update(raw);
        if ((int) c.getValue() != crc) {
            throw new ProtocolException("envelope crc mismatch");
        }

        if (magic == MAGIC_LEGACY) {
            return List.of(raw);
        }
        if (magic != MAGIC_BLOCK) {
            throw new ProtocolException("unknown envelope magic 0x" + Integer.toHexString(magic));
        }
        List<byte[]> packets = new ArrayList<>();
        int p = 0;
        while (p < raw.length) {
            if (raw.length - p < 3) {
                throw new ProtocolException("block: truncated inner length");
            }
            int len = (raw[p] & 0xFF) << 16 | (raw[p + 1] & 0xFF) << 8 | raw[p + 2] & 0xFF;
            p += 3;
            if (len < 2 || len > raw.length - p) {
                throw new ProtocolException("block: inner length out of range: " + len);
            }
            if ((raw[p] & 0xFF) == 0xFF && (raw[p + 1] & 0xFF) == 0xFF) {
                throw new ProtocolException("block: nested envelope");
            }
            byte[] packet = new byte[len];
            System.arraycopy(raw, p, packet, 0, len);
            packets.add(packet);
            p += len;
        }
        if (packets.isEmpty()) {
            throw new ProtocolException("block: empty");
        }
        return packets;
    }

    /** Packet id of a single packet payload (first two bytes, signed big-endian). */
    public static short packetId(byte[] packet) {
        return (short) ((packet[0] & 0xFF) << 8 | packet[1] & 0xFF);
    }

    private static int readInt(byte[] b, int off) {
        return (b[off] & 0xFF) << 24 | (b[off + 1] & 0xFF) << 16 | (b[off + 2] & 0xFF) << 8 | b[off + 3] & 0xFF;
    }
}
