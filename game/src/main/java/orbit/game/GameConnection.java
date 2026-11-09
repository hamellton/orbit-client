package orbit.game;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
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
    public void send(Packet p) {
        OutputStream o = out;
        if (o == null) {
            return;
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
    public void run() {
        runner = Thread.currentThread();
        GameSession session = null;
        MapServers.Address server = null;
        int failures = 0;
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
                        Duration wait = settings.backoff(failures++);
                        log.warn("Вход на сайт не удался: {} Повтор через {} с", e.getMessage(), wait.toSeconds());
                        listener.onState(GameListener.State.RECONNECTING, e.getMessage());
                        sleep(wait);
                        continue;
                    }
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
                } catch (IOException | ProtocolException e) {
                    if (closed.get()) {
                        break;
                    }
                    Duration wait = settings.backoff(failures);
                    failures++;
                    boolean refresh = e instanceof LoginRejected || failures >= settings.refreshAfter();
                    String why = e instanceof LoginRejected ? e.getMessage() : "Соединение потеряно: " + e.getMessage();
                    log.warn("{}. Повтор через {} с{}", why, wait.toSeconds(), refresh ? " с новым входом на сайт" : "");
                    listener.onState(GameListener.State.RECONNECTING, why);
                    if (refresh) {
                        session = null;
                        failures = Math.min(failures, settings.backoff().size() - 1);
                    }
                    sleep(wait);
                }
            }
        } catch (ProtocolOutdatedException e) {
            log.error(e.getMessage());
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
        if (settings.policyRequest()) {
            requestPolicy(server);
        }
        Socket s = new Socket(settings.proxy());
        socket = s;
        if (closed.get()) {
            return null;
        }
        s.setTcpNoDelay(true);
        s.connect(new InetSocketAddress(server.host(), server.port()), (int) settings.connectTimeout().toMillis());
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
                    throw new LoginRejected("Сервер не принял вход за " + settings.loginTimeout().toSeconds() + " с");
                }
                if (payload == null) {
                    if (!shipReady) {
                        throw new LoginRejected("Игровой сервер закрыл соединение во время входа");
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
                    if (p instanceof VersionCommand vc) {
                        ProtocolOutdatedException.check(vc);
                        sendLogin(session);
                        compression = true;
                    } else if (p instanceof ShipInitializationCommand && startupThread != null) {
                        // portal jump to a map on the same server: the client re-sends its UI state at once
                        startupThread.interrupt();
                        loginFlagSent = false;
                        StartupSequence.Context ctx = new StartupSequence.Context(false, userSettings, true);
                        startupThread = Thread.ofVirtual().name("startup").start(() -> runStartup(ctx));
                    } else if (p instanceof ShipInitializationCommand) {
                        shipReady = true;
                        s.setSoTimeout(0);
                        listener.onState(GameListener.State.ONLINE, "В игре, сервер " + server);
                    } else if (p instanceof UserSettingsCommand us) {
                        userSettings = us;
                    } else if (p instanceof LoginResponse && startupThread == null) {
                        StartupSequence.Context ctx = new StartupSequence.Context(!connectedBefore, userSettings);
                        connectedBefore = true;
                        startupThread = Thread.ofVirtual().name("startup").start(() -> runStartup(ctx));
                        keepAlive = Thread.ofVirtual().name("keepalive").start(this::keepAliveLoop);
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
        Thread.ofVirtual().name("delayed-send").start(() -> {
            try {
                Thread.sleep(minMs + random.nextInt(maxMs - minMs + 1));
                if (sessionSeq.get() == seq) {
                    send(p);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
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
        try (Socket p = new Socket(settings.proxy())) {
            p.connect(new InetSocketAddress(server.host(), server.port()), (int) settings.connectTimeout().toMillis());
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

    private void sleep(Duration d) throws InterruptedException {
        if (!d.isZero() && !closed.get()) {
            Thread.sleep(d.toMillis());
        }
    }
}
