package orbit.protocol;

/**
 * A game protocol module (packet or nested structure). Implementations are generated
 * from the parser JSON into {@code orbit.protocol.gen}.
 */
public interface Packet {

    /** The int16 module id written at the start of the module. */
    short moduleId();

    /** Writes the module including its leading id. */
    void write(PacketOutput out);
}
