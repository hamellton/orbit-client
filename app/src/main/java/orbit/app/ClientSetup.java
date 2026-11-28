package orbit.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The client for «Смотреть», «Играть руками» and «Сайт» (docs/PLAN.md «Клиент для всех», a user's wish 2026-11-28): kaiserdj's
 * DarkOrbit Client {@link #VERSION} (AGPL — never bundled, fetched from its own release), installed for this user with the
 * release's own checksum checked first; or a client the user points to, kept in {@code local/client.properties}.
 */
public final class ClientSetup {

    private static final Logger LOG = LoggerFactory.getLogger(ClientSetup.class);

    public static final String VERSION = "2.8.0";
    static final String RELEASE = "https://github.com/kaiserdj/Darkorbit-client/releases/download/v" + VERSION + "/";
    static final Path SETTINGS = Path.of("local", "client.properties");

    /** The systems the client is made for. */
    public enum Os {
        /*
         * The release's own macOS checksums are stale (2026-11-28: neither the zip nor the dmg matches latest-mac.yml, the dmg
         * not even in size — rebuilt after the feed was written); this zip's checksum is pinned instead: its 100 files are
         * byte for byte the client that has run on the user's Mac since 2026-11.
         */
        MAC("latest-mac.yml", "DarkOrbit-Client-" + VERSION + "-mac.zip",
                "tc5AfSU4+N0bv0I2EarfdkopgMK2hi1WXSIuwNxON4bZvm7AezVXc2FKBfLMTtW9RikmQ4kjmjASRP3Kt6w6JQ=="),
        WINDOWS("latest.yml", "DarkOrbit-Client-Setup-" + VERSION + ".exe", null),
        LINUX("latest-linux.yml", "DarkOrbit-Client-" + VERSION + ".AppImage", null);

        /** The release's file listing this system's downloads with their checksums. */
        final String feed;
        /** The download for this system. */
        final String asset;
        /** The checksum known here, used in place of the feed's ({@code null}: the feed's). */
        final String pinned;

        Os(String feed, String asset, String pinned) {
            this.feed = feed;
            this.asset = asset;
            this.pinned = pinned;
        }

        public static Os current() {
            String name = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            return name.contains("mac") ? MAC : name.contains("win") ? WINDOWS : LINUX;
        }
    }

    private ClientSetup() {
    }

    /**
     * The client runs here: its builds are x86-64 only, with x86 Flash inside (macOS on Apple silicon and Windows on ARM run
     * them by their own emulation; Linux on ARM cannot — 2026-11-28 on the Oracle aarch64 server the AppImage would not start).
     */
    public static boolean supported() {
        return supported(Os.current(), System.getProperty("os.arch", ""));
    }

    static boolean supported(Os os, String arch) {
        String a = arch.toLowerCase(java.util.Locale.ROOT);
        return os != Os.LINUX || a.equals("amd64") || a.equals("x86_64");
    }

    /** The client the user pointed to ({@code null}: none, or it is no longer there). */
    static Path chosen() {
        if (!Files.isRegularFile(SETTINGS)) {
            return null;
        }
        Properties p = new Properties();
        try (InputStream in = Files.newInputStream(SETTINGS)) {
            p.load(in);
        } catch (IOException e) {
            return null;
        }
        String path = p.getProperty("path", "").trim();
        return path.isEmpty() ? null : Path.of(path);
    }

    /**
     * Keeps the client the user pointed to; {@code null} forgets it.
     *
     * @return the program it starts, {@code null} when no client is there
     */
    public static Path choose(Path client) throws IOException {
        Properties p = new Properties();
        Path program = client == null ? null : HandPlay.program(client);
        if (client != null && program == null) {
            return null;
        }
        if (client != null) {
            p.setProperty("path", client.toAbsolutePath().normalize().toString());
        }
        Files.createDirectories(SETTINGS.getParent());
        try (OutputStream out = Files.newOutputStream(SETTINGS)) {
            p.store(out, "the client for hand play, watching and the site (ClientSetup)");
        }
        LOG.info("Клиент: {}", program == null ? "выбор сброшен" : "указан " + program);
        return program;
    }

    /** Where an install by this program puts the client on this system. */
    static Path place(Os os) {
        String home = System.getProperty("user.home");
        return switch (os) {
            case MAC -> Path.of(home, "Applications", "DarkOrbit Client.app");
            case WINDOWS -> {
                String local = System.getenv("LOCALAPPDATA");
                yield Path.of(local != null && !local.isBlank() ? local : Path.of(home, "AppData", "Local").toString(),
                        "Programs", "darkorbit-client", "DarkOrbit Client.exe");
            }
            case LINUX -> Path.of(home, ".local", "share", "orbit-client", Os.LINUX.asset);
        };
    }

    /** The checksum (SHA-512, base64) the release's feed gives for a file, {@code null} when it lists none. */
    static String checksum(String feed, String asset) {
        String[] lines = feed.split("\\R");
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].trim().equals("- url: " + asset)) {
                for (int j = i + 1; j < lines.length && !lines[j].trim().startsWith("- "); j++) {
                    String t = lines[j].trim();
                    if (t.startsWith("sha512:")) {
                        return t.substring("sha512:".length()).trim();
                    }
                }
            }
        }
        return null;
    }

    static String sha512(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest d = MessageDigest.getInstance("SHA-512");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0; ) {
                d.update(buf, 0, n);
            }
            return Base64.getEncoder().encodeToString(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Downloads the client for this system from its release, checks it against the release's checksum and installs it for
     * this user. {@code status} hears the steps (Russian, for the window).
     *
     * @return the program to start, {@code null} when it did not work out (said to {@code status})
     */
    public static Path install(Consumer<String> status) {
        Os os = Os.current();
        if (!supported()) {
            status.accept("клиент выпущен только для x86-64 (внутри Flash) — на Linux " + System.getProperty("os.arch")
                    + " он не запустится");
            return null;
        }
        Path tmp = null;
        try {
            HttpClient http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL)
                    .connectTimeout(Duration.ofSeconds(20)).build();
            String want = os.pinned;
            if (want == null) {
                status.accept("скачиваю список файлов выпуска " + VERSION + "…");
                HttpResponse<String> feed = http.send(HttpRequest.newBuilder(URI.create(RELEASE + os.feed))
                        .timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofString());
                want = feed.statusCode() == 200 ? checksum(feed.body(), os.asset) : null;
                if (want == null) {
                    status.accept("в выпуске " + VERSION + " нет файла " + os.asset + " (ответ " + feed.statusCode() + ")");
                    return null;
                }
            }
            tmp = Files.createTempFile("orbit-client-", "-" + os.asset);
            status.accept("скачиваю " + os.asset + " (~125 МБ)…");
            HttpResponse<Path> got = http.send(HttpRequest.newBuilder(URI.create(RELEASE + os.asset))
                    .timeout(Duration.ofMinutes(20)).build(),
                    HttpResponse.BodyHandlers.ofFile(tmp));
            if (got.statusCode() != 200) {
                status.accept("скачать не вышло: ответ " + got.statusCode());
                return null;
            }
            if (!want.equals(sha512(tmp))) {
                status.accept("файл не совпал с контрольной суммой — не ставлю");
                return null;
            }
            status.accept("контрольная сумма совпала, ставлю…");
            Path place = place(os);
            switch (os) {
                case WINDOWS -> run(new ProcessBuilder(tmp.toString(), "/S")); // the installer's own silent mode, this user
                case MAC -> {
                    Files.createDirectories(place.getParent());
                    if (Files.exists(place)) {
                        status.accept("в " + place.getParent() + " уже есть DarkOrbit Client.app — укажите его");
                        return null;
                    }
                    run(new ProcessBuilder("ditto", "-x", "-k", tmp.toString(), place.getParent().toString()));
                }
                case LINUX -> {
                    Files.createDirectories(place.getParent());
                    Files.move(tmp, place, StandardCopyOption.REPLACE_EXISTING);
                    tmp = null;
                    if (!place.toFile().setExecutable(true)) {
                        status.accept("не смог сделать " + place + " запускаемым");
                        return null;
                    }
                }
            }
            Path program = HandPlay.program(place);
            if (program == null) {
                status.accept("установка прошла, но клиента нет в " + place);
                return null;
            }
            LOG.info("Клиент {} установлен: {}", VERSION, program);
            status.accept("клиент " + VERSION + " установлен");
            return program;
        } catch (IOException e) {
            status.accept("не вышло: " + e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            status.accept("прервано");
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a temp file left: no harm
                }
            }
        }
    }

    private static void run(ProcessBuilder pb) throws IOException, InterruptedException {
        Process p = pb.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (!p.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) {
            p.destroyForcibly();
            throw new IOException(pb.command().get(0) + " не закончил за 10 мин");
        }
        if (p.exitValue() != 0) {
            throw new IOException(pb.command().get(0) + " завершился с кодом " + p.exitValue());
        }
    }
}
