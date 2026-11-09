package orbit.net;

import java.io.ByteArrayOutputStream;

/** Minimal greedy LZ4 block encoder used only to produce test vectors. */
final class TestLz4Encoder {

    private TestLz4Encoder() {
    }

    static byte[] compress(byte[] src) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int[] table = new int[1 << 12];
        java.util.Arrays.fill(table, -1);
        int anchor = 0;
        int p = 0;
        // LZ4 requires the last 5 bytes to be literals and matches to start >= 12 bytes from the end
        int matchLimit = src.length - 12;
        while (p < matchLimit) {
            int h = hash(src, p);
            int cand = table[h];
            table[h] = p;
            if (cand >= 0 && p - cand <= 0xFFFF && same4(src, cand, p)) {
                int len = 4;
                while (p + len < src.length - 5 && src[cand + len] == src[p + len]) {
                    len++;
                }
                emit(out, src, anchor, p - anchor, p - cand, len);
                p += len;
                anchor = p;
            } else {
                p++;
            }
        }
        emitLast(out, src, anchor, src.length - anchor);
        return out.toByteArray();
    }

    private static void emit(ByteArrayOutputStream out, byte[] src, int litStart, int litLen, int offset, int matchLen) {
        int ml = matchLen - 4;
        out.write((Math.min(litLen, 15) << 4) | Math.min(ml, 15));
        writeLen(out, litLen);
        out.write(src, litStart, litLen);
        out.write(offset & 0xFF);
        out.write(offset >>> 8);
        writeLen(out, ml);
    }

    private static void emitLast(ByteArrayOutputStream out, byte[] src, int litStart, int litLen) {
        out.write(Math.min(litLen, 15) << 4);
        writeLen(out, litLen);
        out.write(src, litStart, litLen);
    }

    private static void writeLen(ByteArrayOutputStream out, int len) {
        if (len < 15) {
            return;
        }
        len -= 15;
        while (len >= 255) {
            out.write(255);
            len -= 255;
        }
        out.write(len);
    }

    private static int hash(byte[] b, int p) {
        int v = (b[p] & 0xFF) | (b[p + 1] & 0xFF) << 8 | (b[p + 2] & 0xFF) << 16 | (b[p + 3] & 0xFF) << 24;
        return (v * -1640531535) >>> 20;
    }

    private static boolean same4(byte[] b, int a, int c) {
        return b[a] == b[c] && b[a + 1] == b[c + 1] && b[a + 2] == b[c + 2] && b[a + 3] == b[c + 3];
    }
}
