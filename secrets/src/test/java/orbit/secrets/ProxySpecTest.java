package orbit.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProxySpecTest {

    @Test
    void urlForms() {
        ProxySpec s = ProxySpec.parse("socks5://alice:Zq9%40w:x@10.1.2.3:1080");
        assertEquals(ProxySpec.Type.SOCKS5, s.type());
        assertEquals("10.1.2.3", s.host());
        assertEquals(1080, s.port());
        assertEquals("alice", s.user());
        assertEquals("Zq9@w:x", s.password());

        ProxySpec h = ProxySpec.parse(" http://proxy.example:3128/ ");
        assertEquals(ProxySpec.Type.HTTP, h.type());
        assertEquals("proxy.example", h.host());
        assertEquals(3128, h.port());
        assertNull(h.user());

        assertEquals(ProxySpec.Type.SOCKS5, ProxySpec.parse("socks5h://h:1").type());
        assertEquals(ProxySpec.Type.SOCKS5, ProxySpec.parse("SOCKS://h:1").type());
    }

    @Test
    void sellerForms() {
        ProxySpec s = ProxySpec.parse("gate.example:7000:bob-session-1:pa:ss");
        assertEquals(ProxySpec.Type.AUTO, s.type());
        assertEquals("gate.example", s.host());
        assertEquals(7000, s.port());
        assertEquals("bob-session-1", s.user());
        assertEquals("pa:ss", s.password());

        ProxySpec u = ProxySpec.parse("bob:secret@gate.example:7000");
        assertEquals(ProxySpec.Type.AUTO, u.type());
        assertEquals("bob", u.user());
        assertEquals("secret", u.password());

        ProxySpec plain = ProxySpec.parse("1.2.3.4:8080");
        assertEquals(ProxySpec.Type.AUTO, plain.type());
        assertFalse(plain.hasCredentials());

        ProxySpec typed = ProxySpec.parse("http://gate.example:7000:bob:secret");
        assertEquals(ProxySpec.Type.HTTP, typed.type());
        assertEquals("bob", typed.user());
        assertEquals("secret", typed.password());

        ProxySpec at = ProxySpec.parse("gate.example:7000:bob:p@ss:w%rd");
        assertEquals("gate.example", at.host());
        assertEquals("bob", at.user());
        assertEquals("p@ss:w%rd", at.password());

        ProxySpec digits = ProxySpec.parse("bob:1234@gate.example:7000");
        assertEquals("gate.example", digits.host());
        assertEquals(7000, digits.port());
        assertEquals("bob", digits.user());
        assertEquals("1234", digits.password());

        ProxySpec v6 = ProxySpec.parse("socks5://[::1]:1080");
        assertEquals("::1", v6.host());
        assertEquals("[::1]:1080", v6.address());
    }

    @Test
    void none() {
        for (String t : new String[] {null, "", "  ", "-", "none", "DIRECT"}) {
            assertTrue(ProxySpec.parse(t).direct(), String.valueOf(t));
        }
    }

    @Test
    void errorsAreForTheUser() {
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProxySpec.parse("socks4://h:1")).getMessage().contains("SOCKS4"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProxySpec.parse("https://h:1")).getMessage().contains("https"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProxySpec.parse("h")).getMessage().contains("порт"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProxySpec.parse("h:abc")).getMessage().contains("порт"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProxySpec.parse("h:0")).getMessage().contains("порт"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> ProxySpec.parse("h:1:onlyuser")).getMessage().contains("логин"));
    }

    @Test
    void passwordNeverPrinted() {
        ProxySpec s = ProxySpec.parse("h.example:1:u:Vv7#kq");
        assertFalse(s.toString().contains("Vv7#kq"));
        assertFalse(s.describe().contains("Vv7#kq"));
        assertEquals("прокси h.example:1 (логин u)", s.describe());

        Account a = new Account("pilot", null, "int1", null, "h.example:1:u:Vv7#kq");
        assertFalse(a.toString().contains("Vv7#kq"));
        a.registerSecrets();
        assertFalse(SecretMask.mask("pass Vv7#kq").contains("Vv7#kq"));
    }

    @Test
    void accountProxyChange() {
        Account a = new Account("pilot", null, "int1", null, null);
        assertTrue(a.proxySpec().direct());
        Account b = a.withProxy("socks5://h:9");
        assertEquals("socks5://h:9", b.proxy());
        assertNull(b.withProxy("").proxy());
        assertNull(b.withProxy(null).proxy());
    }
}
