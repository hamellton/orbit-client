package orbit.app;

import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import orbit.app.gui.MainWindow;
import orbit.auth.AccountLogin;
import orbit.auth.AuthClient;
import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.MapServers;
import orbit.game.ClientProfile;
import orbit.game.ConnectionSettings;
import orbit.game.GameCheck;
import orbit.game.GameConnection;
import orbit.game.GameListener;
import orbit.game.SessionSource;
import orbit.game.StartupSequence;
import orbit.net.Recording;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.secrets.MasterPassword;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;
import orbit.secrets.VaultTool;
import orbit.sniffer.PacketTap;
import orbit.sniffer.SnifferMain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The bot application.
 *
 * <pre>
 *   orbit bot --account main [--headless] [--profile local/client-profile.json] [--vault local/vault.bin]
 *             [--fly 105/65]   # development: fly to a point of the current map once in game
 *             [--go "3-3;3-1:188/125"] # development: travel through portals, legs separated by ';'
 *             [--repair-free [--faction 3]]  # development: take a free repair on the kill screen
 *             [--hunt 1:[ kristallin ]]      # development: after --go, kill NPCs (name part) and pick up boxes
 *             [--then "3-6:20/20"] [--logout] [--repair-at portal]  # then fly on, log out; repair choice
 *   orbit observe [--port 1080] [--direct IP:6001-6007] [--out recordings]   # watch the official client
 *   orbit replay recordings/session-….dorec [--speed 2]
 *   orbit render recordings/session-….dorec --at 30 [--out minimap.png]   # minimap at second 30 as PNG
 * </pre>
 * The bot itself does nothing in game yet: it logs in, keeps the connection and builds the world model.
 */
public final class OrbitMain {

    private static final Logger log = LoggerFactory.getLogger(OrbitMain.class);
    /** How often the headless mode writes the world summary. */
    private static final long HEADLESS_SUMMARY_MS = 30_000;

    private OrbitMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            return;
        }
        List<String> rest = Arrays.asList(args).subList(1, args.length);
        Options o = Options.parse(rest);
        switch (args[0]) {
            case "bot" -> bot(o);
            case "observe" -> observe(o);
            case "replay" -> replay(o);
            case "render" -> render(o);
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("""
                Режимы:
                  orbit bot --account <имя> [--headless]   бот входит в игру сам
                  orbit observe [--direct IP:порты]        окно бота по трафику официального клиента (через сниффер)
                  orbit replay <запись.dorec> [--speed N]  проиграть запись сниффера
                  orbit render <запись.dorec> --at <сек> [--out файл.png]  мини-карта на момент записи""");
        System.exit(2);
    }

    // ---- bot ----

    private static void bot(Options o) throws Exception {
        if (o.account == null) {
            System.err.println("Укажите аккаунт: --account <имя> (vault add <имя>)");
            System.exit(2);
        }
        char[] master = MasterPassword.obtain(false);
        Vault vault;
        try {
            vault = Vault.open(o.vault, master);
        } catch (VaultException e) {
            System.err.println("Хранилище: " + e.getMessage());
            System.exit(1);
            return;
        } finally {
            Arrays.fill(master, '\0');
        }
        ClientProfile profile = Files.exists(o.profile) ? ClientProfile.load(o.profile) : ClientProfile.minimal();
        if (profile.windowSettings() == null) {
            log.warn("Профиль клиента {} не найден — раскладка окон и клавиши отправляться не будут "
                    + "(создайте его: client-profile recordings).", o.profile);
        }
        AuthClient auth = new AuthClient(new HttpSession("", Proxy.NO_PROXY, null), AuthClient.Endpoints.production());
        AccountLogin login = new AccountLogin(auth, vault, o.account);

        WorldFeed feed = new WorldFeed("бот, " + o.account);
        GameListener listener = new GameListener() {
            @Override
            public void onPacket(Packet p) {
                feed.onPacket(p, System.nanoTime());
            }

            @Override
            public void onSent(Packet p) {
                feed.onSent(p, System.nanoTime());
            }

            @Override
            public void onState(State state, String reason) {
                feed.setState(reason);
                log.info("Состояние: {} — {}", state, reason);
            }
        };
        SessionSource source = new SessionSource() {
            @Override
            public GameSession fresh() throws AuthException {
                return login.login();
            }

            @Override
            public MapServers.Address serverForMap(GameSession s, int mapId) throws AuthException {
                return auth.serverForMap(s, mapId);
            }
        };
        GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(Proxy.NO_PROXY),
                StartupSequence.clientLike(profile));
        Thread runner = Thread.ofPlatform().name("game").start(conn::run);
        Runtime.getRuntime().addShutdownHook(new Thread(conn::close));
        if (o.fly != null) {
            startFlightTest(feed, conn, o.fly);
        }
        if (o.go != null || o.hunt != null || o.then != null || o.logout) {
            startTravelTest(feed, conn, o.go, o.hunt, o.then, o.logout, profile);
        }
        if (o.repairFree || o.repairAt != null) {
            startFreeRepair(feed, conn, o.faction, o.repairAt);
        }
        runUntilClosed(feed, o.headless, conn::close, runner);
    }

    /**
     * Development check of the navigator: once in game, waits a few seconds like a player looking
     * around, flies to the point (game coordinates, e.g. {@code 105/65}) and logs the flight.
     */
    private static void startFlightTest(WorldFeed feed, GameConnection conn, String target) {
        String[] xy = target.split("[/,]");
        double x = Double.parseDouble(xy[0].trim()) * 100;
        double y = Double.parseDouble(xy[1].trim()) * 100;
        java.util.Random random = new java.util.Random();
        orbit.bot.nav.Navigator nav = new orbit.bot.nav.Navigator(feed::snapshot, conn::send,
                orbit.world.maps.MapCatalog.bundled(), random, System::nanoTime);
        Thread.ofPlatform().name("bot-loop").daemon().start(() -> {
            try {
                while (feed.snapshot().hero().id() == 0) {
                    Thread.sleep(500);
                }
                Thread.sleep(4_000 + random.nextInt(3_000));
                log.info("Тест полёта: лечу к {}/{}", (int) x / 100, (int) y / 100);
                feed.setAction("лечу к " + (int) x / 100 + "/" + (int) y / 100);
                nav.moveTo(x, y, false);
                long nextLog = 0;
                while (!nav.idle()) {
                    nav.tick();
                    if (System.currentTimeMillis() > nextLog) {
                        var h = feed.snapshot().hero();
                        log.info("Полёт: {}/{}, осталось {}, кликов {}", h.x() / 100, h.y() / 100,
                                (int) nav.remaining(), nav.clicks());
                        nextLog = System.currentTimeMillis() + 5_000;
                    }
                    Thread.sleep(100);
                }
                var h = feed.snapshot().hero();
                log.info("Тест полёта: прибыл в {}/{} ({} кликов)", h.x() / 100, h.y() / 100, nav.clicks());
                feed.setAction(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    /** One leg of a test route: a map and optionally a point on it (game coordinates ×100). */
    private record Leg(orbit.world.maps.MapCatalog.MapInfo map, double x, double y) {

        static List<Leg> parse(String spec) {
            orbit.world.maps.MapCatalog catalog = orbit.world.maps.MapCatalog.bundled();
            List<Leg> legs = new java.util.ArrayList<>();
            if (spec == null) {
                return legs;
            }
            for (String target : spec.split(";")) {
                if (target.isBlank()) {
                    continue;
                }
                String[] parts = target.trim().split(":");
                orbit.world.maps.MapCatalog.MapInfo map = catalog.byName(parts[0].trim());
                if (map == null) {
                    throw new IllegalArgumentException("неизвестная карта: " + parts[0]);
                }
                double x = Double.NaN;
                double y = Double.NaN;
                if (parts.length > 1) {
                    String[] xy = parts[1].split("[/,]");
                    x = Double.parseDouble(xy[0].trim()) * 100;
                    y = Double.parseDouble(xy[1].trim()) * 100;
                }
                legs.add(new Leg(map, x, y));
            }
            return legs;
        }
    }

    /**
     * Development scenario: {@code --go} legs, then {@code --hunt} on the last map, then {@code --then}
     * legs, then {@code --logout}. Survives a death: after the repair it flies back and carries on.
     */
    private static void startTravelTest(WorldFeed feed, GameConnection conn, String go, String hunt, String then,
                                        boolean logout, ClientProfile profile) {
        List<Leg> before = Leg.parse(go);
        List<Leg> after = Leg.parse(then);
        orbit.world.maps.MapCatalog catalog = orbit.world.maps.MapCatalog.bundled();
        java.util.Random random = new java.util.Random();
        orbit.bot.nav.Navigator nav = new orbit.bot.nav.Navigator(feed::snapshot, conn::send, catalog, random, System::nanoTime);
        orbit.bot.nav.Traveler traveler = new orbit.bot.nav.Traveler(feed::snapshot, conn::send, nav, catalog, random,
                System::nanoTime);
        orbit.bot.act.Actions actions = new orbit.bot.act.Actions(feed::snapshot, conn::send, () -> {
            var session = conn.session();
            return new orbit.bot.act.Actions.Login(session.userId(), session.sessionId());
        }, orbit.bot.act.Actions.Screen.fromReadyString(profile.readyTypeStr()), random);
        Thread.ofPlatform().name("bot-loop").daemon().start(() -> {
            try {
                waitAlive(feed);
                Thread.sleep(4_000 + random.nextInt(3_000));
                if (!travel(feed, traveler, before, catalog)) {
                    return;
                }
                if (hunt != null) {
                    int huntMap = before.isEmpty() ? feed.snapshot().hero().mapId() : before.getLast().map().id();
                    String[] h = hunt.split(":", 2);
                    int count = Integer.parseInt(h[0].trim());
                    String name = h.length > 1 ? h[1].trim().toLowerCase() : "";
                    orbit.bot.act.Hunter hunter = new orbit.bot.act.Hunter(feed::snapshot, actions, nav, random,
                            System::nanoTime, ship -> ship.name().toLowerCase().contains(name), count);
                    log.info("Охота: {} NPC{}", count, name.isEmpty() ? "" : " «" + name + "»");
                    feed.setAction("охота");
                    long nextLog = 0;
                    long until = System.currentTimeMillis() + 15 * 60_000;
                    while (hunter.state() == orbit.bot.act.Hunter.State.HUNTING && System.currentTimeMillis() < until) {
                        var hero = feed.snapshot().hero();
                        if (!hero.alive()) {
                            Thread.sleep(500);
                            continue;
                        }
                        if (hero.mapId() != huntMap) {
                            // destroyed and repaired elsewhere: back to the hunting ground
                            log.info("Не на карте охоты — лечу обратно на {}", catalog.info(huntMap).name());
                            if (!travel(feed, traveler, List.of(new Leg(catalog.info(huntMap), Double.NaN, Double.NaN)), catalog)) {
                                return;
                            }
                            continue;
                        }
                        hunter.tick();
                        if (System.currentTimeMillis() > nextLog) {
                            log.info("Охота: убито {}, ящиков {}, HP {}%", hunter.kills(), hunter.boxes(),
                                    (int) (hero.health().hpPercent() * 100));
                            nextLog = System.currentTimeMillis() + 10_000;
                        }
                        Thread.sleep(100);
                    }
                    log.info("Охота: {} — убито {}, ящиков {}", hunter.state(), hunter.kills(), hunter.boxes());
                    nav.stop();
                }
                if (!travel(feed, traveler, after, catalog)) {
                    return;
                }
                if (logout) {
                    Thread.sleep(1_000 + random.nextInt(2_000));
                    log.info("Выход из игры");
                    feed.setAction("выход");
                    actions.logout();
                    long until = System.currentTimeMillis() + 60_000;
                    while (!feed.loggedOut() && System.currentTimeMillis() < until) {
                        Thread.sleep(200);
                    }
                    log.info(feed.loggedOut() ? "Выход выполнен" : "Сервер не завершил сессию за минуту");
                    conn.close();
                    System.exit(0);
                }
                log.info("Сценарий завершён");
                feed.setAction(null);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    private static void waitAlive(WorldFeed feed) throws InterruptedException {
        while (feed.snapshot().hero().id() == 0 || !feed.snapshot().hero().alive()) {
            Thread.sleep(500);
        }
    }

    /** Runs legs one after another; waits through a death and repair. False if a leg cannot be done. */
    private static boolean travel(WorldFeed feed, orbit.bot.nav.Traveler traveler, List<Leg> legs,
                                  orbit.world.maps.MapCatalog catalog) throws InterruptedException {
        for (Leg leg : legs) {
            log.info("Перелёт на {}{}", leg.map().name(),
                    Double.isNaN(leg.x()) ? "" : " " + (int) leg.x() / 100 + "/" + (int) leg.y() / 100);
            feed.setAction("перелёт на " + leg.map().name());
            traveler.travel(leg.map().id(), leg.x(), leg.y());
            long nextLog = 0;
            while (traveler.state() == orbit.bot.nav.Traveler.State.TRAVELLING) {
                if (!feed.snapshot().hero().alive()) {
                    waitAlive(feed);
                    traveler.travel(leg.map().id(), leg.x(), leg.y()); // repaired somewhere: plan again
                }
                traveler.tick();
                if (System.currentTimeMillis() > nextLog) {
                    var h = feed.snapshot().hero();
                    log.info("Перелёт: {} {}/{}, прыжков {}", catalog.info(h.mapId()).name(), h.x() / 100, h.y() / 100,
                            traveler.jumps());
                    nextLog = System.currentTimeMillis() + 10_000;
                }
                Thread.sleep(100);
            }
            if (traveler.state() != orbit.bot.nav.Traveler.State.ARRIVED) {
                log.warn("Перелёт не удался: {}", traveler.failure());
                return false;
            }
            var h = feed.snapshot().hero();
            log.info("Прибыл: {} {}/{}", catalog.info(h.mapId()).name(), h.x() / 100, h.y() / 100);
        }
        return true;
    }

    /**
     * Development: on the kill screen, takes a repair that costs nothing (after a few seconds, like a
     * player reading the screen). Paid options are never chosen.
     */
    private static void startFreeRepair(WorldFeed feed, GameConnection conn, int faction, String prefer) {
        java.util.Random random = new java.util.Random();
        Thread.ofPlatform().name("repair").daemon().start(() -> {
            try {
                while (true) {
                    var w = feed.snapshot();
                    var d = w.death();
                    if (d != null) {
                        var free = d.freeRepair();
                        if ("portal".equals(prefer)) {
                            // repair at the jump gate if offered and free; a paid one is never taken
                            var gate = d.options().stream()
                                    .filter(op -> op.type() == orbit.protocol.gen.KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR)
                                    .findFirst().orElse(null);
                            if (gate != null && gate.affordable() && gate.price() == 0 && gate.cooldownSeconds() == 0) {
                                free = gate;
                            } else if (gate != null) {
                                log.warn("Ремонт у портала: цена {}, ожидание {} с — не беру, выбираю бесплатный", gate.price(),
                                        gate.cooldownSeconds());
                            } else {
                                log.info("Ремонта у портала нет — беру бесплатный");
                            }
                        }
                        int f = w.hero().factionId() != 0 ? w.hero().factionId() : faction;
                        if (free == null) {
                            log.warn("Бесплатного ремонта нет — жду решения игрока");
                        } else if (f == 0) {
                            log.warn("Фракция неизвестна — укажите --faction 1|2|3");
                        } else {
                            Thread.sleep(3_000 + random.nextInt(5_000));
                            log.info("Ремонт: {}", free.button());
                            conn.repair(free.type(), f);
                            Thread.sleep(15_000);
                            continue;
                        }
                        Thread.sleep(30_000);
                    }
                    Thread.sleep(500);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ---- observer ----

    private static void observe(Options o) throws Exception {
        WorldFeed feed = new WorldFeed("наблюдение за клиентом");
        PacketTap.register((upstream, p, nanos) -> {
            if (upstream) {
                feed.onSent(p, nanos);
            } else {
                feed.onPacket(p, nanos);
            }
        });
        Thread socks = SnifferMain.start(o.port, o.direct, o.out);
        feed.setState("Жду клиент на 127.0.0.1:" + o.port + (o.direct != null ? " и " + o.direct : ""));
        log.info("Сниффер запущен: socks5://127.0.0.1:{}; запустите клиент через scripts/sniff-session.sh --observe", o.port);
        runUntilClosed(feed, o.headless, () -> System.exit(0), socks);
    }

    // ---- replay ----

    private static void replay(Options o) throws Exception {
        if (o.positional.isEmpty()) {
            usage();
        }
        Path file = Path.of(o.positional.getFirst());
        List<Recording.PacketRecord> packets = Recording.readPackets(file);
        WorldFeed feed = new WorldFeed("запись " + file.getFileName());
        long base = System.nanoTime();
        double speed = o.speed;
        feed.setClock(() -> base + (long) ((System.nanoTime() - base) * speed));
        Thread player = Thread.ofPlatform().name("replay").daemon().start(() -> {
            try {
                for (Recording.PacketRecord r : packets) {
                    long at = base + r.nanos();
                    long wait = (long) ((at - feed.now()) / speed / 1_000_000);
                    if (wait > 0) {
                        Thread.sleep(wait);
                    }
                    Packet p = PacketCodec.decode(r.bytes());
                    if (r.up()) {
                        feed.onSent(p, at);
                    } else {
                        feed.onPacket(p, at);
                    }
                }
                feed.setState("Запись закончилась");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Запись не проигрывается: {}", e.toString());
            }
        });
        feed.setState("Проигрываю " + file.getFileName() + (speed != 1 ? " ×" + speed : ""));
        runUntilClosed(feed, o.headless, () -> System.exit(0), player);
    }

    // ---- render ----

    private static void render(Options o) throws Exception {
        if (o.positional.isEmpty() || o.at < 0) {
            usage();
        }
        Path file = Path.of(o.positional.getFirst());
        WorldFeed feed = new WorldFeed("запись " + file.getFileName());
        long at = (long) (o.at * 1e9);
        for (Recording.PacketRecord r : Recording.readPackets(file)) {
            if (r.nanos() > at) {
                break;
            }
            Packet p = PacketCodec.decode(r.bytes());
            if (r.up()) {
                feed.onSent(p, r.nanos());
            } else {
                feed.onPacket(p, r.nanos());
            }
        }
        feed.setClock(() -> at);
        Path out = o.out.toString().endsWith(".png") ? o.out : Path.of("minimap.png");
        javax.imageio.ImageIO.write(MainWindow.renderMinimap(feed, 1000, 624), "png", out.toFile());
        System.out.println(GameCheck.describe(feed.snapshot()));
        System.out.println("Сохранено: " + out);
    }

    // ---- common ----

    /** Shows the window (or logs summaries when headless) until the window is closed or {@code worker} ends. */
    private static void runUntilClosed(WorldFeed feed, boolean headless, Runnable stop, Thread worker) throws InterruptedException {
        if (!headless && !java.awt.GraphicsEnvironment.isHeadless()) {
            CountDownLatch closed = new CountDownLatch(1);
            MainWindow.open(feed, closed::countDown);
            closed.await();
            stop.run();
            worker.join(5_000);
            return;
        }
        while (worker.isAlive()) {
            worker.join(HEADLESS_SUMMARY_MS);
            log.info("{}. {}", feed.state(), GameCheck.describe(feed.snapshot()));
        }
    }

    /** Command-line options shared by the modes. */
    record Options(String account, Path vault, Path profile, boolean headless, int port, String direct, Path out,
                   double speed, double at, String fly, String go, boolean repairFree, int faction, String hunt,
                   String then, boolean logout, String repairAt, List<String> positional) {

        static Options parse(List<String> args) {
            String account = null;
            Path vault = VaultTool.DEFAULT_FILE;
            Path profile = Path.of("local", "client-profile.json");
            boolean headless = false;
            int port = 1080;
            String direct = null;
            Path out = Path.of("recordings");
            double speed = 1;
            double at = -1;
            String fly = null;
            String go = null;
            boolean repairFree = false;
            int faction = 0;
            String hunt = null;
            String then = null;
            boolean logout = false;
            String repairAt = null;
            List<String> positional = new java.util.ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                switch (args.get(i)) {
                    case "--account" -> account = args.get(++i);
                    case "--vault" -> vault = Path.of(args.get(++i));
                    case "--profile" -> profile = Path.of(args.get(++i));
                    case "--headless" -> headless = true;
                    case "--port" -> port = Integer.parseInt(args.get(++i));
                    case "--direct" -> direct = args.get(++i);
                    case "--out" -> out = Path.of(args.get(++i));
                    case "--speed" -> speed = Double.parseDouble(args.get(++i));
                    case "--at" -> at = Double.parseDouble(args.get(++i));
                    case "--fly" -> fly = args.get(++i);
                    case "--go" -> go = args.get(++i);
                    case "--repair-free" -> repairFree = true;
                    case "--faction" -> faction = Integer.parseInt(args.get(++i));
                    case "--hunt" -> hunt = args.get(++i);
                    case "--then" -> then = args.get(++i);
                    case "--logout" -> logout = true;
                    case "--repair-at" -> repairAt = args.get(++i);
                    default -> {
                        if (args.get(i).startsWith("--")) {
                            System.err.println("Неизвестный параметр: " + args.get(i));
                            System.exit(2);
                        }
                        positional.add(args.get(i));
                    }
                }
            }
            if (speed <= 0) {
                throw new IllegalArgumentException("--speed должен быть больше 0");
            }
            return new Options(account, vault, profile, headless, port, direct, out, speed, at, fly, go, repairFree, faction,
                    hunt, then, logout, repairAt, List.copyOf(positional));
        }
    }
}
