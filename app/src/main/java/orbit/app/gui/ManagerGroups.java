package orbit.app.gui;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * The account manager's groups and start sets (docs/PLAN.md «Менеджер: группы»): each account in one group at most (by
 * name), a pinned account keeps a tab of its own, a start set — accounts with the config they start on (none: their own).
 * Kept in {@code local/manager-groups.json} ({@code -Dorbit.manager.groups=…} for another file).
 */
final class ManagerGroups {

    /** A start set: its name, the accounts, the config they start on ({@code null}: each its own). */
    record Preset(String name, List<String> accounts, String config) {
    }

    final Path file;
    final Map<String, String> groupOf = new TreeMap<>();
    final Set<String> pinned = new LinkedHashSet<>();
    final List<Preset> presets = new ArrayList<>();

    ManagerGroups(Path file) {
        this.file = file;
        load();
    }

    /** Who hears that the groups changed (another window's group choice): their tabs follow. */
    private static final List<Runnable> LISTENERS = new java.util.concurrent.CopyOnWriteArrayList<>();

    static void onChange(Runnable r) {
        LISTENERS.add(r);
    }

    /** The file read again (another window saved it). */
    void reload() {
        groupOf.clear();
        pinned.clear();
        presets.clear();
        load();
    }

    static ManagerGroups openDefault() {
        return new ManagerGroups(Path.of(System.getProperty("orbit.manager.groups", "local/manager-groups.json")));
    }

    String group(String account) {
        return groupOf.get(account);
    }

    /** The groups in use, sorted. */
    List<String> groups() {
        return groupOf.values().stream().distinct().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    void setGroup(String account, String group) {
        if (group == null || group.isBlank()) {
            groupOf.remove(account);
        } else {
            groupOf.put(account, group.strip());
        }
        save();
    }

    void setPinned(String account, boolean on) {
        if (on ? pinned.add(account) : pinned.remove(account)) {
            save();
        }
    }

    void putPreset(Preset p) {
        presets.removeIf(x -> x.name().equals(p.name()));
        presets.add(p);
        save();
    }

    void removePreset(String name) {
        if (presets.removeIf(x -> x.name().equals(name))) {
            save();
        }
    }

    /** A removed account leaves its group, its pin and the sets. */
    void forget(String account) {
        groupOf.remove(account);
        pinned.remove(account);
        presets.replaceAll(p -> new Preset(p.name(), p.accounts().stream().filter(a -> !a.equals(account)).toList(), p.config()));
        save();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            JsonNode root = orbit.config.Json.MAPPER.readTree(file.toFile());
            root.path("groups").properties().forEach(e -> groupOf.put(e.getKey(), e.getValue().asText()));
            root.path("pinned").forEach(n -> pinned.add(n.asText()));
            for (JsonNode p : root.path("presets")) {
                List<String> accounts = new ArrayList<>();
                p.path("accounts").forEach(a -> accounts.add(a.asText()));
                String config = p.path("config").isTextual() && !p.path("config").asText().isBlank() ? p.path("config").asText() : null;
                presets.add(new Preset(p.path("name").asText(), List.copyOf(accounts), config));
            }
        } catch (IOException | RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(ManagerGroups.class).warn("Не прочитать {}: {}", file, e.getMessage());
        }
    }

    void save() {
        try {
            ObjectNode root = orbit.config.Json.MAPPER.createObjectNode();
            ObjectNode g = root.putObject("groups");
            groupOf.forEach(g::put);
            ArrayNode pins = root.putArray("pinned");
            pinned.forEach(pins::add);
            ArrayNode ps = root.putArray("presets");
            for (Preset p : presets) {
                ObjectNode o = ps.addObject();
                o.put("name", p.name());
                ArrayNode a = o.putArray("accounts");
                p.accounts().forEach(a::add);
                if (p.config() != null) {
                    o.put("config", p.config());
                }
            }
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            orbit.config.Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), root);
            LISTENERS.forEach(Runnable::run);
        } catch (IOException e) {
            org.slf4j.LoggerFactory.getLogger(ManagerGroups.class).warn("Не сохранить {}: {}", file, e.getMessage());
        }
    }
}
