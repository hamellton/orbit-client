package orbit.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VaultTest {

    /** Low iteration count keeps tests fast; production uses {@link Vault#DEFAULT_ITERATIONS}. */
    private static final int FAST = 10_000;
    private static final String PASSWORD = "KoLYa[$D†O$]p@ss";

    @TempDir
    Path dir;

    @AfterEach
    void clearMask() {
        SecretMask.clear();
    }

    @Test
    void roundTripAndSecretsNotInFile() throws Exception {
        Path file = dir.resolve("local/vault.bin");
        Vault v = Vault.open(file, "master".toCharArray(), FAST);
        v.put("main", new Account("pilot", PASSWORD, "int1", "dosid-0123456789", "socks5://u:proxypass@h:1080"));
        v.save();

        byte[] raw = Files.readAllBytes(file);
        String asText = new String(raw, StandardCharsets.ISO_8859_1) + new String(raw, StandardCharsets.UTF_8);
        assertFalse(asText.contains("pilot") || asText.contains("dosid-0123") || asText.contains("proxypass"));

        Vault again = Vault.open(file, "master".toCharArray());
        Account a = again.get("main");
        assertEquals(PASSWORD, a.password());
        assertEquals("dosid-0123456789", a.dosid());
        assertEquals("int1", a.server());

        again.put("main", a.withDosid(null));
        again.save();
        assertNull(Vault.open(file, "master".toCharArray()).get("main").dosid());
    }

    @Test
    void wrongMasterPasswordIsReported() throws Exception {
        Path file = dir.resolve("vault.bin");
        Vault v = Vault.open(file, "master".toCharArray(), FAST);
        v.put("main", new Account("pilot", "secret-pass", "int1", null, null));
        v.save();
        VaultException e = assertThrows(VaultException.class, () -> Vault.open(file, "wrong".toCharArray()));
        assertTrue(e.getMessage().contains("мастер-пароль"), e.getMessage());
    }

    @Test
    void tamperedFileIsRejected() throws Exception {
        Path file = dir.resolve("vault.bin");
        Vault v = Vault.open(file, "master".toCharArray(), FAST);
        v.put("main", new Account("pilot", "secret-pass", "int1", null, null));
        v.save();
        byte[] raw = Files.readAllBytes(file);
        raw[raw.length - 1] ^= 1;
        Files.write(file, raw);
        assertThrows(VaultException.class, () -> Vault.open(file, "master".toCharArray()));
        Files.writeString(file, "not a vault");
        assertThrows(VaultException.class, () -> Vault.open(file, "master".toCharArray()));
    }

    @Test
    void secretsAreMaskedInLogs() throws Exception {
        Vault v = Vault.open(dir.resolve("vault.bin"), "master".toCharArray(), FAST);
        v.put("main", new Account("pilot", PASSWORD, "int1", "dosid-0123456789", "socks5://u:proxypass@h:1080"));
        SecretMask.register("abc"); // too short, ignored

        String line = "login " + PASSWORD + " cookie dosid=dosid-0123456789 proxy proxypass abc";
        assertEquals("login *** cookie dosid=*** proxy *** abc", SecretMask.mask(line));
        assertEquals("pilot", SecretMask.mask("pilot"), "username is not a secret");

        MaskingConverter converter = new MaskingConverter();
        LoggerContext ctx = new LoggerContext();
        LoggingEvent event = new LoggingEvent("x", ctx.getLogger("t"), ch.qos.logback.classic.Level.INFO,
                "sent {}", null, new Object[] {"dosid-0123456789"});
        assertEquals("sent ***", converter.convert(event));
    }

    @Test
    void accountToStringHidesSecrets() {
        String s = new Account("pilot", PASSWORD, "int1", "dosid-0123456789", "socks5://u:p@h:1").toString();
        assertTrue(s.contains("pilot"));
        assertFalse(s.contains(PASSWORD) || s.contains("dosid-0123") || s.contains("u:p@"));
    }
}
