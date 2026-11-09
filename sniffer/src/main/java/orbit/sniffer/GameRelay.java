package orbit.sniffer;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import orbit.net.Downstream;
import orbit.net.Frames;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.ProtocolOutdatedException;
import orbit.protocol.gen.Packets;
import orbit.protocol.gen.VersionCommand;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Relays one game connection, forwarding bytes unchanged while decoding frames
 * in both directions for logging and recording.
 */
final class GameRelay {

    private static final Logger log = LoggerFactory.getLogger(GameRelay.class);
    private static final AtomicLong IDS = new AtomicLong();
    private static final int MAX_LOG_CHARS = 400;

    private final Socket client;
    private final Socket server;
    private final String target;
    private final Path recordDir;
    private final long id = IDS.incrementAndGet();

    private volatile boolean compressionOffered;
    /** sessionID of this connection, masked wherever the client repeats it. */
    private volatile byte[] sessionSecret;
    private final Stats stats = new Stats();

    GameRelay(Socket client, Socket server, String target, Path recordDir) {
        this.client = client;
        this.server = server;
        this.target = target;
        this.recordDir = recordDir;
    }

    void run() throws IOException {
        InputStream clientIn = new BufferedInputStream(client.getInputStream());
        int hello = clientIn.read();
        if (hello < 0) {
            return;
        }
        if (hello == '<') {
            // Flash socket policy request on the game port: tunnel it verbatim
            log.debug("[#{}] Запрос socket policy к {}", id, target);
            server.getOutputStream().write(hello);
            Thread t = Thread.ofVirtual().start(() -> {
                try {
                    clientIn.transferTo(server.getOutputStream());
                } catch (IOException ignored) {
                    // closed
                }
            });
            server.getInputStream().transferTo(client.getOutputStream());
            closeQuietly(client);
            closeQuietly(server);
            try {
                t.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return;
        }

        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
        Path file = recordDir.resolve("session-" + stamp + "-" + id + ".dorec");
        log.info("[#{}] Игровое соединение с {} — запись в {}", id, target, file);
        try (SessionRecorder rec = new SessionRecorder(file)) {
            server.getOutputStream().write(hello);
            server.getOutputStream().flush();
            rec.record(SessionRecorder.UP, new byte[] {(byte) hello});
            if (hello != 0) {
                log.warn("[#{}] Неожиданный байт приветствия 0x{}", id, Integer.toHexString(hello));
            }
            Thread up = Thread.ofVirtual().name("up-" + id).start(() -> pump(true, clientIn, rec));
            pump(false, new BufferedInputStream(server.getInputStream()), rec);
            try {
                up.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        } finally {
            log.info("[#{}] Соединение закрыто. {}", id, stats.summary());
        }
    }

    private void pump(boolean upstream, InputStream in, SessionRecorder rec) {
        String arrow = upstream ? "→" : "←";
        try {
            OutputStream out = new BufferedOutputStream((upstream ? server : client).getOutputStream());
            byte dir = upstream ? SessionRecorder.UP : SessionRecorder.DOWN;

            byte[] payload;
            while ((payload = Frames.read(in)) != null) {
                // record and inspect before forwarding: keeps the recording in causal order and
                // makes sure the compression flag is set before the server can answer the login
                handle(upstream, arrow, payload, rec, dir);
                Frames.write(out, payload);
            }
        } catch (IOException e) {
            log.debug("[#{}] {} поток завершён: {}", id, arrow, e.toString());
        } catch (RuntimeException e) {
            log.error("[#{}] {} ошибка разбора, соединение закрывается", id, arrow, e);
        } finally {
            closeQuietly(client);
            closeQuietly(server);
            try {
                rec.flush();
            } catch (IOException ignored) {
                // recorder is closed by the owner
            }
        }
    }

    private void handle(boolean upstream, String arrow, byte[] payload, SessionRecorder rec, byte dir) throws IOException {
        if (payload.length == 0) {
            rec.record(dir, payload);
            return;
        }
        if (upstream) {
            String version = Secrets.loginVersion(payload);
            if (version != null) {
                compressionOffered = Downstream.COMPRESSION_CAPABILITY.equals(version)
                        || version.startsWith("do-lz4-");
                log.info("[#{}] LoginRequest: версия «{}», сжатие {}", id, version,
                        compressionOffered ? "включено" : "выключено");
            }
            byte[] sid = Secrets.sessionId(payload);
            if (sid != null) {
                sessionSecret = sid;
            }
            byte[] masked = Secrets.maskAll(Secrets.maskSession(payload), sessionSecret);
            rec.record(dir, masked);
            logPacket(arrow, masked, false);
            return;
        }
        rec.record(dir, payload);
        boolean envelope = Downstream.isEnvelope(payload);
        List<byte[]> packets = Downstream.split(payload, compressionOffered);
        if (envelope) {
            stats.envelopes++;
        }
        for (byte[] p : packets) {
            logPacket(arrow, p, envelope);
        }
    }

    private void logPacket(String arrow, byte[] packet, boolean fromEnvelope) {
        short pid = Downstream.packetId(packet);
        boolean known = Packets.known(pid);
        stats.count(known);
        if (!log.isInfoEnabled()) {
            return;
        }
        String text;
        try {
            Packet decoded = PacketCodec.decode(packet);
            if (decoded instanceof VersionCommand vc) {
                try {
                    ProtocolOutdatedException.check(vc);
                } catch (ProtocolOutdatedException e) {
                    log.error("[#{}] {}", id, e.getMessage());
                }
            }
            text = abbreviate(decoded.toString());
        } catch (RuntimeException e) {
            stats.decodeErrors++;
            log.warn("[#{}] {} {}({}): поля не разобраны — {}", id, arrow, nameOf(pid), pid, e.getMessage());
            text = nameOf(pid);
        }
        log.info("[#{}] {} {} ({} байт{})", id, arrow, text, packet.length, fromEnvelope ? ", lz4" : "");
    }

    private static String nameOf(short pid) {
        String n = Packets.name(pid);
        return n != null ? n : "?" + pid;
    }

    private static String abbreviate(String s) {
        return s.length() <= MAX_LOG_CHARS ? s : s.substring(0, MAX_LOG_CHARS) + "…";
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
            // already closed
        }
    }

    private static final class Stats {
        long known;
        long unknown;
        long envelopes;
        long decodeErrors;

        synchronized void count(boolean isKnown) {
            if (isKnown) {
                known++;
            } else {
                unknown++;
            }
        }

        synchronized String summary() {
            long total = known + unknown;
            double pct = total == 0 ? 0 : 100.0 * unknown / total;
            return String.format("Пакетов: %d, неизвестных: %d (%.2f%%), ошибок разбора полей: %d, сжатых блоков: %d",
                    total, unknown, pct, decodeErrors, envelopes);
        }
    }
}
