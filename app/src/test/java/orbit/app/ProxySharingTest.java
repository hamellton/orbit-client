package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import orbit.secrets.ProxySpec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProxySharingTest {

    @Test
    void theSameProxyTwoDirectOnesAndTheSameIpAreTold() {
        Map<String, ProxySpec> p = new LinkedHashMap<>();
        p.put("a", ProxySpec.parse("socks5://u1:x@10.0.0.1:1080"));
        p.put("b", ProxySpec.parse("http://u1:y@10.0.0.1:1080")); // the type and the password do not matter
        p.put("c", ProxySpec.parse("socks5://u2:x@10.0.0.1:1080")); // another login: another exit
        p.put("d", ProxySpec.DIRECT);
        p.put("e", ProxySpec.DIRECT);
        p.put("f", ProxySpec.parse("socks5://9.9.9.9:1080"));
        var links = ProxySharing.of(p, Map.of("c", "5.5.5.5", "f", "5.5.5.5", "d", "1.1.1.1"));
        assertEquals(List.of("b"), links.get("a").sameProxy());
        assertEquals(List.of(), links.get("c").sameProxy());
        assertEquals(List.of("f"), links.get("c").sameIp(), "two proxies, one exit IP");
        assertEquals(List.of("e"), links.get("d").sameProxy(), "both without a proxy: this computer's IP");
        assertEquals("напрямую — IP этого компьютера · один IP с «e»",
                ProxySharing.describe(ProxySpec.DIRECT, null, links.get("d")));
        assertEquals("SOCKS5 10.0.0.1:1080 (логин u1) · DE · тот же прокси, что у «b»",
                ProxySharing.describe(p.get("a"), new IpHistory.Seen("5.6.7.8", "DE", 0), links.get("a")));
    }

    @Test
    void aCountryChangeIsToldOnceAndKept(@TempDir Path dir) throws Exception {
        Path f = dir.resolve("proxy-seen.json");
        IpHistory h = new IpHistory(f);
        assertNull(h.record("a", "5.6.7.8", "DE"), "first time: nothing to compare");
        assertNull(h.record("a", "5.6.7.9", "DE"), "another IP in the same country is fine");
        assertNull(h.record("a", "5.6.7.9", null), "the service said nothing: the country stays");
        assertEquals("DE", h.get("a").country());
        assertEquals("DE", h.record("a", "8.8.8.8", "US"));
        assertTrue(Files.exists(f));
        IpHistory again = new IpHistory(f);
        assertEquals(new IpHistory.Seen("8.8.8.8", "US", again.get("a").at()), again.get("a"));
    }
}
