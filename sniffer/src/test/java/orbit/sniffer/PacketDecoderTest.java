package orbit.sniffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import orbit.net.ProtocolException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PacketDecoderTest {

    @TempDir
    Path tmp;

    /** Same rotation the client applies before writing an int field. */
    private static int clientRotate32(int v, String dir, int n) {
        return dir.equals("left") ? (v << n | v >>> (32 - n)) : (v >>> n | v << (32 - n));
    }

    private static int clientRotate16(int v, String dir, int n) {
        v &= 0xFFFF;
        return 0xFFFF & (dir.equals("left") ? (v << n | v >>> (16 - n)) : (v >>> n | v << (16 - n)));
    }

    @Test
    void unrotateInvertsClientRotationForAllWidthsAndAmounts() throws Exception {
        ObjectMapper om = new ObjectMapper();
        int[] samples = {0, 1, -1, 123456789, Integer.MIN_VALUE, 21000, -32768, 32767, 0x7F};
        for (String dir : new String[] {"left", "right"}) {
            for (int n = 1; n < 32; n++) {
                var shift = om.readTree("{\"direction\":\"" + dir + "\",\"amount\":" + n + "}");
                for (int v : samples) {
                    assertEquals(v, PacketDecoder.unrotate(clientRotate32(v, dir, n), 32, shift), dir + n + " " + v);
                    if (n < 16) {
                        short s = (short) v;
                        assertEquals(s & 0xFFFF,
                                PacketDecoder.unrotate(clientRotate16(s, dir, n), 16, shift), dir + n + " short " + s);
                    }
                }
            }
        }
    }

    @Test
    void decodesInheritanceSubmodulesAndArrays() throws Exception {
        Path json = tmp.resolve("p.json");
        Files.writeString(json, """
                [
                 {"id":100,"name":"Parent","base":"IModule","writeBody":[
                   {"name":"x","type":"int","shiftOperationFromClientToServer":{"direction":"left","amount":5}},
                   {"name":"tag","type":"utf"}]},
                 {"id":200,"name":"Child","base":"Parent","writeBody":[
                   {"name":"super_call","type:":"super_call"},
                   {"name":"kind","type:":"submodule"},
                   {"name":"none","type:":"submodule"},
                   {"name":"ids","type":"arrayOfPrimitives","length_type":"Short","subType":"int",
                    "shiftOperationFromClientToServer":{"direction":"right","amount":3}},
                   {"name":"items","type":"arrayOfModules","length_type":"Int"},
                   {"name":"hp","type":"short","shiftOperationFromClientToServer":{"direction":"right","amount":9}},
                   {"name":"on","type":"boolean"},
                   {"name":"speed","type":"double"}]},
                 {"id":300,"name":"Kind","base":"IModule","writeBody":[{"name":"type","type":"byte"}]}
                ]""");
        PacketDecoder d = PacketDecoder.load(json);

        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream o = new DataOutputStream(b);
        o.writeShort(200);                               // Child id
        o.writeShort(100);                               // super.write: Parent id
        o.writeInt(clientRotate32(-15911, "left", 5));   // Parent.x
        o.writeUTF("pa");                                // Parent.tag
        o.writeShort(300);                               // kind = Kind{type=7}
        o.writeByte(7);
        o.writeShort(0);                                 // none = null
        o.writeShort(2);                                 // ids
        o.writeInt(clientRotate32(150200182, "right", 3));
        o.writeInt(clientRotate32(-2, "right", 3));
        o.writeInt(1);                                   // items: one Kind
        o.writeShort(300);
        o.writeByte(9);
        o.writeShort(clientRotate16(-1234, "right", 9)); // hp
        o.writeBoolean(true);
        o.writeDouble(1.5);

        PacketDecoder.Module m = d.decode(b.toByteArray());
        assertEquals("Child", m.name());
        assertEquals(-15911, m.fields().get("x"));
        assertEquals("pa", m.fields().get("tag"));
        assertEquals("Kind{type=7}", m.fields().get("kind").toString());
        assertNull(m.fields().get("none"));
        assertEquals(List.of(150200182, -2), m.fields().get("ids"));
        assertEquals("[Kind{type=9}]", m.fields().get("items").toString());
        assertEquals((short) -1234, m.fields().get("hp"));
        assertEquals(true, m.fields().get("on"));
        assertEquals(1.5, m.fields().get("speed"));
    }

    @Test
    void rejectsTrailingAndTruncatedBytesAndUnknownIds() throws Exception {
        Path json = tmp.resolve("p.json");
        Files.writeString(json, "[{\"id\":5,\"name\":\"A\",\"writeBody\":[{\"name\":\"v\",\"type\":\"int\"}]}]");
        PacketDecoder d = PacketDecoder.load(json);
        assertEquals(258, d.decode(new byte[] {0, 5, 0, 0, 1, 2}).fields().get("v"));
        assertThrows(ProtocolException.class, () -> d.decode(new byte[] {0, 5, 0, 0, 1, 2, 9}));
        assertThrows(ProtocolException.class, () -> d.decode(new byte[] {0, 5, 0, 0, 1}));
        assertThrows(ProtocolException.class, () -> d.decode(new byte[] {0, 6}));
    }
}
