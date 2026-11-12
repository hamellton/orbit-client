package orbit.auth.proxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;
import orbit.auth.AuthException;
import orbit.auth.HttpSession;
import orbit.secrets.ProxySpec;
import org.junit.jupiter.api.Test;

class ProxyConnectorTest {

    private static final String USER = "tester";
    private static final String PASS = "Qx7:w@p%";

    private static void assertEchoes(Socket s) throws IOException {
        s.setSoTimeout(3_000);
        OutputStream out = s.getOutputStream();
        out.write("ping".getBytes(StandardCharsets.US_ASCII));
        out.flush();
        assertEquals("ping", new String(s.getInputStream().readNBytes(4), StandardCharsets.US_ASCII));
    }

    @Test
    void socks5PassesTheHostNameAndLogsIn() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.SOCKS5, USER, PASS, echo.address())) {
            ProxyConnector c = new ProxyConnector(new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, PASS));
            try (Socket s = c.open("game.example", 6001, 3_000)) {
                assertEchoes(s);
            }
            try (Socket s = c.open("170.33.12.50", 6002, 3_000)) {
                assertEchoes(s);
            }
            assertEquals(new FakeProxy.Request(3, "game.example", 6001), proxy.requests.get(0));
            assertEquals(new FakeProxy.Request(1, "170.33.12.50", 6002), proxy.requests.get(1));
        }
    }

    @Test
    void socks5WithoutLogin() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.SOCKS5, null, null, echo.address());
             Socket s = new ProxyConnector(ProxySpec.parse("socks5://127.0.0.1:" + proxy.port())).open("h.example", 1, 3_000)) {
            assertEchoes(s);
        }
    }

    @Test
    void socks5Errors() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.SOCKS5, USER, PASS, echo.address())) {
            ProxyException wrong = assertThrows(ProxyException.class, () -> new ProxyConnector(
                    new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, "bad")).open("h", 1, 3_000));
            assertEquals(ProxyException.Kind.AUTH, wrong.kind());
            assertTrue(wrong.getMessage().contains("отклонил логин"), wrong.getMessage());
            assertFalse(wrong.getMessage().contains("bad"));

            ProxyException none = assertThrows(ProxyException.class, () -> new ProxyConnector(
                    new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), null, null)).open("h", 1, 3_000));
            assertEquals(ProxyException.Kind.AUTH, none.kind());
            assertTrue(none.getMessage().contains("требует логин"), none.getMessage());

            ProxyException refused = assertThrows(ProxyException.class, () -> new ProxyConnector(
                    new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, PASS)).open("refused.example", 1, 3_000));
            assertEquals(ProxyException.Kind.TARGET, refused.kind());
            assertTrue(refused.getMessage().contains("соединение отклонено"), refused.getMessage());
        }
    }

    @Test
    void httpConnectWithBasicLogin() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.HTTP, USER, PASS, echo.address())) {
            ProxyConnector c = new ProxyConnector(new ProxySpec(ProxySpec.Type.HTTP, "127.0.0.1", proxy.port(), USER, PASS));
            try (Socket s = c.open("www.darkorbit.com", 443, 3_000)) {
                assertEchoes(s);
            }
            assertEquals(new FakeProxy.Request(0, "www.darkorbit.com", 443), proxy.requests.get(0));

            ProxyException wrong = assertThrows(ProxyException.class, () -> new ProxyConnector(
                    new ProxySpec(ProxySpec.Type.HTTP, "127.0.0.1", proxy.port(), USER, "bad")).open("h", 1, 3_000));
            assertEquals(ProxyException.Kind.AUTH, wrong.kind());
            ProxyException refused = assertThrows(ProxyException.class, () -> c.open("refused.example", 1, 3_000));
            assertEquals(ProxyException.Kind.TARGET, refused.kind());
            assertTrue(refused.getMessage().contains("502"), refused.getMessage());
        }
    }

    @Test
    void autoFindsTheType() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy socks = new FakeProxy(FakeProxy.Kind.SOCKS5, USER, PASS, echo.address());
             FakeProxy http = new FakeProxy(FakeProxy.Kind.HTTP, USER, PASS, echo.address())) {
            ProxyConnector a = new ProxyConnector(ProxySpec.parse("127.0.0.1:" + socks.port() + ":" + USER + ":" + PASS));
            try (Socket s = a.open("h.example", 1, 3_000)) {
                assertEchoes(s);
            }
            assertEquals(ProxySpec.Type.SOCKS5, a.spec().type());

            ProxyConnector b = new ProxyConnector(ProxySpec.parse("127.0.0.1:" + http.port() + ":" + USER + ":" + PASS));
            try (Socket s = b.open("h.example", 1, 3_000)) {
                assertEchoes(s);
            }
            assertEquals(ProxySpec.Type.HTTP, b.spec().type());
        }
    }

    @Test
    void wrongTypeIsNamed() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy http = new FakeProxy(FakeProxy.Kind.HTTP, null, null, echo.address())) {
            ProxyException e = assertThrows(ProxyException.class, () -> new ProxyConnector(
                    ProxySpec.parse("socks5://127.0.0.1:" + http.port())).open("h", 1, 3_000));
            assertEquals(ProxyException.Kind.PROTOCOL, e.kind());
            assertTrue(e.getMessage().contains("http://"), e.getMessage());
        }
    }

    @Test
    void unreachableProxy() throws Exception {
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        ProxyException e = assertThrows(ProxyException.class,
                () -> new ProxyConnector(ProxySpec.parse("socks5://127.0.0.1:" + port)).open("h", 1, 2_000));
        assertEquals(ProxyException.Kind.UNREACHABLE, e.kind());
        assertTrue(e.getMessage().contains("недоступен"), e.getMessage());
    }

    @Test
    void websiteRequestsGoThroughTheRelayWithTheHostName() throws Exception {
        HttpServer site = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        AtomicReference<String> seen = new AtomicReference<>();
        site.createContext("/", x -> {
            seen.set(x.getRequestURI().toString() + " | " + x.getRequestHeaders().getFirst("Host")
                    + " | " + x.getRequestHeaders().containsKey("Proxy-Connection"));
            byte[] body = "hello".getBytes(StandardCharsets.UTF_8);
            x.sendResponseHeaders(200, body.length);
            x.getResponseBody().write(body);
            x.close();
        });
        site.start();
        try (FakeProxy proxy = new FakeProxy(FakeProxy.Kind.SOCKS5, USER, PASS, site.getAddress());
             ProxyRoute route = ProxyRoute.of(new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, PASS))) {
            HttpSession http = new HttpSession("test", route.httpProxy());
            HttpSession.Response r = http.get("http://site.example:8080/a/b?x=1");
            assertEquals(200, r.status());
            assertEquals("hello", r.body());
            assertEquals("/a/b?x=1 | site.example:8080 | false", seen.get());
            // the name reached the proxy: nothing was resolved locally
            assertEquals(new FakeProxy.Request(3, "site.example", 8080), proxy.requests.getFirst());
        } finally {
            site.stop(0);
        }
    }

    @Test
    void relayTunnelsConnect() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.HTTP, USER, PASS, echo.address());
             ProxyRoute route = ProxyRoute.of(new ProxySpec(ProxySpec.Type.HTTP, "127.0.0.1", proxy.port(), USER, PASS));
             Socket s = new Socket()) {
            s.connect(route.httpProxy().address(), 2_000);
            s.setSoTimeout(3_000);
            s.getOutputStream().write("CONNECT www.darkorbit.com:443 HTTP/1.1\r\nHost: www.darkorbit.com:443\r\n\r\n"
                    .getBytes(StandardCharsets.ISO_8859_1));
            InputStream in = s.getInputStream();
            assertTrue(ProxyConnector.readHead(in).startsWith("HTTP/1.1 200"));
            assertEchoes(s);
            assertEquals(new FakeProxy.Request(0, "www.darkorbit.com", 443), proxy.requests.getFirst());
        }
    }

    @Test
    void relayAnswers502WhenTheProxyFails() throws Exception {
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.SOCKS5, USER, PASS, echo.address());
             ProxyRoute route = ProxyRoute.of(new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, "bad"))) {
            HttpSession http = new HttpSession("test", route.httpProxy());
            assertThrows(IOException.class, () -> http.get("https://www.darkorbit.com/"));
        }
    }

    @Test
    void verifyBeforeLogin() throws Exception {
        ProxyRoute.none().verify(); // direct: nothing to check
        try (FakeProxy.Echo echo = new FakeProxy.Echo();
             FakeProxy proxy = new FakeProxy(FakeProxy.Kind.SOCKS5, USER, PASS, echo.address())) {
            try (ProxyRoute fake = ProxyRoute.of(new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, PASS))) {
                // the tunnel opens, but no TLS server answers behind it: like a fake free proxy
                ProxyException e = assertThrows(ProxyException.class, fake::check);
                assertEquals(ProxyException.Kind.TARGET, e.kind());
                assertTrue(e.getMessage().contains("поддельный"), e.getMessage());
                assertEquals(AuthException.Reason.UNAVAILABLE, assertThrows(AuthException.class, fake::verify).reason());
            }
            try (ProxyRoute ok = ProxyRoute.of(new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, PASS))
                    .probe(ProxyConnectorTest::assertEchoes)) {
                ProxyRoute.Check c = ok.check();
                assertNull(c.ip()); // the echo server is no IP service
                assertEquals(new FakeProxy.Request(3, ProxyRoute.CHECK_HOST, 443), proxy.requests.getFirst());
                ok.verify();
            }
            try (ProxyRoute bad = ProxyRoute.of(new ProxySpec(ProxySpec.Type.SOCKS5, "127.0.0.1", proxy.port(), USER, "bad"))) {
                AuthException e = assertThrows(AuthException.class, bad::verify);
                assertEquals(AuthException.Reason.PROXY_REJECTED, e.reason());
                assertFalse(e.retryable());
            }
        }
        int port;
        try (ServerSocket free = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = free.getLocalPort();
        }
        try (ProxyRoute down = ProxyRoute.of(ProxySpec.parse("socks5://127.0.0.1:" + port))) {
            AuthException e = assertThrows(AuthException.class, down::verify);
            assertEquals(AuthException.Reason.UNAVAILABLE, e.reason());
            assertTrue(e.retryable());
        }
    }

    @Test
    void theIpServiceAnswerGivesTheAddressAndTheCountry() {
        String[] g = ProxyRoute.parseGeo("{\"ip\":\"5.6.7.8\",\"city\":\"Berlin\",\"country\":\"DE\"}");
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[] {"5.6.7.8", "DE"}, g);
        org.junit.jupiter.api.Assertions.assertNull(ProxyRoute.parseGeo("<html>blocked</html>"));
        org.junit.jupiter.api.Assertions.assertArrayEquals(new String[] {"5.6.7.8", null},
                ProxyRoute.parseGeo("{\"ip\":\"5.6.7.8\"}"));
    }
}
