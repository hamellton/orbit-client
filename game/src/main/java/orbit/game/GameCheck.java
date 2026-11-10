package orbit.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import orbit.auth.AccountLogin;
import orbit.auth.AuthClient;
import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.MapServers;
import orbit.protocol.Packet;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.secrets.MasterPassword;
import orbit.secrets.Vault;
import orbit.secrets.VaultTool;
import orbit.world.World;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Developer tool: logs in with {@code creds.txt}, stays connected for a while without doing
 * anything in game, then disconnects. Prints states and packet statistics, never secrets.
 *
 * <pre>
 *   game-check --account main [--seconds 60]                  # credentials from local/vault.bin
 *   game-check --creds creds.txt [--seconds 60]
 *   options: --profile local/client-profile.json (UI state of the official client, see ClientProfileTool)
 *   ORBIT_DOSID=... game-check --server int1 [--seconds 60]   # when the site asks for a captcha
 * </pre>
 */
public final class GameCheck {

    private static final Logger log = LoggerFactory.getLogger(GameCheck.class);

    private interface Login {
        GameSession run() throws AuthException;
    }

    private GameCheck() {
    }

    private static String itemText(WorldSnapshot.Item i) {
        return i == null ? "-" : String.format("%s ×%.0f", i.id().replaceFirst("^ammunition_[a-z]+_", ""), i.counter());
    }

    /** One-line summary of the world model for the log. */
    public static String describe(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        List<WorldSnapshot.Ship> npcs = w.nearest(WorldSnapshot.ShipKind.NPC);
        long boxes = w.collectables().values().stream().filter(c -> c.kind() == WorldSnapshot.CollectableKind.BOX).count();
        String nearest = npcs.isEmpty() ? "нет" : String.format("%s в %.0f", npcs.getFirst().name(),
                npcs.getFirst().distanceTo(h.x(), h.y()));
        WorldSnapshot.Item laser = w.selected("lasers");
        WorldSnapshot.Item rocket = w.selected("rockets");
        MapCatalog.MapInfo map = MapCatalog.bundled().info(h.mapId());
        return String.format("Мир: карта %s (%dx%d), герой %d,%d%s, HP %.0f%%, конфиг %d, трюм свободно %d из %d, "
                        + "лазер %s, ракеты %s, предметов %d; игроков %d, NPC %d (ближайший: %s), "
                        + "питомцев %d, ящиков %d, руды %d, порталов %d, объектов %d, зон %d%s",
                map.name(), map.width(), map.height(), h.x(), h.y(), h.moving() ? " (летит)" : "", h.health().hpPercent() * 100, h.config(),
                h.cargoFree(), h.cargoMax(), itemText(laser), itemText(rocket), w.items().size(),
                w.nearest(WorldSnapshot.ShipKind.PLAYER).size(), npcs.size(), nearest,
                w.nearest(WorldSnapshot.ShipKind.PET).size(), boxes, w.collectables().size() - boxes,
                w.portals().size(), w.assets().size(), w.zones().size(),
                h.beacon().inStation() ? ", на станции" : h.beacon().inPeaceArea() ? ", в мирной зоне" : "");
    }

    public static void main(String[] args) throws Exception {
        Path creds = Path.of("creds.txt");
        Path profileFile = Path.of("local", "client-profile.json");
        Path vaultFile = VaultTool.DEFAULT_FILE;
        String account = null;
        String server = "int1";
        int seconds = 60;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--account" -> account = args[++i];
                case "--vault" -> vaultFile = Path.of(args[++i]);
                case "--creds" -> creds = Path.of(args[++i]);
                case "--server" -> server = args[++i];
                case "--seconds" -> seconds = Integer.parseInt(args[++i]);
                case "--profile" -> profileFile = Path.of(args[++i]);
                default -> {
                    System.err.println("Неизвестный параметр: " + args[i]);
                    System.exit(2);
                }
            }
        }

        ClientProfile profile;
        if (Files.exists(profileFile)) {
            profile = ClientProfile.load(profileFile);
            log.info("Профиль клиента: {} (раскладка окон: {}, клавиши: {})", profileFile,
                    profile.windowSettings() != null ? "есть" : "нет", profile.keyBindings() != null ? "есть" : "нет");
        } else {
            profile = ClientProfile.minimal();
            log.warn("Профиль клиента {} не найден — раскладка окон и клавиши отправляться не будут. "
                    + "Создайте его из записи сниффера: client-profile recordings", profileFile);
        }

        Vault vault = null;
        orbit.auth.proxy.ProxyRoute route = orbit.auth.proxy.ProxyRoute.none();
        if (account != null) {
            char[] master = MasterPassword.obtain(false);
            try {
                vault = Vault.open(vaultFile, master);
            } finally {
                Arrays.fill(master, '\0');
            }
            if (vault.get(account) != null) {
                route = orbit.auth.proxy.ProxyRoute.of(vault.get(account).proxySpec());
            }
        }
        orbit.auth.proxy.ProxyRoute net = route;
        AuthClient auth = new AuthClient(new HttpSession("", net.httpProxy()), AuthClient.Endpoints.production());
        Login login;
        if (account != null) {
            AccountLogin accountLogin = new AccountLogin(auth, vault, account);
            login = () -> {
                net.verify();
                return accountLogin.login();
            };
        } else if (System.getenv("ORBIT_DOSID") != null && !System.getenv("ORBIT_DOSID").isBlank()) {
            String dosid = System.getenv("ORBIT_DOSID").strip();
            String accountServer = server;
            login = () -> auth.loginWithDosid(accountServer, dosid);
        } else {
            List<String> lines = Files.readAllLines(creds);
            String user = lines.get(0).strip();
            String password = lines.get(1).strip();
            login = () -> auth.loginWithPassword(user, password);
        }

        SessionSource source = new SessionSource() {
            @Override
            public GameSession fresh() throws AuthException {
                return login.run();
            }

            @Override
            public MapServers.Address serverForMap(GameSession s, int mapId) throws AuthException {
                return auth.serverForMap(s, mapId);
            }
        };

        Map<String, AtomicLong> counts = new ConcurrentHashMap<>();
        AtomicLong total = new AtomicLong();
        World world = new World();
        GameListener listener = new GameListener() {
            @Override
            public void onPacket(Packet p) {
                world.apply(p, System.nanoTime());
                total.incrementAndGet();
                counts.computeIfAbsent(p.getClass().getSimpleName(), k -> new AtomicLong()).incrementAndGet();
                if (p instanceof ShipInitializationCommand si) {
                    log.info("Корабль: {} ({}), карта {}, позиция {}x{}, HP {}/{}, щит {}/{}", si.userName, si.typeId,
                            si.mapId, si.x, si.y, (long) si.hitPoints, (long) si.hitMax, si.shield, si.shieldMax);
                }
            }

            @Override
            public void onSent(Packet p) {
                world.applyOutgoing(p, System.nanoTime());
            }

            @Override
            public void onState(State state, String reason) {
                log.info("Состояние: {} — {}", state, reason);
            }
        };

        GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(net.connector()),
                StartupSequence.clientLike(profile));
        Thread runner = Thread.ofPlatform().name("game").start(conn::run);
        long end = System.currentTimeMillis() + Duration.ofSeconds(seconds).toMillis();
        while (System.currentTimeMillis() < end && runner.isAlive()) {
            Thread.sleep(Math.min(10_000, Math.max(1, end - System.currentTimeMillis())));
            log.info("Получено пакетов: {}. {}", total.get(), describe(world.snapshot(System.nanoTime())));
        }
        conn.close();
        runner.join(5_000);
        log.info("Итог: {} пакетов, типов {}. Чаще всего: {}", total.get(), counts.size(),
                counts.entrySet().stream()
                        .sorted((a, b) -> Long.compare(b.getValue().get(), a.getValue().get()))
                        .limit(8).map(e -> e.getKey() + "=" + e.getValue().get()).toList());
    }
}
