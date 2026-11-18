package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;
import orbit.net.Recording;
import orbit.protocol.old.Legacy;
import org.junit.jupiter.api.Test;

/** A recording of an earlier client build is read by its own protocol and turned into the current classes. */
class RecordedTest {

    static final Legacy.Protocol OLD = Legacy.ALL.stream().filter(p -> p.build().equals("81367938")).findFirst()
            .orElseThrow();

    @Test
    void theBuildIsToldByTheVersionHashTheClientSent() {
        byte[] old = ("xx" + OLD.versionHash()).getBytes(StandardCharsets.ISO_8859_1);
        byte[] now = ("xx" + orbit.protocol.gen.ProtocolVersion.VERSION_HASH).getBytes(StandardCharsets.ISO_8859_1);
        assertSame(OLD, Recorded.protocolOf(List.of(new Recording.PacketRecord(0, true, old))));
        assertNull(Recorded.protocolOf(List.of(new Recording.PacketRecord(0, true, now))), "the current build");
        assertNull(Recorded.protocolOf(List.of(new Recording.PacketRecord(0, false, old))), "only the client's word counts");
    }

    @Test
    void anOldPacketBecomesTheCurrentClassFieldByField() {
        var old = new orbit.protocol.old.b81367938.MapAddPOICommand();
        old.poiId = "rad";
        old.shape = 2;
        old.active = true;
        old.shapeCoordinates.addAll(List.of(1, 2, 3, 4));
        old.poiType = new orbit.protocol.old.b81367938.POITypeModule();
        old.poiType.typeValue = orbit.protocol.old.b81367938.POITypeModule.POITYPE_RADIATION;
        byte[] bytes = PacketCodec.encode(old);
        assertNotEquals(orbit.protocol.old.b81367938.MapAddPOICommand.MODULE_ID, orbit.protocol.gen.MapAddPOICommand.MODULE_ID,
                "the patch changed the id");
        assertFalse(PacketCodec.decode(bytes, OLD.registry()) instanceof orbit.protocol.gen.MapAddPOICommand);
        Packet p = Recorded.toCurrent(PacketCodec.decode(bytes, OLD.registry()));
        assertTrue(p instanceof orbit.protocol.gen.MapAddPOICommand);
        var now = (orbit.protocol.gen.MapAddPOICommand) p;
        assertEquals("rad", now.poiId);
        assertEquals(2, now.shape);
        assertTrue(now.active);
        assertEquals(List.of(1, 2, 3, 4), now.shapeCoordinates);
        assertEquals(orbit.protocol.gen.POITypeModule.POITYPE_RADIATION, now.poiType.typeValue);
        assertTrue(now.poiType instanceof orbit.protocol.gen.POITypeModule);
    }
}
