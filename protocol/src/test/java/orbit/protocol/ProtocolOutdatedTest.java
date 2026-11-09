package orbit.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.protocol.gen.VersionCommand;
import org.junit.jupiter.api.Test;

class ProtocolOutdatedTest {

    @Test
    void equalVersionPasses() {
        VersionCommand vc = new VersionCommand();
        vc.equal = true;
        assertDoesNotThrow(() -> ProtocolOutdatedException.check(vc));
    }

    @Test
    void mismatchTellsUserToRegenerate() {
        VersionCommand vc = new VersionCommand();
        vc.equal = false;
        vc.version = "ffff";
        ProtocolOutdatedException e = assertThrows(ProtocolOutdatedException.class,
                () -> ProtocolOutdatedException.check(vc));
        assertTrue(e.getMessage().contains("update-protocol"), e.getMessage());
        assertTrue(e.getMessage().contains("устарела"), e.getMessage());
    }
}
