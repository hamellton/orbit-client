package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import orbit.world.World;
import org.junit.jupiter.api.Test;

class GameFeedTest {

    @Test
    void theGamesLinesInItsWords() {
        assertEquals("Вы получили 26 880 опыта", MinimapPanel.noteText(new World.GameNote(0, "experience", 26_880, null)));
        assertEquals("Вы получили 100 уридия", MinimapPanel.noteText(new World.GameNote(0, "uridium", 100, null)));
        assertEquals("Потрачено 500 уридия", MinimapPanel.noteText(new World.GameNote(0, "uridium", -500, null)));
        assertEquals("-=- 5 -=-", MinimapPanel.noteText(new World.GameNote(0, null, 0, "-=- 5 -=-")));
    }

    @Test
    void theWorldKeepsTheGamesLines() {
        World w = new World();
        orbit.protocol.gen.LegacyModule lm = new orbit.protocol.gen.LegacyModule();
        lm.message = "0|LM|ST|URI|100|260711";
        w.apply(lm, 5);
        lm = new orbit.protocol.gen.LegacyModule();
        lm.message = "0|A|STD| -=- 5 -=- ";
        w.apply(lm, 6);
        var notes = w.gameNotes();
        assertEquals(2, notes.size());
        assertEquals("uridium", notes.get(0).item());
        assertEquals(100, notes.get(0).amount());
        assertEquals("-=- 5 -=-", notes.get(1).text());
    }
}
