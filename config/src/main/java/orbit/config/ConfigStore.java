package orbit.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Named configs as files {@code <dir>/<name>.json} (by default {@code local/configs}). Saves go
 * through a temporary file, so a crash never leaves half a config.
 */
public final class ConfigStore {

    public static final Path DEFAULT_DIR = Path.of("local", "configs");
    /** Letters (any script), digits, space, {@code _ - .}; no path separators. */
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_\\-. ]{1,64}");

    private final Path dir;

    public ConfigStore(Path dir) {
        this.dir = dir;
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

    /** Config names, sorted. */
    public List<String> list() throws IOException {
        List<String> names = new ArrayList<>();
        if (!Files.isDirectory(dir)) {
            return names;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(dir, "*.json")) {
            for (Path f : files) {
                String n = f.getFileName().toString();
                names.add(n.substring(0, n.length() - ".json".length()));
            }
        }
        names.sort(String.CASE_INSENSITIVE_ORDER);
        return names;
    }

    public boolean exists(String name) {
        checkName(name);
        return Files.exists(file(name));
    }

    /** When the config file last changed (0 if missing): to notice edits made in the window. */
    public long modified(String name) {
        checkName(name);
        try {
            return Files.getLastModifiedTime(file(name)).toMillis();
        } catch (IOException e) {
            return 0;
        }
    }

    public BotConfig load(String name) throws IOException {
        checkName(name);
        Path f = file(name);
        if (!Files.exists(f)) {
            throw new IOException("Конфиг «" + name + "» не найден");
        }
        return Migrations.read((ObjectNode) Json.MAPPER.readTree(f.toFile()));
    }

    public void save(String name, BotConfig config) throws IOException {
        checkName(name);
        Files.createDirectories(dir);
        Path f = file(name);
        Path tmp = f.resolveSibling(f.getFileName() + ".tmp");
        Json.MAPPER.writeValue(tmp.toFile(), config);
        try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void copy(String from, String to) throws IOException {
        refuseExisting(to);
        save(to, load(from));
    }

    public void rename(String from, String to) throws IOException {
        refuseExisting(to);
        checkName(from);
        Files.move(file(from), file(to));
    }

    public void delete(String name) throws IOException {
        checkName(name);
        Files.deleteIfExists(file(name));
    }

    private void refuseExisting(String name) throws IOException {
        if (exists(name)) {
            throw new IOException("Конфиг «" + name + "» уже есть");
        }
    }

    private Path file(String name) {
        return dir.resolve(name + ".json");
    }
}
