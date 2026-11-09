package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import orbit.protocol.gen.Packets;
import orbit.protocol.gen.ProtocolVersion;
import org.junit.jupiter.api.Test;

/**
 * For every generated module: fill it with random values, encode, decode, encode again and
 * require identical bytes and full consumption.
 */
class RoundTripAllModulesTest {

    private static final int MAX_DEPTH = 3;

    @Test
    void everyModuleRoundTrips() throws Exception {
        short[] ids = Packets.ids();
        assertEquals(ProtocolVersion.PACKET_COUNT, ids.length);
        Random rnd = new Random(20261109);
        int checked = 0;
        for (short id : ids) {
            for (int run = 0; run < 3; run++) {
                Packet original = randomModule(id, rnd, 0);
                byte[] first = PacketCodec.encode(original);
                Packet decoded = PacketCodec.decode(first);
                assertEquals(original.getClass(), decoded.getClass(), Packets.name(id));
                assertArrayEquals(first, PacketCodec.encode(decoded), Packets.name(id) + " run " + run);
                checked++;
            }
        }
        assertEquals(ids.length * 3, checked);
    }

    @Test
    void rotationsAreInverse() {
        int[] samples = {0, 1, -1, 21000, Integer.MIN_VALUE, Integer.MAX_VALUE, 0x7F, 0x8000};
        for (int width : new int[] {8, 16, 32}) {
            for (int k = 0; k < width; k++) {
                for (int v : samples) {
                    int masked = width == 32 ? v : v & ((1 << width) - 1);
                    assertEquals(masked, Rotations.rotr(Rotations.rotl(v, k, width), k, width), width + "/" + k);
                }
            }
        }
    }

    @Test
    void versionMetadataIsPresent() {
        assertEquals(32, ProtocolVersion.VERSION_HASH.length());
        assertNotNull(ProtocolVersion.BUILD);
        assertEquals("do-lz4-block-v3", ProtocolVersion.COMPRESSION);
    }

    private static Packet randomModule(short id, Random rnd, int depth) throws Exception {
        Class<?> cls = Class.forName("orbit.protocol.gen." + Packets.name(id));
        Packet p = (Packet) cls.getDeclaredConstructor().newInstance();
        fill(p, rnd, depth);
        return p;
    }

    private static void fill(Object p, Random rnd, int depth) throws Exception {
        for (Class<?> c = p.getClass(); c != AbstractPacket.class; c = c.getSuperclass()) {
            for (Field f : c.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || !Modifier.isPublic(f.getModifiers())) {
                    continue;
                }
                f.set(p, randomValue(f.getType(), f.getGenericType(), rnd, depth));
            }
        }
    }

    private static Object randomValue(Class<?> type, Type generic, Random rnd, int depth) throws Exception {
        if (type == int.class || type == Integer.class) {
            return rnd.nextInt();
        }
        if (type == short.class || type == Short.class) {
            return (short) rnd.nextInt();
        }
        if (type == byte.class || type == Byte.class) {
            return (byte) rnd.nextInt();
        }
        if (type == boolean.class || type == Boolean.class) {
            return rnd.nextBoolean();
        }
        if (type == double.class || type == Double.class) {
            return rnd.nextDouble() * 1e6 - 5e5;
        }
        if (type == float.class || type == Float.class) {
            return rnd.nextFloat() * 1000f;
        }
        if (type == String.class) {
            return rnd.nextBoolean() ? "" : "тест-" + rnd.nextInt(1000);
        }
        if (type == List.class) {
            Type elem = ((ParameterizedType) generic).getActualTypeArguments()[0];
            Class<?> elemClass = (Class<?>) elem;
            List<Object> list = new ArrayList<>();
            int n = depth >= MAX_DEPTH ? 0 : rnd.nextInt(3);
            for (int i = 0; i < n; i++) {
                Object v = randomValue(elemClass, elemClass, rnd, depth + 1);
                if (v != null) {
                    list.add(v);
                }
            }
            return list;
        }
        if (Packet.class.isAssignableFrom(type)) {
            if (depth >= MAX_DEPTH || type == Packet.class || rnd.nextInt(4) == 0) {
                return null;
            }
            short id = type.getField("MODULE_ID").getShort(null);
            return randomModule(id, rnd, depth + 1);
        }
        throw new IllegalStateException("unsupported field type " + type);
    }
}
