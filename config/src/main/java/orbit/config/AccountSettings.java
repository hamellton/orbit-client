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

    /**
     * @param config    the config when no timetable is on (or between its windows, if {@code schedule} is off)
     * @param hangars   the account's ships as the site listed them at the last login (shown when picking ships)
     * @param palladium the ships of the two-ship palladium sale, {@code null} = not chosen
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Entry(String config, Schedule schedule, java.util.List<Ship> hangars, PalladiumShips palladium) {

        public Entry {
            schedule = schedule == null ? Schedule.OFF : schedule;
            hangars = hangars == null || hangars.isEmpty() ? null : java.util.List.copyOf(hangars);
        }

        public Entry(String config, Schedule schedule) {
            this(config, schedule, null, null);
        }

        public Entry withConfig(String c) {
            return new Entry(c, schedule, hangars, palladium);
        }

        public Entry withSchedule(Schedule s) {
            return new Entry(config, s, hangars, palladium);
        }

        public Entry withHangars(java.util.List<Ship> list) {
            return new Entry(config, schedule, list, palladium);
        }

        public Entry withPalladium(PalladiumShips p) {
            return new Entry(config, schedule, hangars, p);
        }

        /** The ships known from the site ({@code []} until the first login reads them). */
        public java.util.List<Ship> ships() {
            return hangars == null ? java.util.List.of() : hangars;
        }

        /** The config to run now: the timetable's if it is on, else the account's own; {@code null} = offline. */
        public Schedule.Decision decide(java.time.LocalDateTime now, long seed) {
            if (!schedule.enabled()) {
                return new Schedule.Decision(config, null);
            }
            return schedule.at(now, seed);
        }
    }

    /**
     * One hangar of the account: the site's id, its ship ({@code ship_goliath}…) and the design when the site says
     * it ({@code null} otherwise).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Ship(int id, String ship, String design) {

        /** «Goliath», «G-Champion»: the ship's name from its loot id. */
        public String shipName() {
            return prettyName(ship);
        }

        /** «Goliath (Venom)» or «Goliath». */
        public String label() {
            String d = design == null || design.isBlank() ? "" : " (" + prettyName(design) + ")";
            return (ship == null || ship.isBlank() ? "корабль ?" : shipName()) + d;
        }

        static String prettyName(String lootId) {
            if (lootId == null || lootId.isBlank()) {
                return "?";
            }
            String s = lootId.replaceFirst("^(ship|design)_", "").replace('_', ' ');
            StringBuilder b = new StringBuilder(s.length());
            boolean up = true;
            for (char ch : s.toCharArray()) {
                b.append(up ? Character.toUpperCase(ch) : ch);
                up = ch == ' ' || ch == '-';
            }
            return b.toString();
        }
    }

    /** The two-ship palladium sale: the ship that collects on 5-3 and the one parked at 5-2's trader. */
    public record PalladiumShips(int collect, int sell) {

        public boolean usable() {
            return collect > 0 && sell > 0 && collect != sell;
        }
    }

    /** {@code main@ru5} without its own entry takes the one of {@code main}. */
    public Entry account(String name) {
        Entry e = accounts.get(name);
        if (e == null && name.contains("@")) {
            e = accounts.get(name.substring(0, name.indexOf('@')));
        }
        return e != null ? e : new Entry(null, null);
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
