package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.concurrent.atomic.AtomicInteger;
import orbit.auth.AuthClient;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.MapServers;
import orbit.config.BotConfig;
import orbit.game.SessionSource;
import org.junit.jupiter.api.Test;

class SwitchHangarTest {

    @Test
    void aStaleSiteLoginIsRenewedAndTheShipIsSwitched() throws Exception {
        // live 2026-11-12 on gb2: after a night the site sent its page instead of the hangar list, the account stopped
        AtomicInteger logins = new AtomicInteger();
        AtomicInteger lists = new AtomicInteger();
        String json = "{\"isError\":0,\"data\":{\"ret\":{\"hangars\":[{\"hangarID\":7,\"hangar_is_active\":true,"
                + "\"general\":{\"ship\":{\"L\":0}}}]},\"map\":{\"lootIds\":[\"ship_goliath\"]}}}";
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/flashAPI/inventory.php", ex -> {
            lists.incrementAndGet();
            String body = logins.get() == 0 ? "<!DOCTYPE html><html>login</html>"
                    : Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
        try {
            AuthClient auth = new AuthClient(new HttpSession("", java.net.Proxy.NO_PROXY), AuthClient.Endpoints.production());
            SessionSource source = new SessionSource() {
                @Override
                public GameSession fresh() {
                    logins.incrementAndGet();
                    return null;
                }

                @Override
                public MapServers.Address serverForMap(GameSession s, int mapId) {
                    return null;
                }
            };
            String host = "http://127.0.0.1:" + server.getAddress().getPort();
            boolean ok = OrbitMain.switchHangar(auth, source, host, 7, BotConfig.empty(), new WorldFeed("тест", "t"));
            assertTrue(ok);
            assertEquals(1, logins.get());
            assertEquals(2, lists.get());
        } finally {
            server.stop(0);
        }
    }
}
