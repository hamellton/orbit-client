package orbit.protocolgen;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Normalised view of the parser JSON, validated before any code is emitted. */
record ProtocolModel(List<Module> modules, Map<String, Module> byName) {

    private static final Set<String> JAVA_KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class", "const",
            "continue", "default", "do", "double", "else", "enum", "extends", "final", "finally", "float",
            "for", "goto", "if", "implements", "import", "instanceof", "int", "interface", "long", "native",
            "new", "package", "private", "protected", "public", "return", "short", "static", "strictfp",
            "super", "switch", "synchronized", "this", "throw", "throws", "transient", "try", "void",
            "volatile", "while", "var", "record", "yield", "true", "false", "null", "MODULE_ID");

    enum Kind { PRIMITIVE, MODULE, PRIMITIVE_ARRAY, MODULE_ARRAY, SUPER }

    /** Primitive wire type with its bit width (0 = not rotatable). */
    enum Prim {
        INT(32), SHORT(16), BYTE(8), BOOLEAN(0), DOUBLE(0), FLOAT(0), UTF(0);

        final int width;

        Prim(int width) {
            this.width = width;
        }

        static Prim of(String s) {
            return switch (s.toLowerCase()) {
                case "int" -> INT;
                case "short" -> SHORT;
                case "byte" -> BYTE;
                case "boolean" -> BOOLEAN;
                case "double" -> DOUBLE;
                case "float" -> FLOAT;
                case "utf" -> UTF;
                default -> throw new IllegalArgumentException("unknown primitive type " + s);
            };
        }
    }

    /**
     * One wire step of a module body.
     *
     * @param rotateLeft rotation applied before writing, as "rotate left by k" within the width; 0 = none
     * @param moduleType declared class name for module fields/arrays, or {@code null} for any module
     * @param lengthType INT/SHORT/BYTE for arrays
     */
    record Step(Kind kind, String field, Prim prim, int rotateLeft, String moduleType, Prim lengthType) {
    }

    record Module(String name, short id, String parent, List<Step> steps) {
    }

    static ProtocolModel from(JsonNode packets) {
        Map<String, JsonNode> raw = new LinkedHashMap<>();
        for (JsonNode p : packets) {
            String name = p.get("name").asText();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException("invalid class name " + name);
            }
            if (raw.put(name, p) != null) {
                throw new IllegalArgumentException("duplicate packet name " + name);
            }
        }
        Map<String, String> lowerToName = new HashMap<>();
        raw.keySet().forEach(n -> lowerToName.put(n.toLowerCase(), n));

        Map<Short, String> ids = new HashMap<>();
        List<Module> modules = new ArrayList<>();
        Map<String, Module> byName = new HashMap<>();
        for (JsonNode p : raw.values()) {
            String name = p.get("name").asText();
            short id = (short) p.get("id").asInt();
            String prev = ids.put(id, name);
            if (prev != null) {
                throw new IllegalArgumentException("duplicate id " + id + ": " + prev + ", " + name);
            }
            String base = p.path("base").asText();
            String parent = raw.containsKey(base) ? base : null;

            Map<String, String> declared = new HashMap<>();
            for (JsonNode f : p.path("fields")) {
                declared.put(f.get("name").asText(), f.get("type").asText());
            }

            List<Step> steps = new ArrayList<>();
            for (JsonNode d : p.get("writeBody")) {
                String type = d.has("type") ? d.get("type").asText() : d.path("type:").asText();
                String field = d.path("name").asText();
                switch (type) {
                    case "super_call" -> {
                        if (parent == null) {
                            throw new IllegalArgumentException(name + ": super.write but parent " + base + " is not a packet");
                        }
                        steps.add(new Step(Kind.SUPER, null, null, 0, parent, null));
                    }
                    case "submodule" -> steps.add(new Step(Kind.MODULE, javaName(field), null, 0,
                            resolveClass(declared.get(field), lowerToName), null));
                    case "arrayOfModules" -> steps.add(new Step(Kind.MODULE_ARRAY, javaName(field), null, 0,
                            resolveVectorClass(declared.get(field), lowerToName),
                            Prim.of(d.get("length_type").asText())));
                    case "arrayOfPrimitives" -> {
                        Prim sub = Prim.of(d.get("subType").asText());
                        steps.add(new Step(Kind.PRIMITIVE_ARRAY, javaName(field), sub, rotation(d, sub),
                                null, Prim.of(d.get("length_type").asText())));
                    }
                    default -> {
                        Prim prim = Prim.of(type);
                        steps.add(new Step(Kind.PRIMITIVE, javaName(field), prim, rotation(d, prim), null, null));
                    }
                }
            }
            Module m = new Module(name, id, parent, List.copyOf(steps));
            modules.add(m);
            byName.put(name, m);
        }
        checkNoInheritanceCycles(byName);
        return new ProtocolModel(List.copyOf(modules), Map.copyOf(byName));
    }

    /** Converts the client's left/right shift into "rotate left by k". */
    private static int rotation(JsonNode d, Prim prim) {
        JsonNode s = d.get("shiftOperationFromClientToServer");
        if (s == null) {
            return 0;
        }
        if (prim.width == 0) {
            throw new IllegalArgumentException("rotation on non-integer field " + d);
        }
        int n = s.get("amount").asInt() % prim.width;
        return switch (s.get("direction").asText()) {
            case "left" -> n;
            case "right" -> (prim.width - n) % prim.width;
            default -> throw new IllegalArgumentException("bad shift direction in " + d);
        };
    }

    private static String resolveClass(String declared, Map<String, String> lowerToName) {
        return declared == null ? null : lowerToName.get(declared.toLowerCase());
    }

    private static String resolveVectorClass(String declared, Map<String, String> lowerToName) {
        if (declared == null || !declared.startsWith("vector.<") || !declared.endsWith(">")) {
            return null;
        }
        return lowerToName.get(declared.substring(8, declared.length() - 1));
    }

    static String javaName(String field) {
        return JAVA_KEYWORDS.contains(field) ? field + "_" : field;
    }

    private static void checkNoInheritanceCycles(Map<String, Module> byName) {
        for (Module m : byName.values()) {
            int depth = 0;
            for (String p = m.parent(); p != null; p = byName.get(p).parent()) {
                if (++depth > 64) {
                    throw new IllegalArgumentException("inheritance cycle at " + m.name());
                }
            }
        }
    }
}
