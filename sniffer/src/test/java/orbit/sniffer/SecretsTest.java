package orbit.sniffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import orbit.protocol.PacketCodec;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.LoginRequest;
import org.junit.jupiter.api.Test;

class SecretsTest {

    private static final String SID = "0123456789abcdef0123456789abcdef";

    @Test
    void sessionIdRepeatedByTheClientIsMaskedEverywhere() {
        LoginRequest login = new LoginRequest();
        login.userID = 42;
        login.sessionID = SID;
        login.version = "do-lz4-1";
        byte[] sid = Secrets.sessionId(PacketCodec.encode(login));
        assertArrayEquals(SID.getBytes(), sid);

        LegacyModule cfg = new LegacyModule();
        cfg.message = "S|CFG|1|42|" + SID;
        byte[] masked = Secrets.maskAll(PacketCodec.encode(cfg), sid);
        assertEquals("S|CFG|1|42|" + "x".repeat(SID.length()), ((LegacyModule) PacketCodec.decode(masked)).message);

        LegacyModule other = new LegacyModule();
        other.message = "LF|w";
        byte[] plain = PacketCodec.encode(other);
        assertSame(plain, Secrets.maskAll(plain, sid), "packets without the secret are untouched");
    }
}
