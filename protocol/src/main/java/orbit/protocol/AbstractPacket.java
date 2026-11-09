package orbit.protocol;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Base class of generated modules: readable {@link #toString()} over public fields, parents first. */
public abstract class AbstractPacket implements Packet {

    @Override
    public String toString() {
        List<Class<?>> chain = new ArrayList<>();
        for (Class<?> c = getClass(); c != AbstractPacket.class; c = c.getSuperclass()) {
            chain.addFirst(c);
        }
        StringBuilder sb = new StringBuilder(getClass().getSimpleName()).append('{');
        boolean first = true;
        for (Class<?> c : chain) {
            for (Field f : c.getDeclaredFields()) {
                int mod = f.getModifiers();
                if (Modifier.isStatic(mod) || !Modifier.isPublic(mod)) {
                    continue;
                }
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                try {
                    Object v = f.get(this);
                    sb.append(f.getName()).append('=').append(v instanceof String s ? '"' + s + '"' : v);
                } catch (IllegalAccessException e) {
                    sb.append(f.getName()).append("=?");
                }
            }
        }
        return sb.append('}').toString();
    }
}
