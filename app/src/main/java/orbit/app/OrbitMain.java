package orbit.app;

import orbit.app.gui.MinimapBackgrounds;
import orbit.auth.proxy.ProxyRoute;
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
        // a dark macOS title bar; read when AWT starts, so before any window
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        WorldFeed.learnNpcMaps = true; // the NPCs met are remembered by map (local/npc-maps.json)
        orbit.bot.mod.FightLog.enable(); // temporary: each fight in the log and local/fight-log.csv
        orbit.bot.mod.RadiusTuner.keepOnDisk(); // the distances learned per NPC kind (local/npc-radius-learned.json)
        orbit.bot.mod.Threats.keepOnDisk(); // the NPC kinds that attack on sight (local/npc-aggressive.json)
        orbit.bot.mod.AbilityUse.keepOnDisk(); // EMP-01 and ISH-01 spent per pilot (local/ability-use.json)
        // portals the data lacks, learned by jumping (5-2 → 5-1)
        orbit.world.maps.MapCatalog.bundled().useLearned(orbit.world.maps.MapCatalog.LEARNED);
        if (args.length == 0) {
            manager(Options.parse(List.of()));
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
            case "proxy-check" -> proxyCheck(o);
            case "manager" -> manager(o);
            default -> usage();
        }
    }

    private static void usage() {
        System.err.println("""
                Режимы:
                  orbit                                    окно аккаунтов: добавить, прокси, запуск и остановка
                  orbit bot --account <имя> [--account <имя2> …] [--config <конфиг>] [--minutes N] [--headless]
                                                           бот входит в игру и работает по конфигу (N минут, затем выход)
                  orbit observe [--direct IP:порты]        окно бота по трафику официального клиента (через сниффер)
                  orbit replay <запись.dorec> [--speed N]  проиграть запись сниффера
                  orbit render <запись.dorec> --at <сек> [--out файл.png]  мини-карта на момент записи
                  orbit configs                            окно конфигов, аккаунтов и расписания
                  orbit proxy-check --account <имя> […]    проверить прокси аккаунтов (без входа в игру)""");
        System.exit(2);
    }

    // ---- proxy-check ----

    /** Each account's proxy: reaches the game's site? which public IP? No website login. */
    private static void proxyCheck(Options o) throws Exception {
        if (o.accounts.isEmpty()) {
            System.err.println("Укажите аккаунт: --account <имя> (прокси задаётся: vault set-proxy <имя>)");
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
        int failed = 0;
        for (String account : o.accounts) {
            orbit.secrets.Account a = vault.get(loginOf(account));
            if (a == null) {
                System.out.println(account + ": нет в хранилище");
                failed++;
                continue;
            }
            orbit.secrets.ProxySpec spec;
            try {
                spec = a.proxySpec();
            } catch (IllegalArgumentException e) {
                System.out.println(account + ": прокси не разбирается — " + e.getMessage());
                failed++;
                continue;
            }
            if (spec.direct()) {
                System.out.println(account + ": без прокси (напрямую)");
                continue;
            }
            try (ProxyRoute route = ProxyRoute.of(spec)) {
                System.out.println(account + ": " + route.check().describe());
            } catch (java.io.IOException e) {
                System.out.println(account + ": " + spec.describe() + " — ошибка: " + e.getMessage());
                failed++;
            }
        }
        System.exit(failed == 0 ? 0 : 1);
    }

    // ---- manager ----

    /**
     * The account manager: the master password in a window, then the accounts — add, edit, proxy check,
     * start and stop each or all — with a tab per started account and «Все».
     */
    private static void manager(Options o) throws Exception {
        if (java.awt.GraphicsEnvironment.isHeadless()) {
            System.err.println("Окно аккаунтов недоступно без экрана — используйте orbit bot --headless");
            System.exit(2);
        }
        Vault vault = orbit.app.gui.VaultDialog.open(o.vault);
        if (vault == null) {
            System.exit(0);
            return;
        }
        ClientProfile profile = Files.exists(o.profile) ? ClientProfile.load(o.profile) : ClientProfile.minimal();
        if (profile.windowSettings() == null) {
            log.warn("Профиль клиента {} не найден — раскладка окон и клавиши отправляться не будут", o.profile);
        }
        CountDownLatch closed = new CountDownLatch(1);
        // «orbit manager --account a --account b»: these start at once (a restart on a server without anybody at it)
        orbit.app.gui.ManagerWindow.open(vault, name -> new AccountRunner(name, vault, profile), closed::countDown,
                o.accounts());
        closed.await();
        System.exit(0);
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
        // one account with a config or a timetable runs like several: the same window, a config switch
        if (!devScenario && (o.accounts.size() > 1 || hasPlan(o, o.account))) {
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
        GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(rig.route().connector()),
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
    record Rig(WorldFeed feed, AuthClient auth, SessionSource source, GameListener listener, ProxyRoute route) {
    }

    /** The vault name of {@code main@ru5}: {@code main}. */
    static String loginOf(String account) {
        int at = account.indexOf('@');
        return at < 0 ? account : account.substring(0, at);
    }

    /** The server of {@code main@ru5}: {@code ru5}; {@code null} = the account's default. */
    static String serverOf(String account) {
        int at = account.indexOf('@');
        return at < 0 ? null : account.substring(at + 1).strip().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * @param account a vault name, or {@code name@server} for another server of the same login
     *                ({@code main@ru5}); logs, tabs, stop files and «Аккаунты» use the whole string
     */
    private static Rig rig(String account, Vault vault) throws AuthException {
        return rig(account, vault, new WorldFeed("бот, " + account, account));
    }

    /** The same with a feed that outlives one start (the manager keeps an account's tab across restarts). */
    static Rig rig(String account, Vault vault, WorldFeed feed) throws AuthException {
        ProxyRoute route = route(account, vault);
        AuthClient auth = new AuthClient(new HttpSession("", route.httpProxy()), AuthClient.Endpoints.production());
        AccountLogin login = new AccountLogin(auth, vault, loginOf(account), serverOf(account));
        feed.setServer(login.server());
        feed.setProxy(route::describe, !route.direct());
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
                if (state == State.RECONNECTING) {
                    feed.setLinkLost(true);
                } else if (state == State.ONLINE || state == State.DEAD || state == State.STOPPED) {
                    feed.setLinkLost(false);
                }
                log.info("Состояние: {} — {}", state, reason);
            }
        };
        SessionSource source = new SessionSource() {
            @Override
            public GameSession fresh() throws AuthException {
                route.verify(); // a dead proxy means no login, never a direct one
                noteIp(account, route, feed);
                return login.login();
            }

            @Override
            public MapServers.Address serverForMap(GameSession s, int mapId) throws AuthException {
                return auth.serverForMap(s, mapId);
            }
        };
        return new Rig(feed, auth, source, listener, route);
    }

    /**
     * Remembers the IP and country the proxy showed before a login; a country other than last time is told in the log
     * and the window (plan 5в.5: an account's IP must not jump between countries). The login goes on.
     */
    static void noteIp(String account, ProxyRoute route, WorldFeed feed) {
        if (route.direct() || route.externalIp() == null) {
            return;
        }
        String before = IpHistory.shared().record(account, route.externalIp(), route.country());
        if (before != null) {
            String why = "страна IP сменилась: была " + before + ", теперь " + route.country() + " (IP " + route.externalIp()
                    + ") — проверьте прокси";
            log.warn("Прокси: {}", why);
            feed.warn(why);
        }
    }

    /**
     * The account's proxy from the vault: its website requests, game sockets and the minimap pictures
     * all go through it.
     */
    private static ProxyRoute route(String account, Vault vault) throws AuthException {
        orbit.secrets.Account a = vault.get(loginOf(account));
        orbit.secrets.ProxySpec spec;
        try {
            spec = a == null ? orbit.secrets.ProxySpec.DIRECT : a.proxySpec();
        } catch (IllegalArgumentException e) {
            throw new AuthException(AuthException.Reason.PROXY_REJECTED,
                    "Неверный прокси: " + e.getMessage());
        }
        if (spec.direct()) {
            return ProxyRoute.none();
        }
        try {
            ProxyRoute route = ProxyRoute.of(spec);
            log.info("Аккаунт «{}»: {}", account, spec.describe());
            MinimapBackgrounds.useProxy(route.selector());
            return route;
        } catch (java.io.IOException e) {
            throw new AuthException(AuthException.Reason.UNAVAILABLE, "Прокси не запустился: " + e.getMessage(), e);
        }
    }

    /**
     * The account's ships from the site, for the account window, without the game: the website login the bot
     * does (saved dosid, else another server's, the password last — new dosids are kept), through the account's
     * proxy (none reachable = no login), then the hangar list. Kept in the account's settings.
     *
     * @throws java.io.IOException with a message for the user
     */
    public static List<orbit.config.AccountSettings.Ship> siteShips(Vault vault, String account) throws java.io.IOException {
        orbit.secrets.Account a = vault.get(loginOf(account));
        if (a == null) {
            throw new java.io.IOException("аккаунта нет в хранилище");
        }
        orbit.secrets.ProxySpec spec;
        try {
            spec = a.proxySpec();
        } catch (IllegalArgumentException e) {
            throw new java.io.IOException("неверный прокси: " + e.getMessage());
        }
        try (ProxyRoute route = spec.direct() ? ProxyRoute.none() : ProxyRoute.of(spec)) {
            route.verify();
            AuthClient auth = new AuthClient(new HttpSession("", route.httpProxy()), AuthClient.Endpoints.production());
            GameSession session = new AccountLogin(auth, vault, loginOf(account), serverOf(account)).login();
            List<orbit.auth.Hangars.Hangar> list = new orbit.auth.Hangars(auth.http(), session.host()).list();
            List<orbit.config.AccountSettings.Ship> ships = list.stream()
                    .map(h -> new orbit.config.AccountSettings.Ship(h.id(), h.ship(), h.design())).toList();
            synchronized (OrbitMain.class) {
                var file = orbit.config.AccountSettings.DEFAULT_FILE;
                var all = orbit.config.AccountSettings.load(file);
                all.with(account, all.account(account).withHangars(ships)).save(file);
            }
            return ships;
        } catch (AuthException e) {
            throw new java.io.IOException(e.getMessage());
        }
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
            if (vault.get(loginOf(account)) == null) {
                log.error("Аккаунта «{}» нет в хранилище — пропускаю", loginOf(account));
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
            tabs.add(new MainWindow.Tab(rig.feed(), run.close(), run.config(), run.switchConfig()));
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
            private final java.util.Map<String, orbit.auth.Shop.Item> items = new java.util.concurrent.ConcurrentHashMap<>();

            private orbit.auth.Shop shop() {
                return new orbit.auth.Shop(auth.http(), conn.session().host());
            }

            @Override
            public java.util.Map<String, Offer> offers() throws java.io.IOException {
                var ammo = shop().ammunition();
                items.putAll(ammo);
                java.util.Map<String, Offer> out = new java.util.LinkedHashMap<>();
                ammo.forEach((id, i) -> out.put(id, new Offer(id, i.price(), i.uridium(), i.maxAmount())));
                // the cloaking CPU is not on the ammunition page: bought as CARNAGE's BLCycle buys it (its log: category
                // "special", one at a time, 15 750 uridium for CL04K-XL)
                orbit.auth.Shop.Item cloak = new orbit.auth.Shop.Item("equipment_extra_cpu_cl04k-xl", "special", "CL04K-XL",
                        15_750, "uridium", 1);
                items.putIfAbsent(cloak.id(), cloak);
                out.putIfAbsent(cloak.id(), new Offer(cloak.id(), cloak.price(), true, 1));
                return out;
            }

            @Override
            public java.util.Map<String, Offer> boosterOffers() throws java.io.IOException {
                var boosters = shop().boosters();
                items.putAll(boosters);
                java.util.Map<String, Offer> out = new java.util.LinkedHashMap<>();
                boosters.forEach((id, i) -> out.put(i.code(), new Offer(id, i.price(), i.uridium(), i.maxAmount())));
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
    /**
     * @param config       the config the account runs now
     * @param switchConfig run another config from now on (also saved as the account's config)
     */
    /** @param kill ends everything at once: the connection is closed without flying to a portal */
    record Sessions(Thread thread, Runnable close, java.util.function.Supplier<String> config,
                            java.util.function.Consumer<String> switchConfig, Runnable kill) {
    }

    static Sessions sessions(Options o, String accountName, Rig rig, ClientProfile profile, long startDelayMs,
                                     Runnable endedByItself) throws java.io.IOException {
        WorldFeed feed = rig.feed();
        AuthClient auth = rig.auth();
        SessionSource source = rig.source();
        GameListener listener = rig.listener();
        var account = orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE).account(accountName);
        var planner = new orbit.config.SessionPlanner(account, o.config, accountName.hashCode(), new java.util.Random());
        Path stopFile = Path.of("local", "stop-" + accountName);
        java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();
        java.util.concurrent.atomic.AtomicReference<String> playing = new java.util.concurrent.atomic.AtomicReference<>(
                o.config != null ? o.config : account.config());
        long testEnd = o.minutes > 0 ? System.currentTimeMillis() + o.minutes * 60_000L : Long.MAX_VALUE;
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicReference<ModuleRun> current = new java.util.concurrent.atomic.AtomicReference<>();
        // chosen in the window: wins over the plan until the program ends
        java.util.concurrent.atomic.AtomicReference<String> chosen = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<GameConnection> currentConn = new java.util.concurrent.atomic.AtomicReference<>();
        // the account's site of the last game session (the hangars are switched there between sessions)
        java.util.concurrent.atomic.AtomicReference<String> lastHost = new java.util.concurrent.atomic.AtomicReference<>();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            GameConnection c = currentConn.get();
            if (c != null) {
                c.close();
            }
        }));
        // one summary line an hour on the clock, while the account runs
        Thread.ofPlatform().name("hourly-" + accountName).daemon().start(() -> {
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            HourlyReport hourly = HourlyReport.of(feed);
            DailyTotals daily = DailyTotals.of(accountName);
            while (!closed.get()) {
                try {
                    Thread.sleep(1_000);
                } catch (InterruptedException e) {
                    daily.flush();
                    return;
                }
                long nowMs = System.currentTimeMillis();
                hourly.tick(nowMs, feed.sessionStartNanos() != 0 && !feed.loggedOut());
                daily.update(HourlyReport.Totals.of(feed), nowMs); // «За сегодня» over all runs
            }
            daily.flush();
        });
        Thread sessions = Thread.ofPlatform().name("sessions-" + accountName).start(() -> {
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            try {
                if (startDelayMs > 0) {
                    restUntil(feed, closed, stopFile, java.time.LocalDateTime.now().plusNanos(startDelayMs * 1_000_000),
                            "Очередь входа");
                }
                boolean rested = false;
                int failures = 0; // sessions in a row whose game connection ended by itself
                orbit.bot.run.BotRunner afterShipChange = null; // the session a ship change continues
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
                        afterShipChange = null; // a break between: the next login is a new session
                        restUntil(feed, closed, stopFile, earlier(plan.until(), limit), capitalized(plan.reason()));
                        rested = true;
                        continue;
                    }
                    if (rested) {
                        // after a break the accounts would all log in the same second: keep their queue
                        rested = false;
                        long queue = startDelayMs + java.util.concurrent.ThreadLocalRandom.current().nextLong(3_000, 20_000);
                        restUntil(feed, closed, stopFile, earlier(now.plusNanos(queue * 1_000_000), limit), "Очередь входа");
                        continue;
                    }
                    String cfgName = chosen.get() != null ? chosen.get() : plan.config();
                    playing.set(cfgName);
                    orbit.config.BotConfig cfg = loadConfig(cfgName, false);
                    if (cfg == null) {
                        afterShipChange = null;
                        restUntil(feed, closed, stopFile, now.plusMinutes(1), "Ошибка в конфиге «" + cfgName + "»");
                        continue;
                    }
                    long end = plan.until() == null ? Long.MAX_VALUE
                            : plan.until().atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
                    String why = plan.reason();
                    if (testEnd < end) {
                        end = testEnd;
                        why = "--minutes " + o.minutes;
                    }
                    orbit.bot.run.BotRunner previous = afterShipChange;
                    afterShipChange = null;
                    if (previous != null) {
                        feed.resumeSession(); // a ship change is no new session: clock, counters, memory go on
                    } else {
                        feed.newSession();
                    }
                    GameConnection conn = new GameConnection(source, listener, ConnectionSettings.defaults(rig.route().connector()),
                            StartupSequence.clientLike(profile));
                    currentConn.set(conn);
                    java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
                    Thread game = Thread.ofPlatform().name("game-" + accountName).start(() -> {
                        if (ctx != null) {
                            org.slf4j.MDC.setContextMap(ctx);
                        }
                        conn.run();
                    });
                    orbit.bot.run.AccountShips ships = accountShips(accountName);
                    ModuleRun run = startModule(feed, conn, cfgName, cfg, end, why, profile, stopFile,
                            shopFor(auth, conn), () -> !game.isAlive(), ships, previous);
                    current.set(run);
                    run.runner().setClickHold(clickHold(accountName));
                    // every module: which ship is in use, and the list the «Ангар» window shows (the user, 2026-11-14:
                    // «в кораблях пусто» — it was read for palladium only)
                    watchHangar(auth, conn, run.runner(), lastHost, accountName, cfg, ships);
                    if (closed.get()) {
                        // stopped while logging in: the stop found no module to finish, so finish this one
                        Thread.ofPlatform().name("stop-" + accountName).start(() -> run.finish(conn));
                    }
                    run.done().await();
                    conn.close();
                    game.join(10_000);
                    current.set(null);
                    if (conn.trouble() != null) {
                        // a kick, a login from elsewhere, a typed server line: no retries, the user looks first
                        log.error("Аккаунт остановлен: {}. Повторов входа не будет — проверьте аккаунт", conn.trouble());
                        feed.alert(conn.trouble());
                        break;
                    }
                    int wantHangar = run.runner().hangarRequest();
                    if (wantHangar != 0 && !closed.get() && lastHost.get() != null) {
                        // the module asked for another ship (two-ship palladium): switch it on the site, log in again
                        if (!switchHangar(auth, source, lastHost.get(), wantHangar, cfg, feed)) {
                            log.error("Корабль на сайте не сменился — аккаунт остановлен, чтобы не входить и выходить"
                                    + " по кругу; проверьте корабли палладия в настройках аккаунта");
                            break;
                        }
                        failures = 0;
                        afterShipChange = run.runner();
                        continue;
                    }
                    if (run.connectionEnded().get() && !closed.get()) {
                        // the login failed or the server ended the session: try again later, a few times
                        String reason = feed.state();
                        if (++failures >= MAX_FAILED_SESSIONS) {
                            log.error("Связь с игрой не удалась {} раза подряд — аккаунт остановлен: {}", failures, reason);
                            break;
                        }
                        log.warn("Связь с игрой закончилась ({}) — повтор через {} мин (попытка {} из {})", reason,
                                RETRY_AFTER_FAILURE.toMinutes(), failures, MAX_FAILED_SESSIONS);
                        restUntil(feed, closed, stopFile, earlier(java.time.LocalDateTime.now().plus(RETRY_AFTER_FAILURE),
                                limit), "Повтор входа");
                        continue;
                    }
                    failures = 0;
                    if (closed.get() || run.commanded().get() || System.currentTimeMillis() >= testEnd
                            || run.runner().state() == orbit.bot.run.BotRunner.State.FAILED) {
                        break;
                    }
                    if (!plan.breakAfter().isZero() && System.currentTimeMillis() >= end - 1_000) {
                        restUntil(feed, closed, stopFile, earlier(java.time.LocalDateTime.now().plus(plan.breakAfter()), limit),
                                "Перерыв");
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
        java.util.function.Consumer<String> switchConfig = name -> {
            chosen.set(name);
            playing.set(name);
            try {
                var file = orbit.config.AccountSettings.DEFAULT_FILE;
                var all = orbit.config.AccountSettings.load(file);
                all.with(accountName, all.account(accountName).withConfig(name))
                        .save(file);
            } catch (java.io.IOException e) {
                log.warn("Не сохранить выбор конфига: {}", e.getMessage());
            }
            ModuleRun run = current.get();
            if (run != null) {
                run.watch().switchTo(name);
            }
        };
        Runnable kill = () -> {
            closed.set(true);
            ModuleRun run = current.get();
            if (run != null) {
                run.stopRequested().set(true); // a stop, not a lost connection: no retry
            }
            GameConnection c = currentConn.get();
            if (c != null) {
                if (feed.sessionStartNanos() != 0 && !feed.loggedOut()) {
                    log.warn("Экстренная остановка: соединение закрыто без выхода у портала");
                } else {
                    log.info("Остановлен (ещё не в игре)");
                }
                c.close();
            }
        };
        return new Sessions(sessions, close, playing::get, switchConfig, kill);
    }

    /**
     * Reads the account's hangars once the game session is known and tells the runner which one is in use; logs
     * them (id, ship) — the two-ship palladium needs it to know whether the collect or the sell ship is out.
     */
    private static void watchHangar(AuthClient auth, GameConnection conn, orbit.bot.run.BotRunner runner,
                                    java.util.concurrent.atomic.AtomicReference<String> lastHost, String accountName,
                                    orbit.config.BotConfig cfg, orbit.bot.run.AccountShips ships) {
        java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("hangars").start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            try {
                for (int i = 0; i < 120 && (conn.session() == null || conn.session().host() == null); i++) {
                    Thread.sleep(500);
                }
                if (conn.session() == null) {
                    return;
                }
                String host = conn.session().host();
                lastHost.set(host);
                var list = new orbit.auth.Hangars(auth.http(), host).list();
                rememberHangars(accountName, list, cfg);
                var pair = accountShips(accountName); // DarkBot's pair may have just been moved to the account
                ships.setPalladium(pair.palladiumCollect(), pair.palladiumSell());
                if (!HANGAR_SHAPE_LOGGED.getAndSet(true)) {
                    log.info("Ангары: поля ответа сайта (без значений) — {}", orbit.auth.Hangars.lastShape());
                }
                var active = list.stream().filter(orbit.auth.Hangars.Hangar::active).findFirst().orElse(null);
                log.info("Ангары: кораблей {}; сейчас {}", list.size(), active == null ? "не понять"
                        : new orbit.config.AccountSettings.Ship(active.id(), active.ship(), active.design()).label());
                if (active != null) {
                    runner.setActiveHangar(active.id());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (java.io.IOException | RuntimeException e) {
                log.warn("Ангары с сайта не прочитать: {}", e.getMessage());
            }
        });
    }

    /** The account's wait after a click on the map (its settings, 1 s when none). */
    private static int clickHold(String accountName) {
        try {
            return orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE).account(accountName)
                    .clickHold();
        } catch (java.io.IOException e) {
            return orbit.config.AccountSettings.Entry.DEFAULT_CLICK_HOLD_MS;
        }
    }

    /**
     * The account's ships for the bot: the palladium pair chosen in the account's settings ({@code 0} = none, the
     * module then takes the config's, imported from DarkBot).
     */
    private static orbit.bot.run.AccountShips accountShips(String accountName) {
        orbit.bot.run.AccountShips ships = new orbit.bot.run.AccountShips();
        try {
            var p = orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE).account(accountName).palladium();
            if (p != null && p.usable()) {
                ships.setPalladium(p.collect(), p.sell());
            }
        } catch (java.io.IOException e) {
            log.warn("Настройки аккаунта не прочитать: {}", e.getMessage());
        }
        return ships;
    }

    /**
     * Keeps the site's list of ships in the account's settings (the account window picks from it); an account
     * without a palladium pair takes the config's one (DarkBot's), if both of those ships are the account's.
     */
    private static synchronized void rememberHangars(String accountName, List<orbit.auth.Hangars.Hangar> list,
                                                     orbit.config.BotConfig cfg) {
        try {
            var file = orbit.config.AccountSettings.DEFAULT_FILE;
            var all = orbit.config.AccountSettings.load(file);
            var entry = all.account(accountName); // main@ru5 without its own entry: main's, kept as its own from now
            List<orbit.config.AccountSettings.Ship> ships = list.stream()
                    .map(h -> new orbit.config.AccountSettings.Ship(h.id(), h.ship(), h.design())).toList();
            var next = entry.withHangars(ships);
            if (entry.palladium() == null || !entry.palladium().usable()) {
                var s = cfg.modules().get(orbit.config.BotConfig.Module.PALLADIUM.key());
                int c = s == null ? 0 : s.path("collectHangar").asInt(0);
                int sell = s == null ? 0 : s.path("sellHangar").asInt(0);
                var pair = new orbit.config.AccountSettings.PalladiumShips(c, sell);
                java.util.Set<Integer> ids = new java.util.HashSet<>();
                ships.forEach(x -> ids.add(x.id()));
                if (pair.usable() && ids.contains(c) && ids.contains(sell)) {
                    next = next.withPalladium(pair);
                    log.info("Ангары палладия из конфига перенесены в настройки аккаунта");
                }
            }
            if (!next.equals(all.accounts().get(accountName))) {
                all.with(accountName, next).save(file);
            }
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Список кораблей не сохранить: {}", e.getMessage());
        }
    }

    private static final java.util.concurrent.atomic.AtomicBoolean HANGAR_SHAPE_LOGGED =
            new java.util.concurrent.atomic.AtomicBoolean();

    private static String shipName(String lootId) {
        return lootId == null || lootId.isBlank() ? "?" : lootId.replaceFirst("^ship_", "");
    }

    /** Attempts at a refused switch, and the pause between them (DarkBot keeps 12 s between hangar actions). */
    static final int SWITCH_TRIES = 3;
    static final long SWITCH_GAP_MS = 15_000;

    /**
     * Makes {@code want} the active ship on the site, then waits the module's extra pause (DmPlugin's 5 s); a ship
     * the account does not have is never asked for. A refusal («too short a time between two actions») is tried
     * again after a pause, a few times. A stale site login (a page instead of the list, after a long session) is
     * renewed once by a new site login, the same one a game login does.
     *
     * @return false when the ship could not be made active (the account then stops: no log in/out loop)
     */
    static boolean switchHangar(AuthClient auth, SessionSource source, String host, int want, orbit.config.BotConfig cfg,
            WorldFeed feed) throws InterruptedException {
        var hangars = new orbit.auth.Hangars(auth.http(), host);
        feed.setState("Смена корабля");
        boolean renewed = false;
        for (int attempt = 1; ; attempt++) {
            Thread.sleep(attempt == 1 ? 3_000 : SWITCH_GAP_MS);
            try {
                var list = hangars.list();
                if (list.stream().noneMatch(h -> h.id() == want)) {
                    log.error("Ангара {} у аккаунта нет — корабль не меняю", want);
                    return false;
                }
                int active = list.stream().filter(orbit.auth.Hangars.Hangar::active).mapToInt(orbit.auth.Hangars.Hangar::id)
                        .findFirst().orElse(0);
                if (active != want) {
                    Thread.sleep(SWITCH_GAP_MS); // the list and the switch are two actions for the site
                    hangars.activate(active, want);
                    log.info("Корабль сменён на сайте: ангар {} → {}", active, want);
                }
                break;
            } catch (orbit.auth.Hangars.Expired e) {
                if (renewed) {
                    log.warn("Корабль не сменить: вход на сайт устарел и после нового входа");
                    return false;
                }
                renewed = true;
                log.info("Вход на сайт устарел — вхожу на сайт заново, чтобы сменить корабль");
                try {
                    source.fresh();
                } catch (orbit.auth.AuthException a) {
                    log.warn("Корабль не сменить: новый вход на сайт не удался — {}", a.getMessage());
                    return false;
                }
                attempt = 0; // the tries start again with the new login
            } catch (java.io.IOException e) {
                if (attempt >= SWITCH_TRIES) {
                    log.warn("Корабль не сменить: {}", e.getMessage());
                    return false;
                }
                log.warn("Корабль не сменить: {} — ещё раз через {} с", e.getMessage(), SWITCH_GAP_MS / 1000);
            }
        }
        var s = cfg.modules().get(orbit.config.BotConfig.Module.PALLADIUM.key());
        long extra = s == null ? 5 : Math.max(0, s.path("aditionalWaitingTime").asInt(5));
        Thread.sleep(2_000 + extra * 1_000);
        return true;
    }

    /** After this many sessions in a row without a game connection the account stops. */
    static final int MAX_FAILED_SESSIONS = 3;
    static final java.time.Duration RETRY_AFTER_FAILURE = java.time.Duration.ofMinutes(5);

    private static java.time.LocalDateTime earlier(java.time.LocalDateTime a, java.time.LocalDateTime b) {
        return a == null ? b : b == null ? a : a.isBefore(b) ? a : b;
    }

    private static String capitalized(String s) {
        return s == null || s.isEmpty() ? "Не в игре" : Character.toUpperCase(s.charAt(0)) + s.substring(1);
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
    /** @param connectionEnded the game connection ended by itself (failed login, server closed it), not a logout */
    private record ModuleRun(orbit.bot.run.BotRunner runner, java.util.concurrent.atomic.AtomicBoolean stopRequested,
                             CountDownLatch done, java.util.concurrent.atomic.AtomicBoolean commanded,
                             orbit.bot.run.ConfigWatch watch, java.util.concurrent.atomic.AtomicBoolean connectionEnded) {

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

    /**
     * Where modules keep what they learn: {@code local} for int1 (as before), {@code local/<server>} for
     * other servers — the same map id is another map with other cubes there.
     */
    private static Path dataDir(WorldFeed feed) {
        String server = feed.server();
        return server == null || server.isBlank() || server.equals("int1") ? Path.of("local") : Path.of("local", server);
    }

    private static ModuleRun startModule(WorldFeed feed, GameConnection conn, String configName,
                                         orbit.config.BotConfig config, long untilMillis, String untilReason,
                                         ClientProfile profile, Path stopFile, orbit.bot.run.ShopAccess shop,
                                         java.util.function.BooleanSupplier connectionOver, orbit.bot.run.AccountShips ships,
                                         orbit.bot.run.BotRunner previous) {
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
        // after a ship change the session's counters go on (live 2026-11-12: «за сессию» started from 1 again)
        orbit.bot.run.Stats stats = previous != null && feed.stats() != null ? feed.stats() : new orbit.bot.run.Stats();
        feed.setStats(stats);
        orbit.bot.run.ConfigWatch watch = new orbit.bot.run.ConfigWatch(orbit.config.ConfigStore.openDefault(), configName,
                config, catalog);
        nav.setAvoidMines(() -> watch.current().general().avoidMines());
        nav.setAvoidBases(() -> watch.current().general().baseRadius());
        nav.setAvoidedZones(map -> {
            orbit.config.BotConfig.Zones z = watch.current().zones().get(map);
            return z == null ? null : z.avoided();
        });
        nav.setPreferredZones(map -> {
            orbit.config.BotConfig.Zones z = watch.current().zones().get(map);
            return z == null ? null : z.preferred();
        });
        orbit.bot.run.BotContext ctx = new orbit.bot.run.BotContext(feed::snapshot, watch::current, actions, nav, traveler,
                catalog, random, System::nanoTime, conn::repair, ship, dataDir(feed), stats, ships);
        orbit.bot.run.BotRunner runner = new orbit.bot.run.BotRunner(ctx, feed::loggedOut, feed::setAction);
        if (previous != null) {
            runner.carryOver(previous);
        }
        runner.setShop(shop);
        runner.setLinkLost(feed::linkLost);
        feed.setRunner(runner);
        java.util.concurrent.atomic.AtomicBoolean stopRequested = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean commanded = new java.util.concurrent.atomic.AtomicBoolean();
        java.util.concurrent.atomic.AtomicBoolean connectionEnded = new java.util.concurrent.atomic.AtomicBoolean();
        CountDownLatch done = new CountDownLatch(1);
        long until = untilMillis;
        java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofPlatform().name("bot-loop").daemon().start(() -> {
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            try {
                // not in game yet (logging in, or the server keeps refusing): a stop still ends it. The ship
                // must be this session's: the feed outlives a restart and still knows the last one's ship
                // logged in dead: the kill screen is this session's too, the runner takes the repair
                while (feed.sessionStartNanos() == 0 || feed.snapshot().death() == null
                        && (feed.snapshot().hero().id() == 0 || !feed.snapshot().hero().alive())) {
                    if (connectionOver.getAsBoolean()) {
                        connectionEnded.set(!stopRequested.get());
                        if (!stopRequested.get()) {
                            log.warn("Вход в игру не состоялся: {}", feed.state());
                        }
                        return;
                    }
                    if (stopRequested.get() || Files.exists(stopFile)) {
                        if (Files.exists(stopFile)) {
                            commanded.set(true);
                            try {
                                Files.deleteIfExists(stopFile);
                            } catch (java.io.IOException e) {
                                log.warn("Не удалить {}: {}", stopFile, e.getMessage());
                            }
                        }
                        log.info("Остановлен до входа в игру");
                        return;
                    }
                    Thread.sleep(500);
                }
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
                        runner.stopWhenFree("по команде");
                    }
                    if (stopRequested.get()) {
                        runner.stopWhenFree("по кнопке");
                    } else if (System.currentTimeMillis() > until) {
                        runner.stopWhenFree("время вышло");
                    }
                    if (connectionOver.getAsBoolean() && !feed.loggedOut()) {
                        if (!stopRequested.get()) {
                            connectionEnded.set(true);
                            log.warn("Связь с игрой закончилась без выхода: {}", feed.state());
                        }
                        break; // no connection: nothing to fly or log out with
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
                feed.setRunner(null);
                if (!stopRequested.get()) {
                    conn.close(); // finished by itself (time, deaths, error, command): the session is over
                }
                done.countDown();
            }
        });
        return new ModuleRun(runner, stopRequested, done, commanded, watch, connectionEnded);
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
        feed.setState("Жду клиент: 127.0.0.1:" + o.port + (o.direct != null ? " и " + o.direct : ""));
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
        // «…window….png»: the whole account view (map, cards, log), to check the layout
        java.awt.image.BufferedImage img = out.getFileName().toString().contains("window")
                ? MainWindow.snapshot(List.of(new MainWindow.Tab(feed, () -> { }, () -> "запись", n -> { })), 0, 1600, 1000)
                : MainWindow.renderMinimap(feed, 1000, 624);
        javax.imageio.ImageIO.write(img, "png", out.toFile());
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
