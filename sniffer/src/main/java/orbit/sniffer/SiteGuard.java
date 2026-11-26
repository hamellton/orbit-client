package orbit.sniffer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Keeps the site window from logging the account out (docs/PLAN.md «Сайт аккаунта»): the official client opens HTTPS to
 * {@code *.darkorbit.com}, the relay answers it with its own certificate (the client always runs with
 * {@code ignore-certificate-errors}, {@code core.js}), reads each request line and turns {@code action=externalLogout} into a
 * redirect to the start page; every other request goes on to the real site over TLS (its certificate checked as usual), one
 * request per connection ({@code Connection: close}), so only request lines need reading.
 */
public final class SiteGuard {

    private static final Logger LOG = LoggerFactory.getLogger(SiteGuard.class);
    static final Pattern BLOCKED = Pattern.compile("(?i)[?&]action=externalLogout\\b");
    /** A throwaway certificate for loopback only; its password is no secret. */
    private static final char[] STORE_PASSWORD = "orbit-local".toCharArray();

    private final SSLContext server;

    public SiteGuard(Path keystore) throws IOException {
        try {
            if (!Files.exists(keystore)) {
                create(keystore);
            }
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (InputStream in = Files.newInputStream(keystore)) {
                ks.load(in, STORE_PASSWORD);
            }
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(ks, STORE_PASSWORD);
            server = SSLContext.getInstance("TLS");
            server.init(kmf.getKeyManagers(), null, null);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("сертификат для окна сайта не готов: " + e.getMessage(), e);
        }
    }

    /** A self-signed key for the guard, made once by the JDK's keytool. */
    private static void create(Path keystore) throws IOException, InterruptedException {
        Files.createDirectories(keystore.toAbsolutePath().getParent());
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        Process p = new ProcessBuilder(keytool.toString(), "-genkeypair", "-alias", "site", "-keyalg", "RSA", "-keysize",
                "2048", "-validity", "3650", "-dname", "CN=orbit-local", "-keystore", keystore.toString(), "-storetype",
                "PKCS12", "-storepass", new String(STORE_PASSWORD), "-keypass", new String(STORE_PASSWORD))
                .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        if (p.waitFor() != 0 || !Files.exists(keystore)) {
            throw new IOException("keytool не создал сертификат");
        }
    }

    /** The site's pages (HTTPS of darkorbit.com), not the CDN. */
    boolean wants(String host, int port) {
        String h = host.toLowerCase(Locale.ROOT);
        return port == 443 && (h.equals("darkorbit.com") || h.endsWith(".darkorbit.com"));
    }

    /** One connection: TLS with the client, its request read, blocked or passed on to {@code upstream} over TLS. */
    void handle(Socket client, Socket upstream, String host, int port) throws IOException {
        SSLSocket in = (SSLSocket) server.getSocketFactory().createSocket(client, null, true);
        in.setUseClientMode(false);
        in.startHandshake();
        InputStream fromClient = in.getInputStream();
        OutputStream toClient = in.getOutputStream();
        byte[] head = readHead(fromClient);
        if (head == null) {
            in.close();
            return;
        }
        String text = new String(head, StandardCharsets.ISO_8859_1);
        if (blocked(text)) {
            LOG.info("Окно сайта: «Выйти» не пропущено — сессия бота остаётся");
            toClient.write(("HTTP/1.1 302 Found\r\nLocation: /indexInternal.es?action=internalStart\r\nContent-Length: 0\r\n"
                    + "Connection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            toClient.flush();
            in.close();
            return;
        }
        SSLSocket out = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(upstream, host, port, true);
        out.startHandshake();
        OutputStream toServer = out.getOutputStream();
        toServer.write(closing(text).getBytes(StandardCharsets.ISO_8859_1));
        toServer.flush();
        Socks5Server.pipe(in, out);
        in.close();
        out.close();
    }

    /** The request (its head) is the site's logout. */
    static boolean blocked(String head) {
        int end = head.indexOf("\r\n");
        return BLOCKED.matcher(end < 0 ? head : head.substring(0, end)).find();
    }

    /** The request head up to the blank line, {@code null} if the client closed first. */
    static byte[] readHead(InputStream in) throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int matched = 0;
        int c;
        while ((c = in.read()) >= 0) {
            b.write(c);
            if (c == '\r') {
                matched = matched == 2 ? 3 : 1;
            } else if (c == '\n' && (matched == 1 || matched == 3)) {
                matched++;
            } else {
                matched = 0;
            }
            if (matched == 4) {
                return b.toByteArray();
            }
            if (b.size() > 64 * 1024) {
                throw new IOException("слишком длинный заголовок запроса");
            }
        }
        return null;
    }

    /** The head with {@code Connection: close} (one request per connection: the next request line is always read). */
    static String closing(String head) {
        StringBuilder sb = new StringBuilder();
        String[] lines = head.split("\r\n", -1);
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.isEmpty()) {
                continue;
            }
            String low = l.toLowerCase(Locale.ROOT);
            if (i > 0 && (low.startsWith("connection:") || low.startsWith("keep-alive:") || low.startsWith("proxy-connection:"))) {
                continue;
            }
            sb.append(l).append("\r\n");
        }
        return sb.append("Connection: close\r\n\r\n").toString();
    }
}
