package orbit.sniffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import orbit.net.ProtocolException;

/**
 * Interprets packets using the parser JSON, without generated classes. Used by the sniffer
 * for readable logs and to validate the JSON against real traffic; the bot itself will use
 * generated codecs (stage 2).
 *
 * <p>Wire rules (from the client's write methods; read is the exact inverse):
 * <ul>
 *   <li>every module starts with its int16 id;</li>
 *   <li>shifted ints/shorts/bytes are bit-rotated within their width; decoding rotates back;</li>
 *   <li>{@code super_call}: the parent writes its own id, then its fields;</li>
 *   <li>{@code submodule}: a full module (id + fields), or int16 0 for null;</li>
 *   <li>arrays: a length (int/short/byte) followed by primitives or full modules.</li>
 * </ul>
 */
final class PacketDecoder {

    private static final int MAX_DEPTH = 32;
    private static final int MAX_ARRAY = 100_000;

    private final Map<Short, JsonNode> byId = new HashMap<>();
    private final Map<String, JsonNode> byName = new HashMap<>();

    static PacketDecoder load(Path json) throws IOException {
        PacketDecoder d = new PacketDecoder();
        for (JsonNode p : new ObjectMapper().readTree(json.toFile())) {
            d.byId.put((short) p.get("id").asInt(), p);
            d.byName.put(p.get("name").asText(), p);
        }
        return d;
    }

    /** Decoded module: its class name and fields in wire order. */
    record Module(String name, Map<String, Object> fields) {
        @Override
        public String toString() {
            StringBuilder sb = new StringBuilder(name).append('{');
            boolean first = true;
            for (Map.Entry<String, Object> e : fields.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey()).append('=');
                Object v = e.getValue();
                sb.append(v instanceof String s ? '"' + s + '"' : String.valueOf(v));
            }
            return sb.append('}').toString();
        }
    }

    /**
     * Decodes one packet and requires that all bytes are consumed.
     *
     * @throws ProtocolException on unknown ids, truncated data or trailing bytes
     */
    Module decode(byte[] packet) {
        ByteBuffer buf = ByteBuffer.wrap(packet);
        Module m = readModule(buf, 0);
        if (m == null) {
            throw new ProtocolException("packet id 0");
        }
        if (buf.hasRemaining()) {
            throw new ProtocolException(m.name() + ": " + buf.remaining() + " trailing bytes");
        }
        return m;
    }

    private Module readModule(ByteBuffer buf, int depth) {
        if (depth > MAX_DEPTH) {
            throw new ProtocolException("nesting too deep");
        }
        short id = getShort(buf);
        if (id == 0) {
            return null;
        }
        JsonNode def = byId.get(id);
        if (def == null) {
            throw new ProtocolException("unknown module id " + id);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        readBody(def, buf, fields, depth);
        return new Module(def.get("name").asText(), fields);
    }

    private void readBody(JsonNode def, ByteBuffer buf, Map<String, Object> fields, int depth) {
        for (JsonNode f : def.get("writeBody")) {
            String type = f.has("type") ? f.get("type").asText() : f.path("type:").asText();
            String name = f.path("name").asText();
            switch (type) {
                case "super_call" -> {
                    JsonNode parent = byName.get(def.get("base").asText());
                    if (parent == null) {
                        throw new ProtocolException(def.get("name").asText() + ": unknown parent " + def.get("base"));
                    }
                    short pid = getShort(buf);
                    if (pid != (short) parent.get("id").asInt()) {
                        throw new ProtocolException(def.get("name").asText() + ": expected parent id "
                                + parent.get("id").asInt() + ", got " + pid);
                    }
                    readBody(parent, buf, fields, depth + 1);
                }
                case "submodule" -> fields.put(name, readModule(buf, depth + 1));
                case "arrayOfModules" -> {
                    int n = readLength(buf, f.get("length_type").asText());
                    List<Object> list = new ArrayList<>(Math.min(n, 64));
                    for (int i = 0; i < n; i++) {
                        list.add(readModule(buf, depth + 1));
                    }
                    fields.put(name, list);
                }
                case "arrayOfPrimitives" -> {
                    int n = readLength(buf, f.get("length_type").asText());
                    List<Object> list = new ArrayList<>(Math.min(n, 64));
                    for (int i = 0; i < n; i++) {
                        list.add(readPrimitive(buf, f.get("subType").asText(), f));
                    }
                    fields.put(name, list);
                }
                default -> fields.put(name, readPrimitive(buf, type, f));
            }
        }
    }

    private static int readLength(ByteBuffer buf, String lengthType) {
        int n = switch (lengthType.toLowerCase()) {
            case "int" -> getInt(buf);
            case "short" -> getShort(buf);
            case "byte" -> getByte(buf);
            default -> throw new ProtocolException("unknown length type " + lengthType);
        };
        if (n < 0 || n > MAX_ARRAY) {
            throw new ProtocolException("array length out of range: " + n);
        }
        return n;
    }

    private static Object readPrimitive(ByteBuffer buf, String type, JsonNode f) {
        JsonNode shift = f.get("shiftOperationFromClientToServer");
        return switch (type) {
            case "int" -> {
                int v = getInt(buf);
                yield shift == null ? v : unrotate(v, 32, shift);
            }
            case "short" -> {
                int v = getShort(buf) & 0xFFFF;
                yield (short) (shift == null ? v : unrotate(v, 16, shift));
            }
            case "byte" -> {
                int v = getByte(buf) & 0xFF;
                yield (byte) (shift == null ? v : unrotate(v, 8, shift));
            }
            case "boolean" -> getByte(buf) != 0;
            case "double" -> need(buf, 8).getDouble();
            case "float" -> need(buf, 4).getFloat();
            case "utf" -> {
                int len = getShort(buf) & 0xFFFF;
                byte[] b = new byte[len];
                need(buf, len).get(b);
                yield new String(b, StandardCharsets.UTF_8);
            }
            default -> throw new ProtocolException("unknown primitive type " + type);
        };
    }

    /**
     * The client rotated the value before writing; undo it. A write "left n" means
     * {@code v << n | v >>> (w - n)}, so reading rotates right by n (and vice versa).
     */
    static int unrotate(int value, int width, JsonNode shift) {
        int n = shift.get("amount").asInt() % width;
        boolean wroteLeft = shift.get("direction").asText().equals("left");
        long mask = width == 32 ? 0xFFFFFFFFL : (1L << width) - 1;
        long v = value & mask;
        long r = wroteLeft ? (v >>> n | v << (width - n)) : (v << n | v >>> (width - n));
        return (int) (r & mask);
    }

    private static ByteBuffer need(ByteBuffer buf, int n) {
        if (buf.remaining() < n) {
            throw new ProtocolException("truncated: need " + n + " bytes, have " + buf.remaining());
        }
        return buf;
    }

    private static int getInt(ByteBuffer buf) {
        return need(buf, 4).getInt();
    }

    private static short getShort(ByteBuffer buf) {
        return need(buf, 2).getShort();
    }

    private static byte getByte(ByteBuffer buf) {
        return need(buf, 1).get();
    }
}
