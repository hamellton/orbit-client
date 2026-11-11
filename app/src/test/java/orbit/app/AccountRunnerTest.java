package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import orbit.app.gui.ManagedAccount;
import orbit.game.ClientProfile;
import orbit.secrets.Account;
import orbit.secrets.Vault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountRunnerTest {

    @Test
    void refusesWhatCannotRun(@TempDir Path dir) throws Exception {
        Vault v = Vault.open(dir.resolve("vault.bin"), "Te5t-mäster".toCharArray());
        v.put("gamma", new Account("pilot-g", "Kk8%fake", "int1", null, null));

        AccountRunner missing = new AccountRunner("delta", v, ClientProfile.minimal());
        assertTrue(missing.start(0).contains("нет в хранилище"));
        assertEquals(ManagedAccount.Phase.STOPPED, missing.phase());

        // the test's working directory has no local/accounts.json: no config, no timetable
        AccountRunner noPlan = new AccountRunner("gamma", v, ClientProfile.minimal());
        assertTrue(noPlan.start(0).contains("не выбран конфиг"));
        assertEquals(ManagedAccount.Phase.STOPPED, noPlan.phase());
        assertEquals("остановлен", noPlan.feed().state());
    }
}
