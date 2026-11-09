package orbit.protocol;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import orbit.net.ProtocolException;

/**
 * Big-endian writer matching AS3 {@code IDataOutput}. Rotations are given as
 * "rotate left by k" within the value's width (the generator normalises the client's
 * left/right shifts to this form).
 */
public final class PacketOutput {

    private byte[] buf = new byte[64];
    private int size;

    public byte[] toByteArray() {
        return Arrays.copyOf(buf, size);
    }

    public int size() {
        return size;
    }

    private void ensure(int n) {
        if (size + n > buf.length) {
            buf = Arrays.copyOf(buf, Math.max(buf.length * 2, size + n));
        }
    }

    public void writeByte(int v) {
        ensure(1);
        buf[size++] = (byte) v;
    }

    public void writeShort(int v) {
        ensure(2);
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) v;
    }

    public void writeInt(int v) {
        ensure(4);
        buf[size++] = (byte) (v >>> 24);
        buf[size++] = (byte) (v >>> 16);
        buf[size++] = (byte) (v >>> 8);
        buf[size++] = (byte) v;
    }

    public void writeBoolean(boolean v) {
        writeByte(v ? 1 : 0);
    }

    public void writeDouble(double v) {
        long l = Double.doubleToRawLongBits(v);
        writeInt((int) (l >>> 32));
        writeInt((int) l);
    }

    public void writeFloat(float v) {
        writeInt(Float.floatToRawIntBits(v));
    }

    /** AS3 {@code writeUTF}: uint16 byte length, then UTF-8 bytes. */
    public void writeUTF(String s) {
        byte[] b = (s == null ? "" : s).getBytes(StandardCharsets.UTF_8);
        if (b.length > 0xFFFF) {
            throw new ProtocolException("string too long: " + b.length + " bytes");
        }
        writeShort(b.length);
        ensure(b.length);
        System.arraycopy(b, 0, buf, size, b.length);
        size += b.length;
    }

    public void writeIntRot(int v, int k) {
        writeInt(Integer.rotateLeft(v, k));
    }

    public void writeShortRot(short v, int k) {
        writeShort(Rotations.rotl(v & 0xFFFF, k, 16));
    }

    public void writeByteRot(byte v, int k) {
        writeByte(Rotations.rotl(v & 0xFF, k, 8));
    }

    /** A nested module, or int16 0 for {@code null}. */
    public void writeModule(Packet p) {
        if (p == null) {
            writeShort(0);
        } else {
            p.write(this);
        }
    }

    /** Writes an array length using the given wire type ({@code int}, {@code short} or {@code byte}). */
    public void writeLength(List<?> list, LengthType type) {
        int n = list == null ? 0 : list.size();
        switch (type) {
            case INT -> writeInt(n);
            case SHORT -> {
                checkRange(n, 0x7FFF);
                writeShort(n);
            }
            case BYTE -> {
                checkRange(n, 0x7F);
                writeByte(n);
            }
        }
    }

    private static void checkRange(int n, int max) {
        if (n > max) {
            throw new ProtocolException("array too long for its length field: " + n);
        }
    }
}
