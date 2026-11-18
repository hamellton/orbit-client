package orbit.protocol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import orbit.net.Recording;
import orbit.protocol.old.Legacy;

/**
 * Recordings of any client build for the tests: a game patch changes the module ids and the field order (2026-11-18:
 * 998 ids, 493 field orders), so a recording is read by the protocol of the build it was made with — told by the
 * {@code versionHash} its client sent — and its packets are turned into the current classes by field names (the names
 * stay; a widened type is converted).
 */
public final class Recorded {

    private static final String CURRENT = "orbit.protocol.gen";

    private Recorded() {
    }

    /** The earlier protocol a recording was made with, or {@code null} for the current client's (or not told). */
    public static Legacy.Protocol protocolOf(List<Recording.PacketRecord> packets) {
        String current = orbit.protocol.gen.ProtocolVersion.VERSION_HASH;
        for (Recording.PacketRecord r : packets) {
            if (!r.up()) {
                continue;
            }
            String text = new String(r.bytes(), StandardCharsets.ISO_8859_1);
            if (text.contains(current)) {
                return null;
            }
            for (Legacy.Protocol p : Legacy.ALL) {
                if (text.contains(p.versionHash())) {
                    return p;
                }
            }
        }
        return null;
    }

    /** The recording's protocol table ({@code null} = the current one) for {@link PacketCodec#decode(byte[], Registry)}. */
    public static Registry registryOf(List<Recording.PacketRecord> packets) {
        Legacy.Protocol p = protocolOf(packets);
        return p == null ? null : p.registry();
    }

    /** Decodes a packet of these recorded packets into the current classes. */
    public static Function<byte[], Packet> decoder(List<Recording.PacketRecord> packets) {
        Registry r = registryOf(packets);
        return r == null ? PacketCodec::decode : bytes -> toCurrent(PacketCodec.decode(bytes, r));
    }

    private static final Map<Class<?>, Class<?>> CLASSES = new ConcurrentHashMap<>();

    /** An earlier build's packet as the current class (the same name), field by field. */
    public static Packet toCurrent(Packet old) {
        if (old == null || old.getClass().getPackageName().equals(CURRENT)) {
            return old;
        }
        try {
            Class<?> to = CLASSES.computeIfAbsent(old.getClass(), c -> {
                try {
                    return Class.forName(CURRENT + "." + c.getSimpleName());
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException("в текущем протоколе нет " + c.getSimpleName());
                }
            });
            Object out = to.getDeclaredConstructor().newInstance();
            for (Field f : old.getClass().getFields()) {
                if (Modifier.isStatic(f.getModifiers())) {
                    continue;
                }
                Field t;
                try {
                    t = to.getField(f.getName());
                } catch (NoSuchFieldException gone) {
                    continue;
                }
                Object v = convert(f.get(old), t.getType(), t.getGenericType());
                if (v != null || !t.getType().isPrimitive()) {
                    t.set(out, v);
                }
            }
            return (Packet) out;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(old.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    private static Object convert(Object v, Class<?> type, Type generic) {
        if (v == null) {
            return null;
        }
        if (v instanceof Packet p) {
            return toCurrent(p);
        }
        if (v instanceof List<?> list) {
            Type element = generic instanceof ParameterizedType pt ? pt.getActualTypeArguments()[0] : Object.class;
            Class<?> elementClass = element instanceof Class<?> c ? c : Object.class;
            List<Object> out = new ArrayList<>(list.size());
            for (Object e : list) {
                out.add(convert(e, elementClass, element));
            }
            return out;
        }
        if (v instanceof Number n) {
            return number(n, type);
        }
        return v;
    }

    private static Object number(Number n, Class<?> type) {
        if (type == int.class || type == Integer.class) {
            return n.intValue();
        }
        if (type == short.class || type == Short.class) {
            return n.shortValue();
        }
        if (type == byte.class || type == Byte.class) {
            return n.byteValue();
        }
        if (type == long.class || type == Long.class) {
            return n.longValue();
        }
        if (type == double.class || type == Double.class) {
            return n.doubleValue();
        }
        if (type == float.class || type == Float.class) {
            return n.floatValue();
        }
        return n;
    }
}
