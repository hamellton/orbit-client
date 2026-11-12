package orbit.game;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.MapServers;
import orbit.net.Downstream;
import orbit.net.Frames;
import orbit.net.ProtocolException;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.ProtocolOutdatedException;
import orbit.protocol.gen.ChannelCloseRequest;
import orbit.protocol.gen.KeepAlive;
import orbit.protocol.gen.KillScreenOptionTypeModule;
import orbit.protocol.gen.KillScreenPostCommand;
import orbit.protocol.gen.KillScreenRepairRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.LoginRequest;
import orbit.protocol.gen.LoginResponse;
import orbit.protocol.gen.Packets;
import orbit.protocol.gen.ProtocolVersion;
import orbit.protocol.gen.ReloginCommand;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.UserSettingsCommand;
import orbit.protocol.gen.VersionCommand;
import orbit.protocol.gen.VersionRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One account's connection to the game: policy request, handshake byte, version check, login,
 * startup packets, keep-alive, server changes ({@code ReloginCommand}) and reconnects.
 *
 * <p>{@link #run()} blocks until {@link #close()} or a fatal error; call it on a dedicated thread.
 */
public final class GameConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(GameConnection.class);
    /** Legacy server command {@code CLIENT_SETTING} ("7") with the jump CPU part ("HS"). */
    private static final String CLIENT_SETTING_HS = "0|7|HS";
    /** The server ends the session after a logout ({@code ServerCommands.LOGOUT}). */
    private static final String LOGOUT = "0|l";
    /** Sent by the client right after its {@code ReadyRequest}s ({@code ServerCommands.LOGIN_FLAG}). */
    static final String LOGIN_FLAG = "LF|w";
    private static final byte[] POLICY_REQUEST = "<policy-file-request/>\0".getBytes(StandardCharsets.US_ASCII);

    private final SessionSource source;
    private final GameListener listener;
    private final ConnectionSettings settings;
    private final StartupSequence startup;
    private final Random random = new Random();
    private final AtomicBoolean closed = new AtomicBoolean();

    private volatile Socket socket;
    /** Website session of the current TCP session (for requests that repeat the login, e.g. a repair). */
    private volatile GameSession currentSession;
    private volatile OutputStream out;
    private volatile Thread runner;
    /** Incremented per TCP session; delayed sends of an old session are dropped. */
    private final AtomicInteger sessionSeq = new AtomicInteger();
    /** Whether a connection of this run already reached the game (the client UI would exist). */
    private boolean connectedBefore;
    /** {@code LF|w} sent in the current session: the client UI is up and handles server notices. */
    private volatile boolean loginFlagSent;
    /** A jump CPU notice arrived before {@code LF|w}; answered once the flag is sent. */
    private volatile boolean jumpCpuNoticePending;

    /** Raised inside a session when the server moves us to another map/server. */
    private record Relogin(int mapId, int delayMillis) {
    }

    /** The game closed the connection before the ship was initialised: the session is probably stale. */
    /** The client's texts for {@code LoginResponse.status} ({@code LoginResponseHandler.STATE2TEXT}). */
    static String loginStatus(int status) {
        return switch (status) {
            case LoginResponse.STATUS_Error -> "ошибка сервера (Error)";
            case LoginResponse.STATUS_ShuttingDown -> "сервер выключается (ShuttingDown)";
            case LoginResponse.STATUS_InvalidData -> "неверные данные входа (InvalidData)";
            case LoginResponse.STATUS_WrongInstanceId -> "не тот инстанс (WrongInstanceId)";
            case LoginResponse.STATUS_WrongServer -> "не тот игровой сервер (WrongServer)";
            case LoginResponse.STATUS_InvalidSessionId -> "недействительная сессия (InvalidSessionId)";
            case LoginResponse.STATUS_ShipIsDestroyed -> "корабль уничтожен (ShipIsDestroyed)";
            case LoginResponse.STATUS_PlayerIsLoggedOut -> "игрок вышел (PlayerIsLoggedOut)";
            case LoginResponse.STATUS_IPRestricted -> "лимит входов с IP (IPRestricted)";
            default -> "код " + status;
        };
    }

    /**
     * A sign of trouble from the server (a kick, a login from elsewhere, a typed message): the account stops, no
     * reconnect (plan 5в.7г).
     */
    private static final class Trouble extends IOException {
        private static final long serialVersionUID = 1L;

        Trouble(String message) {
            super(message);
        }
    }

    private volatile String trouble;

    /** Why the server's sign of trouble ended this connection for good, or {@code null}. */
    public String trouble() {
        return trouble;
    }

    /**
     * The client's legacy commands that mean trouble ({@code ServerCommands}, {@code ConnectionManager}): {@code KIK}
     * (kicked), {@code ERR|3} double login and {@code ERR|41} logged in twice (the client disconnects and shows
     * «doubleloggedin»), and {@code A|STD|<text>} — a server line in plain text, not a key of the client's
     * dictionary (never seen in hours of recordings; that is how a person writes). {@code null} when none.
     */
    static String troubleOf(String message) {
        if (message == null) {
            return null;
        }
        String[] a = message.split("\\|", -1);
        if (a.length < 2) {
            return null;
        }
        return switch (a[1]) {
            case "KIK" -> "Сервер выкинул из игры (KIK)";
            case "ERR" -> a.length > 2 && ("3".equals(a[2]) || "41".equals(a[2]))
                    ? "В аккаунт вошли с другого места (ERR " + a[2] + ")" : null;
            case "A" -> a.length > 3 && "STD".equals(a[2])
                    ? "Сервер написал: «" + String.join("|", java.util.Arrays.copyOfRange(a, 3, a.length)) + "»" : null;
            default -> null;
        };
    }

    private static final class LoginRejected extends IOException {
        private static final long serialVersionUID = 1L;

        LoginRejected(String message) {
            super(message);
        }
    }

    public GameConnection(SessionSource source, GameListener listener, ConnectionSettings settings,
                          StartupSequence startup) {
        this.source = source;
        this.listener = listener;
        this.settings = settings;
        this.startup = startup;
    }

    Random random() {
        return random;
    }

    /** Sends one packet; ignored while not connected. */
    /** When the server last showed the world alive: a ship moved, appeared, left, shot; a box appeared. */
    private volatile long worldAliveNanos;
    /** When the bot last asked to fly. */
    private volatile long moveSentNanos;
    /** The last reconnect for a world standing still: not more often than {@link #STALL_GAP_NANOS}. */
    private long stallReconnectNanos = Long.MIN_VALUE / 2;
    /** An empty corner of a map can be quiet for long: at most one such reconnect in this time. */
    static final long STALL_GAP_NANOS = 10L * 60 * 1_000_000_000L;

    static boolean worldEvent(Packet p) {
        return p instanceof orbit.protocol.gen.MoveCommand || p instanceof orbit.protocol.gen.ShipCreateCommand
                || p instanceof orbit.protocol.gen.ShipRemoveCommand || p instanceof orbit.protocol.gen.ShipDestroyedCommand
                || p instanceof orbit.protocol.gen.AttackLaserRunCommand || p instanceof orbit.protocol.gen.AttackHitCommand
                || p instanceof orbit.protocol.gen.AddBoxCommand || p instanceof ShipInitializationCommand;
    }

    /** Asked to fly, and since then nothing in the world for {@code stall}: the server hangs. */
    private void checkStall() throws IOException {
        long now = System.nanoTime();
        long alive = worldAliveNanos;
        if (moveSentNanos > alive && now - alive > settings.stall().toNanos() && now - stallReconnectNanos > STALL_GAP_NANOS) {
            stallReconnectNanos = now;
            throw new IOException("мир не обновляется " + settings.stall().toSeconds() + " с");
        }
    }

    public void send(Packet p) {
        OutputStream o = out;
        if (o == null) {
            return;
        }
        if (p instanceof orbit.protocol.gen.MoveRequest) {
            moveSentNanos = System.nanoTime();
        }
        byte[] bytes = PacketCodec.encode(p);
        log.debug("→ {}", p.getClass().getSimpleName());
        synchronized (this) {
            try {
                Frames.write(o, bytes);
            } catch (IOException e) {
                log.debug("Отправка не удалась: {}", e.getMessage());
                closeSocket();
                return;
            }
        }
        listener.onSent(p);
        if (p instanceof LegacyModule lm && LOGIN_FLAG.equals(lm.message)) {
            loginFlagSent = true;
            if (jumpCpuNoticePending) {
                jumpCpuNoticePending = false;
                answerJumpCpuNotice(sessionSeq.get());
            }
        }
    }

    /** The website session of the current connection, or {@code null} before connecting. */
    public GameSession session() {
        return currentSession;
    }

    /**
     * Chooses a way back from the kill screen, as the client's {@code Killscreen} does: the option
     * and the login data again ({@code userID}, {@code sessionID}, faction; version empty).
     *
     * @param optionType {@code KillScreenOptionTypeModule} value offered in the kill screen
     * @param factionId  the hero's faction (1 MMO, 2 EIC, 3 VRU)
     */
    public void repair(int optionType, int factionId) {
        GameSession session = currentSession;
        if (session == null) {
            return;
        }
        KillScreenRepairRequest r = new KillScreenRepairRequest();
        r.selection = new KillScreenOptionTypeModule();
        r.selection.repairTypeValue = (short) optionType;
        r.requestModule = new LoginRequest();
        r.requestModule.userID = session.userId();
        r.requestModule.sessionID = session.sessionId();
        r.requestModule.factionID = (short) factionId;
        r.requestModule.version = "";
        r.requestModule.instanceId = 0;
        r.requestModule.isMiniClient = false;
        send(r);
    }

    public void sendKeepAlive() {
        KeepAlive k = new KeepAlive();
        k.MouseClick = false;
        send(k);
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            closeSocket();
            Thread r = runner;
            if (r != null) {
                r.interrupt();
            }
        }
    }

    /** Main loop: log in, stay online, follow server changes, reconnect on failures. */
    /**
     * The website is tried again no sooner than this: a Wi-Fi restart or the site's own trouble takes a minute
     * or two (the user, 2026-11-11), knocking every few seconds helps nothing.
     */
    static final Duration SITE_RETRY_MIN = Duration.ofMinutes(1);

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    public void run() {
        logContext = org.slf4j.MDC.getCopyOfContextMap();
        runner = Thread.currentThread();
        GameSession session = null;
        MapServers.Address server = null;
        int failures = 0;
        int maintenance = 0; // logins in a row that met the site's maintenance page
        try {
            while (!closed.get()) {
                if (session == null) {
                    listener.onState(GameListener.State.AUTHENTICATING, "Вход на сайт");
                    try {
                        session = source.fresh();
                    } catch (AuthException e) {
                        if (!e.retryable()) {
                            throw e;
                        }
                        // a maintenance lasts half an hour or more: knock rarely, not every few seconds
                        Duration wait = e.reason() == AuthException.Reason.MAINTENANCE
                                ? ConnectionSettings.maintenanceWait(maintenance++, java.util.concurrent.ThreadLocalRandom.current())
                                : max(SITE_RETRY_MIN, settings.backoff(failures++));
                        String why = e.getMessage().replaceFirst("\\.$", "");
                        log.warn("Вход на сайт не удался: {}. Повтор через {}", why, spoken(wait));
                        // short, for the window: what and when the next try is
                        listener.onState(GameListener.State.RECONNECTING, why + " — повтор в "
                                + java.time.LocalTime.now().plus(wait).withNano(0));
                        sleep(wait);
                        continue;
                    }
                    maintenance = 0;
                    server = new MapServers.Address(session.gameHost(), session.gamePort());
                }
                try {
                    Relogin relogin = serve(session, server);
                    if (relogin == null) {
                        continue; // closed by the user
                    }
                    failures = 0;
                    listener.onState(GameListener.State.CHANGING_SERVER, "Переход на карту " + relogin.mapId());
                    try {
                        server = source.serverForMap(session, relogin.mapId());
                        log.info("Сервер просит перейти на карту {} — подключаюсь к {}", relogin.mapId(), server);
                    } catch (AuthException e) {
                        // a fresh login reports the server of the current map
                        log.warn("Не удалось узнать сервер карты {}: {} Выполню вход заново.", relogin.mapId(), e.getMessage());
                        session = null;
                    }
                    sleep(Duration.ofMillis(Math.max(0, relogin.delayMillis())));
                } catch (Trouble e) {
                    throw e;
                } catch (IOException | ProtocolException e) {
                    if (closed.get()) {
                        break;
                    }
                    Duration wait = settings.backoff(failures);
                    failures++;
                    boolean refresh = e instanceof LoginRejected || failures >= settings.refreshAfter();
                    String why = e instanceof LoginRejected ? e.getMessage() : "Обрыв связи: " + e.getMessage();
                    log.warn("{}. Повтор через {} с{}", why, wait.toSeconds(), refresh ? " с новым входом на сайт" : "");
                    listener.onState(GameListener.State.RECONNECTING, why);
                    if (refresh) {
                        session = null;
                        failures = Math.min(failures, settings.backoff().size() - 1);
                    }
                    sleep(wait);
                }
            }
        } catch (Trouble e) {
            trouble = e.getMessage();
            log.error("Признак беды: {} — аккаунт останавливаю, без переподключения", e.getMessage());
            listener.onState(GameListener.State.STOPPED, e.getMessage());
            return;
        } catch (ProtocolOutdatedException e) {
            log.error("{}: {}", e.getMessage(), e.details());
            listener.onState(GameListener.State.STOPPED, e.getMessage());
            return;
        } catch (AuthException e) {
            log.error("Вход на сайт не удался: {}", e.getMessage());
            listener.onState(GameListener.State.STOPPED, e.getMessage());
            return;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            closeSocket();
        }
        listener.onState(GameListener.State.STOPPED, "Остановлено");
    }

    /**
     * One TCP session. Returns the requested server change, or {@code null} when closed by the user.
     */
    private Relogin serve(GameSession session, MapServers.Address server) throws IOException, InterruptedException {
        listener.onState(GameListener.State.CONNECTING, "Подключение к " + server);
        currentSession = session;
        if (settings.policyRequest()) {
            requestPolicy(server);
        }
        Socket s = new Socket();
        socket = s;
        if (closed.get()) {
            return null;
        }
        s.setTcpNoDelay(true);
        settings.proxy().connect(s, server.host(), server.port(), (int) settings.connectTimeout().toMillis());
        s.setSoTimeout((int) settings.loginTimeout().toMillis());
        InputStream in = new BufferedInputStream(s.getInputStream());
        out = new BufferedOutputStream(s.getOutputStream());

        synchronized (this) {
            out.write(0);
            out.flush();
        }
        VersionRequest vr = new VersionRequest();
        vr.version = ProtocolVersion.VERSION_HASH;
        send(vr);

        loginFlagSent = false;
        jumpCpuNoticePending = false;
        boolean compression = false;
        boolean shipReady = false;
        boolean dead = false;
        UserSettingsCommand userSettings = null;
        int seq = sessionSeq.incrementAndGet();
        Thread keepAlive = null;
        Thread startupThread = null;
        try {
            while (!closed.get()) {
                byte[] payload;
                try {
                    payload = Frames.read(in);
                } catch (java.net.SocketTimeoutException e) {
                    if (shipReady) {
                        throw new java.io.IOException("сервер молчит " + settings.silence().toSeconds() + " с");
                    }
                    throw new LoginRejected("Сервер не принял вход за " + settings.loginTimeout().toSeconds() + " с");
                }
                if (payload == null) {
                    if (!shipReady) {
                        throw new LoginRejected("Сервер закрыл соединение при входе");
                    }
                    throw new EOFException("сервер закрыл соединение");
                }
                if (payload.length == 0) {
                    continue;
                }
                for (byte[] raw : Downstream.split(payload, compression)) {
                    Packet p;
                    try {
                        p = PacketCodec.decode(raw);
                    } catch (ProtocolException e) {
                        short id = Downstream.packetId(raw);
                        log.warn("Пакет {} не разобран: {}", Packets.known(id) ? Packets.name(id) : "?" + id, e.getMessage());
                        continue;
                    }
                    if (!shipReady) {
                        log.debug("← {}", p.getClass().getSimpleName()); // the login exchange, for diagnosis
                    }
                    if (worldEvent(p)) {
                        worldAliveNanos = System.nanoTime();
                    } else if (shipReady && !dead) {
                        checkStall(); // the server talks (keep-alives, messages) but the world stands still
                    }
                    if (p instanceof VersionCommand vc) {
                        ProtocolOutdatedException.check(vc);
                        sendLogin(session);
                        compression = true;
                    } else if (p instanceof KillScreenPostCommand ks) {
                        // logged in dead, or destroyed now: no ship until a repair is chosen
                        dead = true;
                        shipReady = true;
                        s.setSoTimeout(0);
                        listener.onState(GameListener.State.DEAD, "Корабль уничтожен" + (ks.killerName.isBlank() ? "" : ": " + ks.killerName));
                    } else if (p instanceof ShipInitializationCommand && dead) {
                        // repaired on this connection: the ship is back, the client re-reports its UI
                        dead = false;
                        s.setSoTimeout((int) settings.silence().toMillis());
                        listener.onState(GameListener.State.ONLINE, "В игре, корабль восстановлен");
                        if (startupThread != null) {
                            startupThread.interrupt();
                        }
                        loginFlagSent = false;
                        StartupSequence.Context ctx = new StartupSequence.Context(false, userSettings, true);
                        startupThread = Thread.ofVirtual().name("startup").start(withLogContext(() -> runStartup(ctx)));
                    } else if (p instanceof ShipInitializationCommand && startupThread != null) {
                        // portal jump to a map on the same server: the client re-sends its UI state at once
                        startupThread.interrupt();
                        loginFlagSent = false;
                        StartupSequence.Context ctx = new StartupSequence.Context(false, userSettings, true);
                        startupThread = Thread.ofVirtual().name("startup").start(withLogContext(() -> runStartup(ctx)));
                    } else if (p instanceof ShipInitializationCommand) {
                        shipReady = true;
                        s.setSoTimeout((int) settings.silence().toMillis());
                        listener.onState(GameListener.State.ONLINE, "В игре");
                    } else if (p instanceof UserSettingsCommand us) {
                        userSettings = us;
                    } else if (p instanceof LoginResponse lr && lr.status == LoginResponse.STATUS_ShipIsDestroyed) {
                        // logged in dead (live 2026-11-11: killed at a portal while logging out): the client keeps
                        // the connection, shows the kill screen and sends the repair on it (only the repair,
                        // keep-alives and closing are allowed then). A reconnect would meet the same answer.
                        log.info("Корабль уничтожен — жду выбора ремонта на этом соединении");
                        if (keepAlive == null) {
                            keepAlive = Thread.ofVirtual().name("keepalive").start(withLogContext(this::keepAliveLoop));
                        }
                    } else if (p instanceof LoginResponse lr && lr.status != LoginResponse.STATUS_Success) {
                        throw new LoginRejected("Сервер отказал во входе: " + loginStatus(lr.status));
                    } else if (p instanceof LoginResponse && startupThread == null) {
                        StartupSequence.Context ctx = new StartupSequence.Context(!connectedBefore, userSettings);
                        connectedBefore = true;
                        startupThread = Thread.ofVirtual().name("startup").start(withLogContext(() -> runStartup(ctx)));
                        keepAlive = Thread.ofVirtual().name("keepalive").start(withLogContext(this::keepAliveLoop));
                    } else if (p instanceof LegacyModule lm && troubleOf(lm.message) != null) {
                        listener.onPacket(p);
                        throw new Trouble(troubleOf(lm.message));
                    } else if (p instanceof LegacyModule lm && LOGOUT.equals(lm.message)) {
                        // the logout we asked for: the server ends the session, nothing to reconnect
                        listener.onPacket(p);
                        listener.onState(GameListener.State.STOPPED, "Выход из игры");
                        closed.set(true);
                        return null;
                    } else if (p instanceof LegacyModule lm && CLIENT_SETTING_HS.equals(lm.message)) {
                        if (loginFlagSent) {
                            answerJumpCpuNotice(seq);
                        } else {
                            jumpCpuNoticePending = true;
                        }
                    } else if (p instanceof ReloginCommand rc) {
                        listener.onPacket(p);
                        // the client closes its channel a few milliseconds later, before reconnecting
                        Thread.sleep(3 + random.nextInt(8));
                        ChannelCloseRequest close = new ChannelCloseRequest();
                        close.close = true;
                        send(close);
                        return new Relogin(rc.mapID, rc.delayInMillis);
                    }
                    listener.onPacket(p);
                }
            }
            return null;
        } finally {
            if (keepAlive != null) {
                keepAlive.interrupt();
            }
            if (startupThread != null) {
                startupThread.interrupt();
            }
            closeSocket();
        }
    }

    private void sendLogin(GameSession session) {
        LoginRequest lr = new LoginRequest();
        lr.userID = session.userId();
        lr.factionID = 0; // the client does not know its faction yet at this point
        lr.sessionID = session.sessionId();
        lr.version = ProtocolVersion.COMPRESSION;
        lr.instanceId = session.instanceId();
        lr.isMiniClient = session.isMiniClient();
        send(lr);
    }

    /**
     * The client answers the jump CPU notice ({@code 0|7|HS}) with a status query, always after its
     * own {@code LF|w}: 0.15–1.3 s later, sometimes up to 3.6 s (recordings of 2026-11-09).
     */
    private void answerJumpCpuNotice(int seq) {
        boolean slow = random.nextDouble() < 0.2;
        sendLater(seq, legacy("JCPU|GET"), slow ? 1_300 : 150, slow ? 3_600 : 1_300);
    }

    /** Sends {@code p} after a random pause unless the session has changed meanwhile. */
    private void sendLater(int seq, Packet p, int minMs, int maxMs) {
        Thread.ofVirtual().name("delayed-send").start(withLogContext(() -> {
            try {
                Thread.sleep(minMs + random.nextInt(maxMs - minMs + 1));
                if (sessionSeq.get() == seq) {
                    send(p);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
    }

    /** The log context (the account) of the thread that started the connection. */
    private volatile java.util.Map<String, String> logContext;

    /** {@code r} running with this connection's log context, so its lines go to the account's log. */
    private Runnable withLogContext(Runnable r) {
        java.util.Map<String, String> ctx = logContext;
        return () -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            r.run();
        };
    }

    private static LegacyModule legacy(String message) {
        LegacyModule m = new LegacyModule();
        m.message = message;
        return m;
    }

    private void runStartup(StartupSequence.Context ctx) {
        try {
            startup.afterLogin(this, ctx);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            log.warn("Стартовая последовательность прервана: {}", e.toString());
        }
    }

    private void keepAliveLoop() {
        try {
            while (!Thread.currentThread().isInterrupted() && out != null) {
                long jitter = settings.keepAliveJitter().toMillis();
                Thread.sleep(settings.keepAlive().toMillis() + (jitter > 0 ? random.nextLong(jitter) : 0));
                sendKeepAlive();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Flash asks for a socket policy on a separate connection before connecting; do the same. */
    private void requestPolicy(MapServers.Address server) {
        try (Socket p = new Socket()) {
            settings.proxy().connect(p, server.host(), server.port(), (int) settings.connectTimeout().toMillis());
            p.setSoTimeout(5_000);
            p.getOutputStream().write(POLICY_REQUEST);
            p.getOutputStream().flush();
            InputStream in = p.getInputStream();
            int b;
            int n = 0;
            while ((b = in.read()) > 0 && n++ < 16_384) {
                // read the policy XML until the terminating zero byte or EOF
            }
        } catch (IOException e) {
            log.debug("Запрос policy не удался: {}", e.getMessage());
        }
    }

    private void closeSocket() {
        out = null;
        Socket s = socket;
        socket = null;
        if (s != null) {
            try {
                s.close();
            } catch (IOException ignored) {
                // already closed
            }
        }
    }

    /** {@code 15 с}, {@code 4 мин 12 с}. */
    static String spoken(Duration d) {
        long s = Math.round(d.toMillis() / 1000.0);
        return s < 60 ? s + " с" : s / 60 + " мин" + (s % 60 == 0 ? "" : " " + s % 60 + " с");
    }

    private void sleep(Duration d) throws InterruptedException {
        if (!d.isZero() && !closed.get()) {
            Thread.sleep(d.toMillis());
        }
    }
}
