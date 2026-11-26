package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PacketJournalTest {

    @Test
    void theSessionIsNeverShown() {
        var login = new orbit.protocol.gen.LoginRequest();
        login.userID = 12345;
        login.sessionID = "invented-session-abc";
        String text = PacketJournal.text(login);
        assertFalse(text.contains("invented-session-abc"), text);
        assertTrue(text.contains("sessionID=***"), text);
        assertTrue(text.contains("userID=12345"), text);
    }

    @Test
    void theRingKeepsTheLastAndCountsOn() {
        PacketJournal j = new PacketJournal();
        for (int i = 0; i < PacketJournal.CAPACITY + 5; i++) {
            j.add(i % 2 == 0, new orbit.protocol.gen.KeepAlive());
        }
        var all = j.since(0);
        assertEquals(PacketJournal.CAPACITY, all.size());
        assertEquals(6, all.getFirst().seq());
        assertEquals(2, j.since(PacketJournal.CAPACITY + 3).size());
        assertTrue(j.since(PacketJournal.CAPACITY + 5).isEmpty());
    }
}
