package orbit.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Length-prefixed framing used in both directions: a 3-byte big-endian length
 * followed by that many payload bytes.
 */
public final class Frames {

    public static final int HEADER_LENGTH = 3;
    public static final int MAX_PAYLOAD = 0xFFFFFF;

    private Frames() {
    }

    /**
     * Reads one frame payload. Returns {@code null} on a clean end of stream
     * before the first header byte. Zero-length frames are returned as empty arrays.
     */
    public static byte[] read(InputStream in) throws IOException {
        int b0 = in.read();
        if (b0 < 0) {
            return null;
        }
        int b1 = in.read();
        int b2 = in.read();
        if ((b1 | b2) < 0) {
            throw new EOFException("truncated frame header");
        }
        int len = b0 << 16 | b1 << 8 | b2;
        byte[] payload = in.readNBytes(len);
        if (payload.length != len) {
            throw new EOFException("truncated frame: got " + payload.length + " of " + len + " bytes");
        }
        return payload;
    }

    /** Writes {@code payload} as one frame and flushes. */
    public static void write(OutputStream out, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD) {
            throw new ProtocolException("frame too large: " + payload.length);
        }
        int len = payload.length;
        out.write(new byte[] {(byte) (len >>> 16), (byte) (len >>> 8), (byte) len});
        out.write(payload);
        out.flush();
    }
}
