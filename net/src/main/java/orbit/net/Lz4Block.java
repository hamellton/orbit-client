package orbit.net;

/**
 * Decoder for the raw LZ4 block format (no frame header), as used inside the
 * game server's downstream compression envelope.
 */
public final class Lz4Block {

    private static final int MIN_MATCH = 4;

    private Lz4Block() {
    }

    /**
     * Decompresses {@code len} bytes of {@code src} starting at {@code off}.
     *
     * @param rawSize exact expected size of the output
     * @throws ProtocolException if the input is malformed or does not produce exactly {@code rawSize} bytes
     */
    public static byte[] decompress(byte[] src, int off, int len, int rawSize) {
        byte[] dst = new byte[rawSize];
        int sp = off;
        int end = off + len;
        int dp = 0;

        while (sp < end) {
            int token = src[sp++] & 0xFF;

            int literalLen = token >>> 4;
            if (literalLen == 15) {
                int b;
                do {
                    if (sp >= end) {
                        throw new ProtocolException("lz4: truncated literal length");
                    }
                    b = src[sp++] & 0xFF;
                    literalLen += b;
                } while (b == 255);
            }
            if (literalLen > end - sp || literalLen > rawSize - dp) {
                throw new ProtocolException("lz4: literal run out of bounds");
            }
            System.arraycopy(src, sp, dst, dp, literalLen);
            sp += literalLen;
            dp += literalLen;

            if (sp == end) {
                // last sequence carries literals only
                if ((token & 0x0F) != 0) {
                    throw new ProtocolException("lz4: dangling match in last sequence");
                }
                break;
            }
            if (end - sp < 2) {
                throw new ProtocolException("lz4: truncated match offset");
            }
            int offset = (src[sp] & 0xFF) | (src[sp + 1] & 0xFF) << 8;
            sp += 2;
            if (offset == 0 || offset > dp) {
                throw new ProtocolException("lz4: invalid match offset " + offset);
            }

            int matchLen = token & 0x0F;
            if (matchLen == 15) {
                int b;
                do {
                    if (sp >= end) {
                        throw new ProtocolException("lz4: truncated match length");
                    }
                    b = src[sp++] & 0xFF;
                    matchLen += b;
                } while (b == 255);
            }
            matchLen += MIN_MATCH;
            if (matchLen > rawSize - dp) {
                throw new ProtocolException("lz4: match run out of bounds");
            }
            int from = dp - offset;
            if (offset >= matchLen) {
                System.arraycopy(dst, from, dst, dp, matchLen);
                dp += matchLen;
            } else {
                // overlapping copy must go byte by byte
                for (int i = 0; i < matchLen; i++) {
                    dst[dp++] = dst[from + i];
                }
            }
        }

        if (dp != rawSize) {
            throw new ProtocolException("lz4: produced " + dp + " bytes, expected " + rawSize);
        }
        return dst;
    }
}
