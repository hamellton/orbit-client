package orbit.app.gui;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The game's own minimap pictures ({@code spacemap/graphics/minimaps/minimap-<map>-700.jpg} on the game
 * CDN, 700×436 like the maps' 21000×13100), downloaded once into {@code local/minimaps/} and kept in
 * memory. Loading never blocks painting: the first call starts a download and returns {@code null}.
 * When an account has a proxy, the pictures come through it (the first such account's), not directly.
 */
public final class MinimapBackgrounds {

    private static final Logger log = LoggerFactory.getLogger(MinimapBackgrounds.class);

    static final String URL = "https://darkorbit-22.bpsecure.com/spacemap/graphics/minimaps/minimap-%d-700.jpg";
    static final Path DIR = Path.of("local", "minimaps");

    private static final Map<Integer, Optional<BufferedImage>> CACHE = new ConcurrentHashMap<>();
    private static final Set<Integer> LOADING = ConcurrentHashMap.newKeySet();
    private static final java.util.List<Runnable> ON_LOADED = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static volatile java.net.ProxySelector proxy;

    private MinimapBackgrounds() {
    }

    /** Downloads go through this proxy from now on; the first proxied account sets it. */
    public static void useProxy(java.net.ProxySelector selector) {
        if (proxy == null) {
            proxy = selector;
        }
    }

    /** Called (on a background thread) when a picture has arrived, e.g. to repaint. */
    public static void onLoaded(Runnable r) {
        ON_LOADED.add(r);
    }

    /** The picture of the map, or {@code null} while it loads or when the game has none. */
    public static BufferedImage get(int mapId) {
        if (mapId <= 0) {
            return null;
        }
        Optional<BufferedImage> known = CACHE.get(mapId);
        if (known != null) {
            return known.orElse(null);
        }
        if (LOADING.add(mapId)) {
            Thread.ofVirtual().name("minimap-" + mapId).start(() -> {
                CACHE.put(mapId, Optional.ofNullable(load(mapId)));
                ON_LOADED.forEach(Runnable::run);
            });
        }
        return null;
    }

    private static BufferedImage load(int mapId) {
        Path file = DIR.resolve(mapId + ".jpg");
        try {
            if (!Files.exists(file)) {
                HttpClient.Builder builder = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                        .followRedirects(HttpClient.Redirect.NORMAL);
                java.net.ProxySelector p = proxy;
                if (p != null) {
                    builder.proxy(p);
                }
                HttpClient http = builder.build();
                HttpResponse<byte[]> r = http.send(HttpRequest.newBuilder(URI.create(String.format(URL, mapId)))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                String type = r.headers().firstValue("content-type").orElse("");
                if (r.statusCode() != 200 || !type.startsWith("image/")) {
                    log.debug("Фона миникарты {} нет ({})", mapId, r.statusCode());
                    return null;
                }
                Files.createDirectories(DIR);
                Path tmp = DIR.resolve(mapId + ".jpg.tmp");
                Files.write(tmp, r.body());
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return ImageIO.read(file.toFile());
        } catch (IOException e) {
            log.debug("Фон миникарты {} не загрузить: {}", mapId, e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }
}
