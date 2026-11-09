package orbit.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;

/**
 * Which config each account runs and its timetable ({@code local/accounts.json}). Nothing secret
 * here: credentials stay in the vault, accounts are named as there.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AccountSettings(Map<String, Entry> accounts) {

    public static final Path DEFAULT_FILE = Path.of("local", "accounts.json");

    public AccountSettings {
        accounts = accounts == null ? Map.of() : BotConfig.sorted(accounts);
    }

    /** @param config the config when no timetable is on (or between its windows, if {@code schedule} is off) */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String config, Schedule schedule) {

        public Entry {
            schedule = schedule == null ? Schedule.OFF : schedule;
        }

        /** The config to run now: the timetable's if it is on, else the account's own; {@code null} = offline. */
        public Schedule.Decision decide(java.time.LocalDateTime now, long seed) {
            if (!schedule.enabled()) {
                return new Schedule.Decision(config, null);
            }
            return schedule.at(now, seed);
        }
    }

    public Entry account(String name) {
        return accounts.getOrDefault(name, new Entry(null, null));
    }

    public AccountSettings with(String name, Entry entry) {
        Map<String, Entry> m = new TreeMap<>(accounts);
        if (entry == null) {
            m.remove(name);
        } else {
            m.put(name, entry);
        }
        return new AccountSettings(m);
    }

    public static AccountSettings load(Path file) throws IOException {
        if (!Files.exists(file)) {
            return new AccountSettings(null);
        }
        return Json.MAPPER.readValue(file.toFile(), AccountSettings.class);
    }

    public void save(Path file) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        Json.MAPPER.writeValue(tmp.toFile(), this);
        try {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
