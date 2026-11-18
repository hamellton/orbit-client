package orbit.protocol;

import orbit.net.ProtocolException;

/** Whole-packet encode/decode with strict length checking. */
public final class PacketCodec {

    private PacketCodec() {
    }

    /**
     * Decodes one packet ({@code [id][fields]}) and requires that every byte is consumed.
     *
     * @throws ProtocolException for unknown ids, truncated input or trailing bytes
     */
    public static Packet decode(byte[] packet) {
        return decode(packet, null);
    }

    /** The same by another protocol (a recording of an earlier client build); {@code null} = the current one. */
    public static Packet decode(byte[] packet, Registry registry) {
        PacketInput in = new PacketInput(packet, 0, packet.length, registry);
        Packet p = in.readModule(Packet.class);
        if (p == null) {
            throw new ProtocolException("packet id 0");
        }
        if (in.remaining() != 0) {
            throw new ProtocolException(p.getClass().getSimpleName() + ": " + in.remaining() + " trailing bytes");
        }
        return p;
    }

    public static byte[] encode(Packet p) {
        PacketOutput out = new PacketOutput();
        p.write(out);
        return out.toByteArray();
    }
}
