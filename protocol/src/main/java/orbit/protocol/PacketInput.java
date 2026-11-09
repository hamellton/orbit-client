package orbit.protocol;

import java.nio.charset.StandardCharsets;
import orbit.net.ProtocolException;
import orbit.protocol.gen.Packets;

/** Big-endian reader matching AS3 {@code IDataInput}, the inverse of {@link PacketOutput}. */
public final class PacketInput {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ARRAY = 100_000;

    private final byte[] buf;
    private int pos;
    private final int end;
    private int depth;

    public PacketInput(byte[] buf) {
        this(buf, 0, buf.length);
    }

    public PacketInput(byte[] buf, int off, int len) {
        this.buf = buf;
        this.pos = off;
        this.end = off + len;
    }

    public int remaining() {
        return end - pos;
    }

    private void need(int n) {
        if (end - pos < n) {
            throw new ProtocolException("truncated: need " + n + " bytes, have " + (end - pos));
        }
    }

    public byte readByte() {
        need(1);
        return buf[pos++];
    }

    public short readShort() {
        need(2);
        int v = (buf[pos] & 0xFF) << 8 | buf[pos + 1] & 0xFF;
        pos += 2;
        return (short) v;
    }

    public int readInt() {
        need(4);
        int v = (buf[pos] & 0xFF) << 24 | (buf[pos + 1] & 0xFF) << 16 | (buf[pos + 2] & 0xFF) << 8 | buf[pos + 3] & 0xFF;
        pos += 4;
        return v;
    }

    public boolean readBoolean() {
        return readByte() != 0;
    }

    public double readDouble() {
        long hi = readInt() & 0xFFFFFFFFL;
        long lo = readInt() & 0xFFFFFFFFL;
        return Double.longBitsToDouble(hi << 32 | lo);
    }

    public float readFloat() {
        return Float.intBitsToFloat(readInt());
    }

    public String readUTF() {
        int len = readShort() & 0xFFFF;
        need(len);
        String s = new String(buf, pos, len, StandardCharsets.UTF_8);
        pos += len;
        return s;
    }

    public int readIntRot(int k) {
        return Integer.rotateRight(readInt(), k);
    }

    public short readShortRot(int k) {
        return (short) Rotations.rotr(readShort() & 0xFFFF, k, 16);
    }

    public byte readByteRot(int k) {
        return (byte) Rotations.rotr(readByte() & 0xFF, k, 8);
    }

    public int readLength(LengthType type) {
        int n = switch (type) {
            case INT -> readInt();
            case SHORT -> readShort();
            case BYTE -> readByte();
        };
        if (n < 0 || n > MAX_ARRAY) {
            throw new ProtocolException("array length out of range: " + n);
        }
        return n;
    }

    /** Reads the parent id written by {@code super.write} and checks it. */
    public void expectId(short expected) {
        short id = readShort();
        if (id != expected) {
            throw new ProtocolException("expected parent module id " + expected + ", got " + id);
        }
    }

    /**
     * Reads a nested module (id + fields) or {@code null} for id 0, and checks it is a {@code type}.
     */
    public <T extends Packet> T readModule(Class<T> type) {
        short id = readShort();
        if (id == 0) {
            return null;
        }
        if (++depth > MAX_DEPTH) {
            throw new ProtocolException("modules nested too deep");
        }
        try {
            Packet p = Packets.readBody(id, this);
            if (!type.isInstance(p)) {
                throw new ProtocolException("module " + p.getClass().getSimpleName() + " is not a " + type.getSimpleName());
            }
            return type.cast(p);
        } finally {
            depth--;
        }
    }
}
