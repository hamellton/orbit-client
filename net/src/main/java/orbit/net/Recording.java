package orbit.net;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Reader for session recordings ({@code .dorec}).
 *
 * <p>Format: magic {@code "DOREC1"}, then records of
 * {@code [nanosSinceStart:i64][direction:u8 (0 = client→server, 1 = server→client)][len:i32][frame payload]}.
 * The first client→server record is the single handshake byte.
 */
public final class Recording {

    public static final String MAGIC = "DOREC1";
    public static final byte UP = 0;
    public static final byte DOWN = 1;

    /** One recorded frame payload. */
    public record Frame(long nanos, boolean up, byte[] payload) {
    }

    /** One packet after splitting frames (downstream envelopes are decompressed). */
    public record PacketRecord(long nanos, boolean up, byte[] bytes) {

        public short id() {
            return Downstream.packetId(bytes);
        }
    }

    private Recording() {
    }

    public static List<Frame> readFrames(Path file) throws IOException {
        List<Frame> out = new ArrayList<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            if (!new String(in.readNBytes(MAGIC.length()), StandardCharsets.US_ASCII).equals(MAGIC)) {
                throw new IOException("not a " + MAGIC + " file: " + file);
            }
            while (true) {
                long nanos;
                try {
                    nanos = in.readLong();
                } catch (EOFException end) {
                    break;
                }
                boolean up = in.readByte() == UP;
                int len = in.readInt();
                byte[] payload = in.readNBytes(len);
                if (payload.length != len) {
                    throw new EOFException("truncated record in " + file);
                }
                out.add(new Frame(nanos, up, payload));
            }
        }
        return out;
    }

    /**
     * Splits recorded frames into packets. Compression is considered offered once a client
     * {@code LoginRequest} (id 7) carrying a {@code do-lz4-*} version has been seen.
     */
    public static List<PacketRecord> readPackets(Path file) throws IOException {
        List<PacketRecord> out = new ArrayList<>();
        boolean compression = false;
        boolean first = true;
        for (Frame f : readFrames(file)) {
            if (f.up() && first) {
                first = false; // handshake byte
                continue;
            }
            if (f.payload().length < 2) {
                continue;
            }
            if (f.up()) {
                if (Downstream.packetId(f.payload()) == 7
                        && new String(f.payload(), StandardCharsets.ISO_8859_1).contains("do-lz4-")) {
                    compression = true;
                }
                out.add(new PacketRecord(f.nanos(), true, f.payload()));
            } else {
                for (byte[] p : Downstream.split(f.payload(), compression)) {
                    out.add(new PacketRecord(f.nanos(), false, p));
                }
            }
        }
        return out;
    }
}
