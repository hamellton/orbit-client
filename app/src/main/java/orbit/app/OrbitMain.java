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
 *   orbit bot --account main [--account second …] [--config CUBIKON] [--minutes 30] [--headless] [--profile local/client-profile.json]
 *             [--vault local/vault.bin]   # runs the config's module (or the account's config); --minutes: then log out
 *             [--fly 105/65]   # development: fly to a point of the current map once in game
 *             [--go "3-3;3-1:188/125"] # development: travel through portals, legs separated by ';'
 *             [--repair-free [--faction 3]]  # development: take a free repair on the kill screen
 *             [--hunt 1:[ kristallin ]]      # development: after --go, kill NPCs (name part) and pick up boxes
 *             [--then "3-6:20/20"] [--logout] [--repair-at portal]  # then fly on, log out; repair choice
 *   orbit observe [--port 1080] [--direct IP:6001-6007] [--out recordings]   # watch the official client
 *   orbit replay recordings/session-….dorec [--speed 2]
 *   orbit render recordings/session-….dorec --at 30 [--out minimap.png]   # minimap at second 30 as PNG
 *   orbit configs                                                          # config, account and timetable window
 * </pre>
 * With a config the bot runs its module ({@link orbit.bot.run.BotRunner}); closing the window or the end of
 * {@code --minutes} makes it fly to a portal and log out.
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
            case "configs" -> configs();
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("""
                Режимы:
                  orbit bot --account <имя> [--account <имя2> …] [--config <конфиг>] [--minutes N] [--headless]
                                                           бот входит в игру и работает по конфигу (N минут, затем выход)
                  orbit observe [--direct IP:порты]        окно бота по трафику официального клиента (через сниффер)
                  orbit replay <запись.dorec> [--speed N]  проиграть запись сниффера
                  orbit render <запись.dorec> --at <сек> [--out файл.png]  мини-карта на момент записи
                  orbit configs                            окно конфигов, аккаунтов и расписания""");
        System.exit(2);
    }

    // ---- configs ----

    private static void configs() throws InterruptedException {
        CountDownLatch closed = new CountDownLatch(1);
        orbit.app.gui.config.ConfigWindow.open(orbit.config.ConfigStore.openDefault(),
                orbit.config.AccountSettings.DEFAULT_FILE, closed::countDown);
        closed.await();
        System.exit(0);
    }

    // ---- bot ----

    private static void bot(Options o) throws Exception {
        if (o.account == null) {
            System.err.println("Укажите аккаунт: --account <имя> (vault add <имя>)");
            System.exit(2);
        }
        boolean devScenario = o.fly != null || o.go != null || o.hunt != null || o.then != null || o.logout
                || o.repairFree || o.repairAt != null;
        if (!devScenario && o.config != null) {
            loadConfig(o.config, true); // a broken --config stops here, before the password is asked
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
        if (o.accounts.size() > 1 && !devScenario) {
            many(o, vault, profile);
            return;
        }
        Rig rig = rig(o.account, vault);
        WorldFeed feed = rig.feed();
        AuthClient auth = rig.auth();
        GameListener listener = rig.listener();
        SessionSource source = rig.source();
        if (!devScenario && hasPlan(o, o.account)) {
            org.slf4j.MDC.put("account", o.account);
            Sessions run = sessions(o, o.account, rig, profile, 0, () -> System.exit(0));
            runUntilClosed(feed, o.headless, run.close(), run.thread());
            run.close().run();
            run.thread().join(5_000);
            System.exit(0); // the window's threads would keep the program alive
            return;
        }
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

    /** One account's web login, game feed and listener. */
    private record Rig(WorldFeed feed, AuthClient auth, SessionSource source, GameListener listener) {
    }

    private static Rig rig(String account, Vault vault) throws AuthException {
        AuthClient auth = new AuthClient(new HttpSession("", Proxy.NO_PROXY, null), AuthClient.Endpoints.production());
        AccountLogin login = new AccountLogin(auth, vault, account);
        WorldFeed feed = new WorldFeed("бот, " + account, account);
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
        return new Rig(feed, auth, source, listener);
    }

    /**
     * Several accounts in one program: each with its own web login, game connection, sessions, window
     * tab in the window and log ({@code logs/orbit-<account>.log}); the vault and what the bot learns
     * (Cubikon spawns) are shared. Logins are spread out so the site does not see a burst. A tab's stop
     * button stops that account; closing the window stops all; the program ends when all have stopped.
     */
    private static void many(Options o, Vault vault, ClientProfile profile) throws Exception {
        java.util.List<Sessions> all = new java.util.ArrayList<>();
        java.util.List<MainWindow.Tab> tabs = new java.util.ArrayList<>();
        java.util.Random random = new java.util.Random();
        long delay = 0;
        boolean gui = !o.headless && !java.awt.GraphicsEnvironment.isHeadless();
        for (String account : o.accounts) {
            if (vault.get(account) == null) {
                log.error("Аккаунта «{}» нет в хранилище — пропускаю", account);
                continue;
            }
            if (!hasPlan(o, account)) {
                log.warn("У аккаунта «{}» нет конфига — пропускаю", account);
                continue;
            }
            Rig rig = rig(account, vault);
            org.slf4j.MDC.put("account", account);
            Sessions run = sessions(o, account, rig, profile, delay, () -> { });
            org.slf4j.MDC.remove("account");
            all.add(run);
            tabs.add(new MainWindow.Tab(rig.feed(), run.close()));
            log.info("Аккаунт «{}»: старт через {} с", account, delay / 1_000);
            delay += 15_000 + random.nextInt(30_000);
        }
        if (all.isEmpty()) {
            System.err.println("Ни один аккаунт не запущен");
            System.exit(2);
        }
        if (gui) {
            // one window, a tab per account; closing it stops every account (each in its own thread)
            MainWindow.open(tabs, () -> all.forEach(r -> Thread.ofPlatform().name("stop").start(r.close())));
        }
        Runtime.getRuntime().addShutdownHook(new Thread(() -> all.forEach(r -> r.close().run())));
        for (Sessions r : all) {
            r.thread().join();
        }
        log.info("Все аккаунты остановлены");
        System.exit(0);
    }

    /** The website shop of the logged-in account, through the same web session the login used. */
    private static orbit.bot.run.ShopAccess shopFor(AuthClient auth, GameConnection conn) {
        return new orbit.bot.run.ShopAccess() {
            private java.util.Map<String, orbit.auth.Shop.Item> items = java.util.Map.of();

            private orbit.auth.Shop shop() {
                return new orbit.auth.Shop(auth.http(), conn.session().host());
            }

            @Override
            public java.util.Map<String, Offer> offers() throws java.io.IOException {
                items = shop().ammunition();
                java.util.Map<String, Offer> out = new java.util.LinkedHashMap<>();
                items.forEach((id, i) -> out.put(id, new Offer(id, i.price(), i.uridium(), i.maxAmount())));
                return out;
            }

            @Override
            public String buy(String item, int amount) throws java.io.IOException {
                orbit.auth.Shop.Item i = items.get(item);
                if (i == null) {
                    throw new java.io.IOException("нет в магазине");
                }
                String answer = shop().purchase(i, amount);
                if (orbit.auth.Shop.isError(answer)) {
                    throw new java.io.IOException("магазин отказал: " + answer);
                }
                return answer;
            }
        };
    }

    /** A config to run: {@code --config}, the account's config or its timetable. */
    private static boolean hasPlan(Options o, String account) throws java.io.IOException {
        if (o.config != null) {
            return true;
        }
        var acc = orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE).account(account);
        boolean plan = acc.config() != null || (acc.schedule().enabled() && !acc.schedule().entries().isEmpty());
        if (!plan) {
            log.info("Конфиг не выбран (--config или «Аккаунты» в orbit configs) — бот только держит связь");
        }
        return plan;
    }

    /** A checked config, or {@code null} (logged) when it cannot run; {@code exitOnError} for the start. */
    private static orbit.config.BotConfig loadConfig(String name, boolean exitOnError) throws java.io.IOException {
        orbit.config.BotConfig c;
        try {
            c = orbit.config.ConfigStore.openDefault().load(name);
        } catch (java.io.IOException e) {
            if (exitOnError) {
                throw e;
            }
            log.error("Конфиг «{}»: {}", name, e.getMessage());
            return null;
        }
        List<orbit.config.ConfigCheck.Problem> problems = orbit.config.ConfigCheck.check(c, orbit.world.maps.MapCatalog.bundled());
        for (orbit.config.ConfigCheck.Problem p : problems) {
            log.warn("Конфиг «{}»: {}", name, p);
        }
        if (orbit.config.ConfigCheck.hasErrors(problems)) {
            if (exitOnError) {
                System.err.println("Конфиг «" + name + "» с ошибками — бот не запущен");
                System.exit(2);
            }
            log.error("Конфиг «{}» с ошибками — сессия пропущена", name);
            return null;
        }
        log.info("Конфиг «{}»: модуль {}, карта {}", name, c.module(),
                orbit.world.maps.MapCatalog.bundled().info(c.general().workingMap()).name());
        return c;
    }

    /**
     * Sessions: the planner says when to play which config (timetable, work/break, {@code --minutes});
     * each session is a fresh login, played until its end, then a logout at a portal; between them the
     * bot is offline. Closing the window or {@code local/stop-<account>} ends it all gracefully.
     */
    /** An account's sessions: the thread, and a graceful stop (logout at a portal) for the window or the end. */
    private record Sessions(Thread thread, Runnable close) {
    }

    private static Sessions sessions(Options o, String accountName, Rig rig, ClientProfile profile, long startDelayMs,
                                     Runnable endedByItself) throws java.io.IOException {
        WorldFeed feed = rig.feed();
        AuthClient auth = rig.auth();
        SessionSource source = rig.source();
        GameListener listener = rig.listener();
        var account = orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE).account(accountName);
        var planner = new orbit.config.SessionPlanner(account, o.config, accountName.hashCode(), new java.util.Random());
        Path stopFile = Path.of("local", "stop-" + accountName);
        java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();
        long testEnd = o.minutes > 0 ? System.currentTimeMillis() + o.minutes * 60_000L : Long.MAX_VALUE;
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ModuleRun> current = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<GameConnection> currentConn = new java.util.concurrent.atomic.AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            GameConnection c = currentConn.get();
            if (c != null) {
                c.close();
            }
        }));
        Thread sessions = Thread.ofPlatform().name("sessions-" + accountName).start(() -> {
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            try {
                if (startDelayMs > 0) {
                    restUntil(feed, closed, stopFile, java.time.LocalDateTime.now().plusNanos(startDelayMs * 1_000_000),
                            "очередь входа");
                }
                while (!closed.get() && System.currentTimeMillis() < testEnd) {
                    java.time.LocalDateTime now = java.time.LocalDateTime.now();
                    var plan = planner.next(now, name -> {
                        try {
                            return orbit.config.ConfigStore.openDefault().load(name).session();
                        } catch (java.io.IOException e) {
                            return null;
                        }
                    });
                    java.time.LocalDateTime limit = testEnd == Long.MAX_VALUE ? null
                            : java.time.LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(testEnd),
                                    java.time.ZoneId.systemDefault());
                    if (!plan.online()) {
                        restUntil(feed, closed, stopFile, earlier(plan.until(), limit), "не в игре: " + plan.reason());
                        continue;
                    }
                    orbit.config.BotConfig cfg = loadConfig(plan.config(), false);
                    if (cfg == null) {
                        restUntil(feed, closed, stopFile, now.plusMinutes(1), "конфиг «" + plan.config() + "» не запускается");
                        continue;
                    }
                    long end = plan.until() == null ? Long.MAX_VALUE
                            : plan.until().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    String why = plan.reason();
                    if (testEnd < end) {
                        end = testEnd;
                        why = "--minutes " + o.minutes;
                    }
                    feed.newSession();
                    GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(Proxy.NO_PROXY),
                            StartupSequence.clientLike(profile));
                    currentConn.set(conn);
                    java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
                    Thread game = Thread.ofPlatform().name("game-" + accountName).start(() -> {
                        if (ctx != null) {
                            org.slf4j.MDC.setContextMap(ctx);
                        }
                        conn.run();
                    });
                    ModuleRun run = startModule(feed, conn, plan.config(), cfg, end, why, profile, stopFile,
                            shopFor(auth, conn));
                    current.set(run);
                    run.done().await();
                    conn.close();
                    game.join(10_000);
                    current.set(null);
                    if (closed.get() || run.commanded().get() || System.currentTimeMillis() >= testEnd
                            || run.runner().state() == orbit.bot.run.BotRunner.State.FAILED) {
                        break;
                    }
                    if (!plan.breakAfter().isZero() && System.currentTimeMillis() >= end - 1_000) {
                        restUntil(feed, closed, stopFile, earlier(java.time.LocalDateTime.now().plus(plan.breakAfter()), limit),
                                "перерыв");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException | java.io.IOException e) {
                log.error("Ошибка расписания сессий", e);
            }
            log.info("Сессии завершены");
            if (!closed.get()) {
                endedByItself.run(); // limit, command, failure
            }
        });
        Runnable close = () -> {
            if (closed.getAndSet(true)) {
                return;
            }
            ModuleRun run = current.get();
            GameConnection c = currentConn.get();
            if (run != null && c != null) {
                run.finish(c);
            }
        };
        return new Sessions(sessions, close);
    }

    private static java.time.LocalDateTime earlier(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    /** Offline until a moment (or a stop): the window shows why and until when. */
    private static void restUntil(WorldFeed feed, java.util.concurrent.atomic.AtomicBoolean closed, Path stopFile,
                                  java.time.LocalDateTime until, String why) throws InterruptedException {
        java.time.LocalDateTime end = until == null ? java.time.LocalDateTime.now().plusMinutes(1) : until;
        String text = why + " до " + end.toLocalTime().withNano(0);
        log.info("Не в игре: {}", text);
        feed.setState(text);
        feed.setAction(null);
        while (!closed.get() && java.time.LocalDateTime.now().isBefore(end)) {
            if (Files.exists(stopFile)) {
                try {
                    Files.deleteIfExists(stopFile);
                } catch (java.io.IOException ignored) {
                    // checked again next second
                }
                closed.set(true);
                break;
            }
            Thread.sleep(1_000);
        }
    }

    /** A running module: the loop thread and a way to finish it with a logout. */
    private record ModuleRun(orbit.bot.run.BotRunner runner, java.util.concurrent.atomic.AtomicBoolean stopRequested,
                             CountDownLatch done, java.util.concurrent.atomic.AtomicBoolean commanded) {

        /** Asks for a graceful stop (portal, logout) and waits for it, at most two minutes; then closes. */
        void finish(GameConnection conn) {
            stopRequested.set(true);
            try {
                if (!done.await(2, java.util.concurrent.TimeUnit.MINUTES)) {
                    log.warn("Выход из игры не завершился за две минуты — закрываю соединение");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            conn.close();
        }
    }

    private static ModuleRun startModule(WorldFeed feed, GameConnection conn, String configName,
                                         orbit.config.BotConfig config, long untilMillis, String untilReason,
                                         ClientProfile profile, Path stopFile, orbit.bot.run.ShopAccess shop) {
        orbit.world.maps.MapCatalog catalog = orbit.world.maps.MapCatalog.bundled();
        java.util.Random random = new java.util.Random();
        orbit.bot.nav.Navigator nav = new orbit.bot.nav.Navigator(feed::snapshot, conn::send, catalog, random, System::nanoTime);
        orbit.bot.nav.Traveler traveler = new orbit.bot.nav.Traveler(feed::snapshot, conn::send, nav, catalog, random,
                System::nanoTime);
        orbit.bot.act.Actions actions = new orbit.bot.act.Actions(feed::snapshot, conn::send, () -> {
            var session = conn.session();
            return new orbit.bot.act.Actions.Login(session.userId(), session.sessionId());
        }, orbit.bot.act.Actions.Screen.fromReadyString(profile.readyTypeStr()), random);
        orbit.bot.run.ShipSetup ship = new orbit.bot.run.ShipSetup(feed::snapshot, actions, System::nanoTime, random);
        orbit.bot.run.Stats stats = new orbit.bot.run.Stats();
        feed.setStats(stats);
        orbit.bot.run.ConfigWatch watch = new orbit.bot.run.ConfigWatch(orbit.config.ConfigStore.openDefault(), configName,
                config, catalog);
        orbit.bot.run.BotContext ctx = new orbit.bot.run.BotContext(feed::snapshot, watch::current, actions, nav, traveler,
                catalog, random, System::nanoTime, conn::repair, ship, Path.of("local"), stats);
        orbit.bot.run.BotRunner runner = new orbit.bot.run.BotRunner(ctx, feed::loggedOut, feed::setAction);
        runner.setShop(shop);
        java.util.concurrent.atomic.AtomicBoolean stopRequested = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean commanded = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        long until = untilMillis;
        java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofPlatform().name("bot-loop").daemon().start(() -> {
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            try {
                waitAlive(feed);
                Thread.sleep(4_000 + random.nextInt(3_000)); // look around after logging in
                if (until != Long.MAX_VALUE) {
                    log.info("Бот работает до {} ({}), затем выйдет из игры",
                            java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(until),
                                    java.time.ZoneId.systemDefault()).withNano(0), untilReason);
                }
                long nextWatch = 0;
                while (true) {
                    if (System.currentTimeMillis() > nextWatch) {
                        if (watch.poll()) {
                            runner.configChanged();
                        }
                        nextWatch = System.currentTimeMillis() + 1_000;
                    }
                    if (Files.exists(stopFile)) {
                        // another process asks for a graceful stop (portal, logout)
                        try {
                            Files.deleteIfExists(stopFile);
                        } catch (java.io.IOException e) {
                            log.warn("Не удалить {}: {}", stopFile, e.getMessage());
                        }
                        commanded.set(true);
                        runner.stop("остановлен по команде (" + stopFile.getFileName() + ")");
                    }
                    if (stopRequested.get()) {
                        runner.stop("остановлен пользователем");
                    } else if (System.currentTimeMillis() > until) {
                        runner.stop("время вышло: " + untilReason);
                    }
                    runner.tick();
                    var st = runner.state();
                    if (st == orbit.bot.run.BotRunner.State.STOPPED || st == orbit.bot.run.BotRunner.State.FAILED) {
                        break;
                    }
                    Thread.sleep(100);
                }
                log.info("Бот завершил работу: {}", runner.status());
                log.info("Итог сессии: {}", feed.summary());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                log.error("Ошибка в цикле бота", e);
            } finally {
                if (!stopRequested.get()) {
                    conn.close(); // finished by itself (time, deaths, error, command): the session is over
                }
                done.countDown();
            }
        });
        return new ModuleRun(runner, stopRequested, done, commanded);
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
                if (!travel(feed, traveler, before, catalog, actions)) {
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
                            if (!travel(feed, traveler, List.of(new Leg(catalog.info(huntMap), Double.NaN, Double.NaN)), catalog, actions)) {
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
                if (!travel(feed, traveler, after, catalog, actions)) {
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

    /** Below this hull share the ship is repaired before flying on (a jump may land among enemies). */
    static final double TRAVEL_MIN_HP = 0.7;

    /**
     * Repairs with the repair robot up to 90 % before a trip, when hurt and nobody attacks; gives up
     * after three minutes or when the robot is not available.
     */
    private static void healUp(WorldFeed feed, orbit.bot.act.Actions actions) throws InterruptedException {
        var h = feed.snapshot().hero();
        if (h.health().hpPercent() < 0 || h.health().hpPercent() >= TRAVEL_MIN_HP) {
            return;
        }
        var bot = feed.snapshot().items().get("equipment_extra_repbot_rep");
        if (bot == null || !bot.available()) {
            log.warn("Прочность {}%, робота-ремонтника нет — лечу так", (int) (h.health().hpPercent() * 100));
            return;
        }
        log.info("Прочность {}% — чинюсь перед перелётом", (int) (h.health().hpPercent() * 100));
        feed.setAction("ремонт");
        long until = System.currentTimeMillis() + 3 * 60_000;
        long pressed = 0;
        while (System.currentTimeMillis() < until) {
            var w = feed.snapshot();
            if (w.hero().health().hpPercent() >= 0.9 || !w.hero().alive()) {
                break;
            }
            var item = w.items().get("equipment_extra_repbot_rep");
            if (w.hero().attackers().isEmpty() && item != null && item.remainingMs(feed.now()) == 0
                    && System.currentTimeMillis() - pressed > 10_000) {
                actions.useItem("equipment_extra_repbot_rep");
                pressed = System.currentTimeMillis();
            }
            Thread.sleep(500);
        }
        log.info("Прочность {}%", (int) (feed.snapshot().hero().health().hpPercent() * 100));
    }

    /** Runs legs one after another; waits through a death and repair. False if a leg cannot be done. */
    private static boolean travel(WorldFeed feed, orbit.bot.nav.Traveler traveler, List<Leg> legs,
                                  orbit.world.maps.MapCatalog catalog, orbit.bot.act.Actions actions)
            throws InterruptedException {
        for (Leg leg : legs) {
            healUp(feed, actions);
            log.info("Перелёт на {}{}", leg.map().name(),
                    Double.isNaN(leg.x()) ? "" : " " + (int) leg.x() / 100 + "/" + (int) leg.y() / 100);
            feed.setAction("перелёт на " + leg.map().name());
            traveler.travel(leg.map().id(), leg.x(), leg.y());
            long nextLog = 0;
            while (traveler.state() == orbit.bot.nav.Traveler.State.TRAVELLING) {
                if (!feed.snapshot().hero().alive()) {
                    waitAlive(feed);
                    healUp(feed, actions);
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
                   String then, boolean logout, String repairAt, String config, int minutes, List<String> accounts,
                   List<String> positional) {

        static Options parse(List<String> args) {
            String account = null;
            List<String> accounts = new java.util.ArrayList<>();
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
            String config = null;
            int minutes = 0;
            List<String> positional = new java.util.ArrayList<>();
            for (int i = 0; i < args.size(); i++) {
                switch (args.get(i)) {
                    case "--account" -> {
                        account = account == null ? args.get(i + 1) : account;
                        accounts.add(args.get(++i));
                    }
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
                    case "--config" -> config = args.get(++i);
                    case "--minutes" -> minutes = Integer.parseInt(args.get(++i));
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
                    hunt, then, logout, repairAt, config, minutes, List.copyOf(accounts), List.copyOf(positional));
        }
    }
}
