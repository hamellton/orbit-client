package orbit.protocol;

/**
 * A protocol's module table: reads the fields of the module with an id (the id is already consumed). The generated
 * {@code orbit.protocol.gen.Packets.REGISTRY} is the current client's; tests read recordings of earlier client builds
 * with theirs ({@code orbit.protocol.old.b<build>.Packets.REGISTRY}).
 */
@FunctionalInterface
public interface Registry {
    Packet readBody(short id, PacketInput in);
}
