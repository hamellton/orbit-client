package orbit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SkylabMessageTest {

    @Test
    void theSitesNoticeIsReadAsPlainText() {
        String page = "<html><div class=\"skylab_error_box\"><b>Not enough</b> space in&nbsp;your cargo bay</div></html>";
        assertEquals("Not enough space in your cargo bay", Skylab.siteMessage(page));
        assertEquals("", Skylab.siteMessage("<html><div class=\"plain\">ok</div></html>"));
        assertEquals("", Skylab.siteMessage(null));
    }
}
