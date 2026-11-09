package orbit.game;

import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import orbit.auth.AuthClient;
import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.MapServers;
import orbit.protocol.Packet;
import orbit.protocol.gen.ShipInitializationCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Developer tool: logs in with {@code creds.txt}, stays connected for a while without doing
 * anything in game, then disconnects. Prints states and packet statistics, never secrets.
 *
 * <pre>
 *   game-check --creds creds.txt [--seconds 60]
 *   ORBIT_DOSID=... game-check --server int1 [--seconds 60]   # when the site asks for a captcha
 * </pre>
 */
public final class GameCheck {

    private static final Logger log = LoggerFactory.getLogger(GameCheck.class);
    /** Observed in the official client's ReadyRequest (2D renderer, window size, UI path). */
    static final String READY_TYPE_STR =
            "2D 1920x1022 .root1.instance469.MainClientApplication0.ApplicationSkin2.Group3.Group4.Main5.instance65124";

    private GameCheck() {
    }

    public static void main(String[] args) throws Exception {
        Path creds = Path.of("creds.txt");
        String server = "int1";
        int seconds = 60;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--creds" -> creds = Path.of(args[++i]);
                case "--server" -> server = args[++i];
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                default -> {
                    System.err.println("Неизвестный параметр: " + args[i]);
                    System.exit(2);
                }
            }
        }
        String envDosid = System.getenv("ORBIT_DOSID");
        String dosid = envDosid == null || envDosid.isBlank() ? null : envDosid.strip();
        String user = null;
        String password = null;
        if (dosid == null) {
            List<String> lines = Files.readAllLines(creds);
            user = lines.get(0).strip();
            password = lines.get(1).strip();
        }
        String accountServer = server;
        String username = user;
        String secret = password;

        AuthClient auth = new AuthClient(new HttpSession("", Proxy.NO_PROXY, null), AuthClient.Endpoints.production());
        SessionSource source = new SessionSource() {
            @Override
            public GameSession fresh() throws AuthException {
                return dosid != null ? auth.loginWithDosid(accountServer, dosid) : auth.loginWithPassword(username, secret);
            }

            @Override
            public MapServers.Address serverForMap(GameSession s, int mapId) throws AuthException {
                return auth.serverForMap(s, mapId);
            }
        };

        Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
        AtomicLong total = new AtomicLong();
        GameListener listener = new GameListener() {
            @Override
            public void onPacket(Packet p) {
                total.incrementAndGet();
                counts.computeIfAbsent(p.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
                if (p instanceof ShipInitializationCommand si) {
                    log.info("Корабль: {} ({}), карта {}, позиция {}x{}, HP {}/{}, щит {}/{}", si.userName, si.typeId,
                            si.mapId, si.x, si.y, (long) si.hitPoints, (long) si.hitMax, si.shield, si.shieldMax);
                }
            }

            @Override
            public void onState(State state, String reason) {
                log.info("Состояние: {} — {}", state, reason);
            }
        };

        GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(Proxy.NO_PROXY),
                StartupSequence.minimal(READY_TYPE_STR));
        Thread runner = Thread.ofPlatform().name("game").start(conn::run);
        long end = System.currentTimeMillis() + Duration.ofSeconds(seconds).toMillis();
        while (System.currentTimeMillis() < end && runner.isAlive()) {
            Thread.sleep(Math.min(10_000, Math.max(1, end - System.currentTimeMillis())));
            log.info("Получено пакетов: {}", total.get());
        }
        conn.close();
        runner.join(5_000);
        log.info("Итог: {} пакетов, типов {}. Чаще всего: {}", total.get(), counts.size(),
                counts.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                        .limit(8).map(e -> e.getKey() + "=" + e.getValue().get()).toList());
    }
}
