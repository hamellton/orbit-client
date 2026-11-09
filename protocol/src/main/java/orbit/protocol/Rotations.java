package orbit.protocol;

/** Bit rotations within 8, 16 or 32 bits. */
public final class Rotations {

    private Rotations() {
    }

    /** Rotates the low {@code width} bits of {@code v} left by {@code k}; returns them unsigned. */
    public static int rotl(int v, int k, int width) {
        if (width == 32) {
            return Integer.rotateLeft(v, k);
        }
        int mask = (1 << width) - 1;
        v &= mask;
        k %= width;
        return (v << k | v >>> (width - k)) & mask;
    }

    /** Inverse of {@link #rotl}. */
    public static int rotr(int v, int k, int width) {
        return rotl(v, (width - k % width) % width, width);
    }
}
