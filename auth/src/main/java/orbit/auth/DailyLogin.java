package orbit.auth;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import orbit.secrets.SecretMask;

/**
 * The daily login calendar's reward of the logged-in account, claimed on the website as AstraBot does right after its
 * login ({@code flashAPI/dailyLogin.php?doBook=1}; DarkBot's PikaPika plugin asks the site the same way once a day).
 * A day missed is a reward lost, so it is asked once every server day.
 */
public final class DailyLogin {

    /** The game's day changes at midnight server time (CET/CEST). */
    public static final ZoneId SERVER_ZONE = ZoneId.of("Europe/Berlin");

    static final String PATH = "/flashAPI/dailyLogin.php?doBook=1";

    /** The site's answer: its HTTP status and the body, short and masked (it is logged). */
    public record Result(int status, String answer) {

        public boolean ok() {
            return status == 200;
        }
    }

    private final HttpSession http;
    private final String origin;

    /** @param origin the account's site, e.g. {@code https://int1.darkorbit.com} ({@link GameSession#host}) */
    public DailyLogin(HttpSession http, String origin) {
        this.http = http;
        this.origin = origin;
    }

    /** Books today's login on the site. */
    public Result claim() throws IOException {
        HttpSession.Response r = http.get(origin + PATH, origin + Shop.START_PAGE);
        return new Result(r.status(), shorten(r.body()));
    }

    /** The server's date now. */
    public static LocalDate serverToday() {
        return LocalDate.now(SERVER_ZONE);
    }

    static String shorten(String body) {
        String b = body == null ? "" : body.strip().replaceAll("\\s+", " ");
        if (b.length() > 200) {
            b = b.substring(0, 200) + "…";
        }
        return SecretMask.mask(b);
    }
}
