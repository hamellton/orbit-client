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
        PacketInput in = new PacketInput(packet);
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
