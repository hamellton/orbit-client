package orbit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Runs the login flow against a local imitation of the website. */
class AuthClientTest {

    private static final String DOSID = "d0s1d-secret";
    private static final String SESSION = "s3ssion-secret";

    private HttpServer server;
    private String origin;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        origin = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/updates.xml", ex -> send(ex, 200, "<Updates><Version>1.6.9</Version></Updates>", false));
        server.createContext("/", this::landing);
        server.createContext("/sas/auth", this::sasAuth);
        server.createContext("/ProjectApi/Authentication", ex -> redirect(ex, "/indexInternal.es?action=internalStart"));
        server.createContext("/indexInternal.es", this::internal);
        server.createContext("/spacemap/xml/maps.php", ex -> send(ex, 200, """
                <maps>
                  <map id="1"><gameserverIP>gs-1.example:6003</gameserverIP></map>
                  <map id="9"> <gameserverIP>gs-9.example:6005</gameserverIP> </map>
                  <map id="42"><gameserverIP>old.example</gameserverIP></map>
                </maps>""", true));
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AuthClient client() {
        AuthClient.Endpoints ep = new AuthClient.Endpoints(origin + "/", origin + "/updates.xml",
                Pattern.compile("^127\\.0\\.0\\.1$"), origin);
        return new AuthClient(new HttpSession("test", Proxy.NO_PROXY, null), ep);
    }

    @Test
    void passwordLoginReturnsGameSession() throws Exception {
        GameSession s = client().loginWithPassword("pilot", "right");
        assertEquals(166225758, s.userId());
        assertEquals(SESSION, s.sessionId());
        assertEquals(DOSID, s.dosid());
        assertEquals(22, s.instanceId());
        assertEquals(9, s.mapId());
        assertEquals("gs-9.example", s.gameHost());
        assertEquals(6005, s.gamePort());
        assertTrue(s.isMiniClient());
        assertFalse(s.toString().contains(SESSION), "toString must hide secrets");
        assertFalse(s.toString().contains(DOSID), "toString must hide secrets");
    }

    @Test
    void serverForMapUsesMapsTable() throws Exception {
        AuthClient c = client();
        GameSession s = c.loginWithDosid("int1", DOSID);
        assertEquals(new MapServers.Address("gs-1.example", 6003), c.serverForMap(s, 1));
        assertThrows(AuthException.class, () -> c.serverForMap(s, 777));
    }

    @Test
    void wrongPasswordIsReported() {
        AuthException e = assertThrows(AuthException.class, () -> client().loginWithPassword("pilot", "wrong"));
        assertEquals(AuthException.Reason.INVALID_CREDENTIALS, e.reason());
        assertFalse(e.retryable());
    }

    @Test
    void captchaIsReportedWithDosidHint() {
        AuthException e = assertThrows(AuthException.class, () -> client().loginWithPassword("captcha", "right"));
        assertEquals(AuthException.Reason.CAPTCHA_REQUIRED, e.reason());
        assertTrue(e.getMessage().contains("dosid"), e.getMessage());
    }

    @Test
    void dosidLoginWorks() throws Exception {
        GameSession s = client().loginWithDosid("int1", DOSID);
        assertEquals(SESSION, s.sessionId());
        assertEquals(6005, s.gamePort());
    }

    @Test
    void expiredDosidIsReported() {
        AuthException e = assertThrows(AuthException.class, () -> client().loginWithDosid("int1", "stale"));
        assertEquals(AuthException.Reason.SESSION_EXPIRED, e.reason());
    }

    @Test
    void mapServersParseDefaultPortAndWhitespace() {
        MapServers m = MapServers.parse("<maps><map id=\"42\"><gameserverIP>old.example</gameserverIP></map>"
                + "<map id=\"9\">\n <gameserverIP>gs.example:6005</gameserverIP></map></maps>");
        assertEquals(new MapServers.Address("old.example", MapServers.DEFAULT_PORT), m.forMap(42));
        assertEquals(new MapServers.Address("gs.example", 6005), m.forMap(9));
    }

    @Test
    void flashVarsUnescapeJs() {
        Map<String, String> v = FlashVars.parse("{\"cdn\": \"https:\\/\\/cdn.example\\/\", \"t\": \"\\u0418\\u0433\"}");
        assertEquals("https://cdn.example/", v.get("cdn"));
        assertEquals("Иг", v.get("t"));
    }

    // ---- fake website ----

    private void landing(HttpExchange ex) throws IOException {
        if (!ex.getRequestURI().getPath().equals("/")) {
            send(ex, 404, "not found", false);
            return;
        }
        send(ex, 200, landingHtml(false), true);
    }

    private static String landingHtml(boolean captcha) {
        return "<html><body><form name=\"bgcdw_login_form\" method=\"post\" class=\"bgcdw_login_form\" "
                + "action=\"/sas/auth?authUser=22&amp;token=one-time\">"
                + "<input name=\"username\"><input name=\"password\" type=\"password\">"
                + (captcha ? "<div class=\"bgcdw_captcha\"><div class=\"h-captcha\" data-sitekey=\"abc\"></div></div>" : "")
                + "</form><div data-error-key=\"bgc.error.captcha.notsolved\"></div></body></html>";
    }

    private void sasAuth(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod()) || !ex.getRequestURI().getQuery().contains("token=one-time")) {
            send(ex, 400, "bad request", false);
            return;
        }
        Map<String, String> form = parseForm(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        if ("captcha".equals(form.get("username"))) {
            send(ex, 200, landingHtml(true), false);
        } else if ("right".equals(form.get("password"))) {
            // cookie set on an intermediate hop must survive the redirect chain
            ex.getResponseHeaders().add("Set-Cookie", "dosid=" + DOSID + "; Path=/");
            redirect(ex, "/ProjectApi/Authentication?authUser=22&token=xyz");
        } else {
            send(ex, 200, landingHtml(false), false);
        }
    }

    private void internal(HttpExchange ex) throws IOException {
        String cookie = String.valueOf(ex.getRequestHeaders().getFirst("Cookie"));
        if (!cookie.contains("dosid=" + DOSID)) {
            redirect(ex, "/");
            return;
        }
        String ua = String.valueOf(ex.getRequestHeaders().getFirst("User-Agent"));
        String query = String.valueOf(ex.getRequestURI().getQuery());
        if (query.contains("internalMapRevolution")) {
            if (!ua.startsWith("BigPointClient/")) {
                send(ex, 200, "<html>download the client</html>", true);
                return;
            }
            send(ex, 200, "<script>var flashembed = {\"src\": \"x\", \"userID\": \"166225758\", \"sessionID\": \""
                    + SESSION + "\", \"basePath\": \"spacemap\", \"pid\": \"22\", \"cdn\": \"" + origin.replace("/", "\\/")
                    + "\\/\", \"platform\": \"PC_CLIENT\", \"mapID\": \"9\"};</script>", true);
            return;
        }
        send(ex, 200, "<html><a href=\"logout\">logout</a></html>", false);
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                out.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return out;
    }

    private static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    private static void send(HttpExchange ex, int status, String body, boolean gzip) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        if (gzip) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            try (GZIPOutputStream gz = new GZIPOutputStream(bo)) {
                gz.write(bytes);
            }
            bytes = bo.toByteArray();
            ex.getResponseHeaders().add("Content-Encoding", "gzip");
        }
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }
}
