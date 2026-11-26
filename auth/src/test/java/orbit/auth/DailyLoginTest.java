package orbit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DailyLoginTest {

    @Test
    void readsTheSiteAnswers() {
        var booked = new DailyLogin.Result(200, "{\"success\":true}");
        assertTrue(booked.booked());
        assertTrue(booked.ok());

        var already = new DailyLogin.Result(200, "{\"isError\":1,\"error\":{\"message\":\"Already booked today\"}}");
        assertFalse(already.booked());
        assertTrue(already.already());
        assertTrue(already.ok());

        var other = new DailyLogin.Result(200, "{\"isError\":1,\"error\":{\"message\":\"Something else\"}}");
        assertFalse(other.ok());
        assertEquals("Something else", other.error());

        assertFalse(new DailyLogin.Result(200, "<html>").ok());
        assertFalse(new DailyLogin.Result(500, "{\"success\":true}").ok());
    }
}
