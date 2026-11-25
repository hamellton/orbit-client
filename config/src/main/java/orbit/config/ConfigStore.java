package orbit.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Named configs as files {@code <dir>/<name>.json} or {@code <dir>/<folder>/<name>.json} (by default
 * {@code local/configs}), and the program's own default configs in the read-only folder {@link #DEFAULT_FOLDER}: proven
 * configs, closed for editing, to be copied and changed.
 *
 * <p>A name is unique over all folders (the accounts keep a config by its name alone), so moving a config between
 * folders changes nothing for them. Saves go through a temporary file, so a crash never leaves half a config.
 */
public final class ConfigStore {

    public static final Path DEFAULT_DIR = Path.of("local", "configs");
    /** The folder of the program's default configs: shown first, never written. */
    public static final String DEFAULT_FOLDER = "Default";
    /** The bundled defaults: {@code index.txt} lists their names, one file each beside it. */
    static final String DEFAULTS = "/orbit/config/defaults/";
    /**
     * The folder of the program's leveling configs (docs/LEVELING.md): read-only like {@link #DEFAULT_FOLDER}; an account on
     * one of them runs the leveling mode.
     */
    public static final String LEVELING_FOLDER = "Прокачка";
    static final String LEVELING = "/orbit/config/leveling/";
    /** Letters (any script), digits, space, {@code _ - .}; no path separators. */
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_\\-. ]{1,64}");

    private final Path dir;
    private final Map<String, String> defaults;
    /** The program folder of each default: {@link #DEFAULT_FOLDER} or {@link #LEVELING_FOLDER}. */
    private final Map<String, String> defaultFolder;

    public ConfigStore(Path dir) {
        this(dir, true);
    }

    /** @param withDefaults the program's default configs too (tests of the plain store leave them out) */
    public ConfigStore(Path dir, boolean withDefaults) {
        this.dir = dir;
        Map<String, String> d = new LinkedHashMap<>();
        Map<String, String> f = new LinkedHashMap<>();
        if (withDefaults) {
            bundled(DEFAULTS).forEach((n, json) -> {
                d.put(n, json);
                f.put(n, DEFAULT_FOLDER);
            });
            bundled(LEVELING).forEach((n, json) -> {
                if (d.putIfAbsent(n, json) == null) {
                    f.put(n, LEVELING_FOLDER);
                }
            });
        }
        this.defaults = d;
        this.defaultFolder = f;
    }

    /** A folder of the program's own configs ({@link #DEFAULT_FOLDER}, {@link #LEVELING_FOLDER}): shown locked, never written. */
    public static boolean isProgramFolder(String folder) {
        return DEFAULT_FOLDER.equals(folder) || LEVELING_FOLDER.equals(folder);
    }

    public static ConfigStore openDefault() {
        return new ConfigStore(DEFAULT_DIR);
    }

    public Path dir() {
        return dir;
    }

    public static void checkName(String name) {
        if (name == null || !NAME.matcher(name).matches() || name.startsWith(".") || name.isBlank()) {
            throw new IllegalArgumentException("Имя конфига: буквы, цифры, пробел, _ - .");
        }
    }

    public static void checkFolder(String folder) {
        if (folder == null || folder.isEmpty()) {
            return; // the top level
        }
        if (!NAME.matcher(folder).matches() || folder.startsWith(".") || folder.isBlank()) {
            throw new IllegalArgumentException("Имя папки: буквы, цифры, пробел, _ - .");
        }
        if (folder.equalsIgnoreCase(DEFAULT_FOLDER) || folder.equalsIgnoreCase(LEVELING_FOLDER)) {
            throw new IllegalArgumentException("Папка «" + folder + "» — для конфигов программы");
        }
    }

    /** All config names (every folder and the defaults), sorted. */
    public List<String> list() throws IOException {
        List<String> names = new ArrayList<>(defaults.keySet());
        for (List<String> in : byFolder().values()) {
            names.addAll(in);
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /**
     * The folders and their configs: {@link #DEFAULT_FOLDER} first (when there are defaults), the user's folders by name,
     * the configs outside any folder under {@code ""} last.
     */
    public Map<String, List<String>> folders() throws IOException {
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (String program : List.of(DEFAULT_FOLDER, LEVELING_FOLDER)) {
            List<String> d = new ArrayList<>();
            defaultFolder.forEach((n, f) -> {
                if (f.equals(program)) {
                    d.add(n);
                }
            });
            if (!d.isEmpty()) {
                d.sort(String.CASE_INSENSITIVE_ORDER);
                out.put(program, d);
            }
        }
        Map<String, List<String>> local = byFolder();
        List<String> root = local.remove("");
        out.putAll(local);
        if (root != null && !root.isEmpty()) {
            out.put("", root);
        }
        return out;
    }

    /** The user's folders by name (empty ones too) and the top level as {@code ""}. */
    private Map<String, List<String>> byFolder() throws IOException {
        Map<String, List<String>> out = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        if (!Files.isDirectory(dir)) {
            return out;
        }
        out.put("", names(dir));
        try (DirectoryStream<Path> subs = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path sub : subs) {
                String f = sub.getFileName().toString();
                if (!f.startsWith(".") && NAME.matcher(f).matches()) {
                    out.put(f, names(sub));
                }
            }
        }
        return out;
    }

    private static List<String> names(Path folder) throws IOException {
        List<String> names = new ArrayList<>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(folder, "*.json")) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                names.add(n.substring(0, n.length() - ".json".length()));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    /** A config of the program: shown in {@link #DEFAULT_FOLDER}, never written, copied to be changed. */
    public boolean isDefault(String name) {
        return defaults.containsKey(name) && find(name) == null;
    }

    /** The folder of a config: a program folder ({@link #isProgramFolder}), a folder's name, {@code ""} for the top level. */
    public String folderOf(String name) {
        if (isDefault(name)) {
            return defaultFolder.getOrDefault(name, DEFAULT_FOLDER);
        }
        Path f = find(name);
        return f == null || f.getParent().equals(dir) ? "" : f.getParent().getFileName().toString();
    }

    public boolean exists(String name) {
        checkName(name);
        return find(name) != null || defaults.containsKey(name);
    }

    /** When the config file last changed (0 if missing or a default): to notice edits made in the window. */
    public long modified(String name) {
        checkName(name);
        Path f = find(name);
        if (f == null) {
            return 0;
        }
        try {
            return Files.getLastModifiedTime(f).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public BotConfig load(String name) throws IOException {
        checkName(name);
        Path f = find(name);
        if (f != null) {
            return Migrations.read((ObjectNode) Json.MAPPER.readTree(f.toFile()));
        }
        String d = defaults.get(name);
        if (d != null) {
            return Migrations.read((ObjectNode) Json.MAPPER.readTree(d));
        }
        throw new IOException("Конфиг «" + name + "» не найден");
    }

    /** Saves where the config is (a new one at the top level). */
    public void save(String name, BotConfig config) throws IOException {
        checkName(name);
        Path f = find(name);
        save(f != null ? f : file("", name), name, config);
    }

    /** Saves a new config into {@code folder} ({@code ""} the top level); an existing one stays where it is. */
    public void save(String folder, String name, BotConfig config) throws IOException {
        checkName(name);
        checkFolder(folder);
        Path f = find(name);
        save(f != null ? f : file(folder, name), name, config);
    }

    private void save(Path f, String name, BotConfig config) throws IOException {
        refuseDefault(name);
        if (find(name) == null && taken(name) != null) {
            throw new IOException("Конфиг «" + taken(name) + "» уже есть (имя отличается только регистром)");
        }
        Files.createDirectories(f.getParent());
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Json.MAPPER.writeValue(tmp.toFile(), config);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void copy(String from, String to) throws IOException {
        copy(from, to, isDefault(from) ? "" : folderOf(from));
    }

    /** A copy of any config (a default too) under a new name in {@code folder}. */
    public void copy(String from, String to, String folder) throws IOException {
        refuseExisting(to);
        save(folder, to, load(from));
    }

    public void rename(String from, String to) throws IOException {
        refuseDefault(from);
        refuseExisting(to);
        checkName(from);
        Path f = existing(from);
        Files.move(f, f.resolveSibling(to + ".json"));
    }

    public void delete(String name) throws IOException {
        checkName(name);
        refuseDefault(name);
        Path f = find(name);
        if (f != null) {
            Files.deleteIfExists(f);
        }
    }

    /** Moves a config into {@code folder} ({@code ""} the top level). */
    public void move(String name, String folder) throws IOException {
        checkName(name);
        checkFolder(folder);
        refuseDefault(name);
        Path f = existing(name);
        Path to = file(folder, name);
        if (!to.equals(f)) {
            Files.createDirectories(to.getParent());
            Files.move(f, to);
        }
    }

    public void createFolder(String folder) throws IOException {
        checkFolder(folder);
        if (folder.isEmpty()) {
            return;
        }
        Files.createDirectories(dir.resolve(folder));
    }

    public void renameFolder(String from, String to) throws IOException {
        checkFolder(from);
        checkFolder(to);
        if (from.isEmpty() || to.isEmpty()) {
            throw new IOException("Верхний уровень не переименовать");
        }
        Path t = dir.resolve(to);
        if (Files.exists(t)) {
            throw new IOException("Папка «" + to + "» уже есть");
        }
        Files.move(dir.resolve(from), t);
    }

    /** Deletes an empty folder (its configs first moved or deleted). */
    public void deleteFolder(String folder) throws IOException {
        checkFolder(folder);
        if (folder.isEmpty()) {
            return;
        }
        Path f = dir.resolve(folder);
        try (DirectoryStream<Path> in = Files.newDirectoryStream(f, "*.json")) {
            if (in.iterator().hasNext()) {
                throw new IOException("В папке «" + folder + "» есть конфиги — сначала перенесите или удалите их");
            }
        }
        Files.deleteIfExists(f);
    }

    private void refuseExisting(String name) throws IOException {
        if (exists(name) || taken(name) != null) {
            String other = taken(name);
            throw new IOException("Конфиг «" + (other != null ? other : name) + "» уже есть");
        }
    }

    /** Another config's name that differs from {@code name} only in case ({@code null} none): one file on macOS. */
    private String taken(String name) throws IOException {
        for (String n : list()) {
            if (!n.equals(name) && n.equalsIgnoreCase(name)) {
                return n;
            }
        }
        return null;
    }

    private void refuseDefault(String name) throws IOException {
        if (isDefault(name) || defaults.containsKey(name) && find(name) == null) {
            throw new IOException("«" + name + "» — конфиг программы (папка " + defaultFolder.getOrDefault(name, DEFAULT_FOLDER)
                    + "): его не меняют — сделайте копию");
        }
    }

    private Path existing(String name) throws IOException {
        Path f = find(name);
        if (f == null) {
            throw new IOException("Конфиг «" + name + "» не найден");
        }
        return f;
    }

    /** The file of a config: the top level first, then each folder; {@code null} when there is none. */
    private Path find(String name) {
        Path top = dir.resolve(name + ".json");
        if (exact(top, name)) {
            return top;
        }
        if (!Files.isDirectory(dir)) {
            return null;
        }
        try (DirectoryStream<Path> subs = Files.newDirectoryStream(dir, Files::isDirectory)) {
            for (Path sub : subs) {
                Path f = sub.resolve(name + ".json");
                if (exact(f, name)) {
                    return f;
                }
            }
        } catch (IOException e) {
            return null;
        }
        return null;
    }

    /**
     * The file is there under exactly this name: macOS's file system ignores the case, and {@code Cubikon} (a
     * default) must not find {@code CUBIKON.json} (the user's own).
     */
    private static boolean exact(Path f, String name) {
        if (!Files.isRegularFile(f)) {
            return false;
        }
        try {
            return f.toRealPath().getFileName().toString().equals(name + ".json");
        } catch (IOException e) {
            return false;
        }
    }

    private Path file(String folder, String name) {
        return (folder == null || folder.isEmpty() ? dir : dir.resolve(folder)).resolve(name + ".json");
    }

    /** The program's default configs: name → JSON text, from the classpath ({@link #DEFAULTS}). */
    static Map<String, String> bundled() {
        return bundled(DEFAULTS);
    }

    /** The program's configs of one bundled folder ({@link #DEFAULTS}, {@link #LEVELING}): name → JSON text. */
    static Map<String, String> bundled(String base) {
        Map<String, String> out = new LinkedHashMap<>();
        try (InputStream idx = ConfigStore.class.getResourceAsStream(base + "index.txt")) {
            if (idx == null) {
                return out;
            }
            for (String line : new String(idx.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                String name = line.strip();
                if (name.isEmpty() || name.startsWith("#")) {
                    continue;
                }
                try (InputStream in = ConfigStore.class.getResourceAsStream(base + name + ".json")) {
                    if (in != null) {
                        out.put(name, new String(in.readAllBytes(), StandardCharsets.UTF_8));
                    }
                }
            }
        } catch (IOException e) {
            return out;
        }
        return out;
    }
}
