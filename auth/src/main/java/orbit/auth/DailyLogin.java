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

    /**
     * The site's answer: its HTTP status and the body, short and masked (it is logged). Seen live 2026-11-26:
     * {@code {"success":true}} when the day is booked now, {@code {"isError":1,"error":{"message":"Already booked
     * today"}}} when it was booked before (by the client or a manual login).
     */
    public record Result(int status, String answer) {

        /** The day is booked now. */
        public boolean booked() {
            return status == 200 && node().path("success").asBoolean(false);
        }

        /** The day was booked before this request. */
        public boolean already() {
            return status == 200 && node().path("error").path("message").asText("").toLowerCase()
                    .contains("already booked");
        }

        /** The day is booked, now or before: nothing more to ask today. */
        public boolean ok() {
            return booked() || already();
        }

        /** The site's error text, {@code ""} none. */
        public String error() {
            return node().path("error").path("message").asText("");
        }

        private com.fasterxml.jackson.databind.JsonNode node() {
            try {
                return JSON.readTree(answer);
            } catch (IOException | RuntimeException e) {
                return com.fasterxml.jackson.databind.node.MissingNode.getInstance();
            }
        }
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

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
