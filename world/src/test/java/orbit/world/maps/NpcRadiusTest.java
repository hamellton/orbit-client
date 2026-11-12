package orbit.world.maps;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class NpcRadiusTest {

    @Test
    void theRecommendedRadiiMatchTheGamesNames() {
        // DarkBot's list (the user, 2026-11-12)
        NpcRadius r = NpcRadius.bundled();
        assertEquals(450, r.of("-=[ Streuner ]=-"), "x-1");
        assertEquals(600, r.of("-=[ StreuneR ]=-"), "x-8: told apart by the case");
        assertEquals(615, r.of("..::{ Boss Kristallon }::.."));
        assertEquals(625, r.of("..::{ Boss Lordakium }::..."));
        assertEquals(580, r.of("( UberKristallin )"));
        assertEquals(500, r.of("( Uber Saimon )"));
        assertEquals(670, r.of("\\\\ Attend IX //"));
        assertEquals(580, r.of("-=[ Saboteur ]=-"));
        assertEquals(0, r.of("-=[ Nobody ]=-"), "not in the list");
    }
}
