package orbit.sniffer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** Packet id to class name lookup, loaded from the parser JSON. */
final class PacketNames {

    private final Map<Short, String> names = new HashMap<>();

    static PacketNames load(Path json) throws IOException {
        PacketNames pn = new PacketNames();
        for (JsonNode p : new ObjectMapper().readTree(json.toFile())) {
            pn.names.put((short) p.get("id").asInt(), p.get("name").asText());
        }
        return pn;
    }

    String name(short id) {
        return names.getOrDefault(id, "?" + id);
    }

    boolean known(short id) {
        return names.containsKey(id);
    }

    int size() {
        return names.size();
    }
}
