package orbit.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AccountCalendarTest {

    @Test
    void calendarIsOnByDefaultAndOnlyOffIsStored(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("accounts.json");
        var entry = new AccountSettings.Entry("a", Schedule.OFF);
        assertTrue(entry.claimsCalendar());

        new AccountSettings(java.util.Map.of("main", entry.withCalendar(true))).save(file);
        assertFalse(Files.readString(file).contains("calendar"));
        assertTrue(AccountSettings.load(file).account("main").claimsCalendar());

        new AccountSettings(java.util.Map.of("main", entry.withCalendar(false).withConfig("b"))).save(file);
        var back = AccountSettings.load(file).account("main");
        assertFalse(back.claimsCalendar());
        assertTrue(back.withCalendar(true).claimsCalendar());
    }
}
