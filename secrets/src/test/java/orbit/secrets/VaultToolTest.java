package orbit.secrets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class VaultToolTest {

    @Test
    void dosidLine() {
        assertEquals("abc123XYZ", VaultTool.dosidOf("abc123XYZ"));
        assertEquals("abc123XYZ", VaultTool.dosidOf("dosid: abc123XYZ"));
        assertEquals("abc123XYZ", VaultTool.dosidOf("dosid=abc123XYZ "));
        assertEquals("abc123XYZ", VaultTool.dosidOf("dosid - abc123XYZ"));
        assertNull(VaultTool.dosidOf("dosid:"));
        assertNull(VaultTool.dosidOf("   "));
    }
}
