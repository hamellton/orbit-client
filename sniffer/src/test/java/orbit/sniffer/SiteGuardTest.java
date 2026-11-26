package orbit.sniffer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.cert.X509Certificate;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SiteGuardTest {

    @Test
    void theLogoutIsTheOnlyRequestStopped() {
        assertTrue(SiteGuard.blocked("GET /indexInternal.es?action=externalLogout HTTP/1.1\r\nHost: int1.darkorbit.com\r\n"));
        assertFalse(SiteGuard.blocked("GET /indexInternal.es?action=internalDock HTTP/1.1\r\nHost: int1.darkorbit.com\r\n"));
        assertFalse(SiteGuard.blocked("GET /x HTTP/1.1\r\nReferer: https://int1.darkorbit.com/?action=externalLogout\r\n"),
                "only the request line counts");
    }

    @Test
    void eachRequestAsksTheSiteToCloseAfterIt() throws Exception {
        String head = "GET / HTTP/1.1\r\nHost: a\r\nConnection: keep-alive\r\nKeep-Alive: 300\r\nAccept: */*\r\n\r\n";
        assertEquals("GET / HTTP/1.1\r\nHost: a\r\nAccept: */*\r\nConnection: close\r\n\r\n", SiteGuard.closing(head));
        byte[] read = SiteGuard.readHead(new ByteArrayInputStream((head + "body").getBytes(StandardCharsets.ISO_8859_1)));
        assertEquals(head, new String(read, StandardCharsets.ISO_8859_1), "up to the blank line, the body left");
    }

    @Test
    void aLogoutOverTlsComesBackAsTheStartPage(@TempDir Path dir) throws Exception {
        SiteGuard guard = new SiteGuard(dir.resolve("guard.p12"));
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            Thread server = Thread.ofVirtual().start(() -> {
                try (Socket s = ss.accept()) {
                    guard.handle(s, null, "int1.darkorbit.com", 443);
                } catch (Exception ignored) {
                    // the test reads what came back
                }
            });
            SSLContext trustAll = SSLContext.getInstance("TLS");
            trustAll.init(null, new TrustManager[] {new X509TrustManager() {
                @Override
                public void checkClientTrusted(X509Certificate[] c, String a) {
                }

                @Override
                public void checkServerTrusted(X509Certificate[] c, String a) {
                }

                @Override
                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, null);
            try (SSLSocket c = (SSLSocket) trustAll.getSocketFactory().createSocket(InetAddress.getLoopbackAddress(),
                    ss.getLocalPort())) {
                c.getOutputStream().write(("GET /indexInternal.es?action=externalLogout HTTP/1.1\r\nHost: int1.darkorbit.com"
                        + "\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
                c.getOutputStream().flush();
                String answer = new String(c.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
                assertTrue(answer.startsWith("HTTP/1.1 302"), answer);
                assertTrue(answer.contains("Location: /indexInternal.es?action=internalStart"), answer);
            }
            server.join(5_000);
        }
    }
}
