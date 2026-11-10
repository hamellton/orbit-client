package orbit.auth;

import java.io.IOException;
import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import orbit.secrets.SecretMask;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Website login and game session discovery.
 *
 * <p>Flow (observed with the official client, Nov 2026):
 * <ol>
 *   <li>the landing page holds a login form posting {@code username}/{@code password} to
 *       {@code sas.bpsecure.com} with a one-time token in the action URL;</li>
 *   <li>after redirects the browser lands on the account server ({@code https://int1.darkorbit.com})
 *       with a {@code dosid} cookie;</li>
 *   <li>{@code indexInternal.es?action=internalMapRevolution} returns the Flash parameters only to
 *       the official client's user agent {@code BigPointClient/<version>};</li>
 *   <li>{@code <cdn><basePath>/xml/maps.php} maps the current map to its game server.</li>
 * </ol>
 */
public final class AuthClient {

    private static final Logger log = LoggerFactory.getLogger(AuthClient.class);

    /** Used when the update feed cannot be read. */
    static final String FALLBACK_CLIENT_VERSION = "1.6.9";

    private static final Pattern LOGIN_FORM = Pattern.compile(
            "(?is)<form[^>]*name=\"bgcdw_login_form\"[^>]*action=\"([^\"]+)\"[^>]*>(.*?)</form>");
    private static final Pattern CLIENT_VERSION = Pattern.compile(">([^<]+)</Version");

    /** URLs and host rules; replaceable in tests. */
    public record Endpoints(String landingUrl, String clientUpdatesUrl, Pattern accountHost,
                            String accountOriginFormat) {

        public static Endpoints production() {
            return new Endpoints("https://www.darkorbit.com/",
                    "http://darkorbit-22-client.bpsecure.com/bpflashclient/windows.x64/repository/Updates.xml",
                    Pattern.compile("^[a-z]+\\d+\\.darkorbit\\.com$"),
                    "https://%s.darkorbit.com");
        }

        /** Origin of an account server, e.g. {@code int1} → {@code https://int1.darkorbit.com}. */
        String accountOrigin(String server) {
            if (!server.matches("[a-z]+\\d+")) {
                throw new IllegalArgumentException("код сервера должен быть вида int1, de2: " + server);
            }
            return String.format(accountOriginFormat, server);
        }
    }

    private final HttpSession http;
    private final Endpoints endpoints;
    private String clientVersion;

    public AuthClient(HttpSession http, Endpoints endpoints) {
        this.http = http;
        this.endpoints = endpoints;
    }

    /** Logs in with username and password. */
    /** The web session (cookies of the logged-in account), e.g. for the shop. */
    public HttpSession http() {
        return http;
    }

    public GameSession loginWithPassword(String username, String password) throws AuthException {
        SecretMask.register(password);
        useClientUserAgent();
        HttpSession.Response landing = request(() -> http.get(endpoints.landingUrl()));
        Matcher form = LOGIN_FORM.matcher(landing.body());
        if (!form.find()) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE,
                    "На главной странице не найдена форма входа — сайт изменился или недоступен.");
        }
        String action = landing.url().resolve(htmlUnescape(form.group(1))).toString();
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("username", username);
        fields.put("password", password);
        log.info("Вход на сайт по логину…");
        HttpSession.Response result = request(() -> http.postForm(action, fields, landing.url().toString()));

        String host = result.url().getHost();
        if (endpoints.accountHost().matcher(host).matches()) {
            String origin = result.url().getScheme() + "://" + result.url().getAuthority();
            String dosid = http.cookie(origin + "/", "dosid");
            if (dosid != null) {
                SecretMask.register(dosid);
                // the site hands out a dosid to anonymous visitors too; only the game page proves the login
                log.info("Пароль принят, сервер аккаунта: {}", host);
                return loadGameSession(origin, dosid, true);
            }
        }
        throw classifyLoginFailure(result);
    }

    /**
     * Uses a {@code dosid} taken from the browser (needed when the site asks for a captcha).
     *
     * @param server account server code, e.g. {@code int1} for GE1
     */
    public GameSession loginWithDosid(String server, String dosid) throws AuthException {
        SecretMask.register(dosid);
        useClientUserAgent();
        String origin = endpoints.accountOrigin(server);
        http.setCookie(origin + "/", "dosid", dosid);
        log.info("Вход по dosid на {}", URI.create(origin).getHost());
        return loadGameSession(origin, dosid, false);
    }

    /**
     * Reads the game page and the map table for an already authenticated session.
     *
     * @param afterPasswordLogin whether the session was just created by a password login (then a
     *                           rejected session is our bug or a site change, not an expired dosid)
     */
    GameSession loadGameSession(String origin, String dosid, boolean afterPasswordLogin) throws AuthException {
        HttpSession.Response page = request(() -> http.get(origin + "/indexInternal.es?action=internalMapRevolution"));
        Map<String, String> vars = FlashVars.parse(page.body());
        if (!vars.containsKey("sessionID") || !vars.containsKey("userID")) {
            String loginError = queryParam(page.url(), "loginError");
            boolean rejected = loginError != null
                    || !endpoints.accountHost().matcher(page.url().getHost()).matches()
                    || LOGIN_FORM.matcher(page.body()).find();
            String code = loginError == null ? "" : " (loginError=" + loginError + ")";
            if (rejected && afterPasswordLogin) {
                throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE,
                        "Пароль принят, но сайт не открыл страницу игры" + code
                                + " — вероятно, изменился порядок входа на сайте.");
            }
            if (rejected) {
                throw new AuthException(AuthException.Reason.SESSION_EXPIRED,
                        "Сессия сайта недействительна или истекла (dosid)" + code + ". Войдите заново.");
            }
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE,
                    "Страница игры не содержит параметров запуска — сайт изменился, либо аккаунт ещё "
                            + "не прошёл начальную настройку в браузере.");
        }
        SecretMask.register(vars.get("sessionID"));
        int userId = parseInt(vars, "userID");
        int instanceId = parseInt(vars, "pid");
        int mapId = parseInt(vars, "mapID");
        String cdn = vars.getOrDefault("cdn", "");
        String basePath = vars.getOrDefault("basePath", "spacemap");
        if (cdn.isEmpty()) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "В параметрах игры нет адреса cdn.");
        }
        // the client loads maps.php from its dynamicHost, set from the flashvar "host" (the account server,
        // e.g. https://ru1.darkorbit.com); the CDN copy lists the game hosts of another server
        String dynamicHost = vars.getOrDefault("host", vars.getOrDefault("dynamicHost", ""));
        String mapsBase = dynamicHost.isBlank() ? cdn : dynamicHost.replaceAll("/+$", "") + "/";
        String mapsUrl = URI.create(mapsBase).resolve(basePath.replaceAll("/+$", "") + "/xml/maps.php").toString();
        HttpSession.Response maps = request(() -> http.get(mapsUrl));
        MapServers servers = MapServers.parse(maps.body());
        MapServers.Address addr = servers.forMap(mapId);
        if (addr == null) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE,
                    "Не найден игровой сервер для карты " + mapId + " (в maps.php " + servers.size() + " карт).");
        }
        // the site may rotate the cookie; keep the one the browser would have now
        String currentDosid = http.cookie(origin + "/", "dosid");
        if (currentDosid != null && !currentDosid.equals(dosid)) {
            SecretMask.register(currentDosid);
            dosid = currentDosid;
        }
        GameSession s = new GameSession(origin, dosid, userId, vars.get("sessionID"), instanceId, mapId,
                addr.host(), addr.port(), vars.getOrDefault("platform", ""), mapsUrl);
        log.info("Параметры игры получены: карта {}, сервер {}", mapId, addr);
        return s;
    }

    /** Looks up the game server of a map, e.g. after {@code ReloginCommand}. */
    public MapServers.Address serverForMap(GameSession session, int mapId) throws AuthException {
        MapServers servers = MapServers.parse(request(() -> http.get(session.mapsUrl())).body());
        MapServers.Address addr = servers.forMap(mapId);
        if (addr == null) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "Не найден игровой сервер для карты " + mapId + ".");
        }
        return addr;
    }

    private AuthException classifyLoginFailure(HttpSession.Response result) {
        String where = result.url().getHost() + result.url().getPath();
        Matcher form = LOGIN_FORM.matcher(result.body());
        if (form.find()) {
            String formBody = form.group(2);
            if (formBody.contains("data-sitekey") || formBody.contains("h-captcha")) {
                return new AuthException(AuthException.Reason.CAPTCHA_REQUIRED,
                        "Сайт требует решить капчу. Войдите в браузере или в клиенте, скопируйте cookie "
                                + "dosid и укажите его в профиле — бот войдёт по нему.");
            }
            return new AuthException(AuthException.Reason.INVALID_CREDENTIALS,
                    "Не удалось войти: неверный логин или пароль (сайт вернул форму входа, " + where + ").");
        }
        if (result.status() >= 500) {
            return new AuthException(AuthException.Reason.UNAVAILABLE,
                    "Сайт игры недоступен (HTTP " + result.status() + "), попробую позже.");
        }
        String lower = result.body().toLowerCase();
        if (lower.contains("banned") || lower.contains("gesperrt") || lower.contains("заблокирован")) {
            return new AuthException(AuthException.Reason.ACCOUNT_BLOCKED, "Аккаунт заблокирован (" + where + ").");
        }
        return new AuthException(AuthException.Reason.UNEXPECTED_PAGE,
                "Вход не удался: неожиданная страница " + where + " (HTTP " + result.status() + ").");
    }

    /** The site serves the Flash game page only to the official client's user agent. */
    private void useClientUserAgent() {
        if (clientVersion == null) {
            try {
                Matcher m = CLIENT_VERSION.matcher(http.get(endpoints.clientUpdatesUrl()).body());
                clientVersion = m.find() ? m.group(1).trim() : FALLBACK_CLIENT_VERSION;
            } catch (IOException | RuntimeException e) {
                log.warn("Не удалось узнать версию клиента ({}), использую {}", e.getMessage(), FALLBACK_CLIENT_VERSION);
                clientVersion = FALLBACK_CLIENT_VERSION;
            }
        }
        http.setUserAgent("BigPointClient/" + clientVersion);
    }

    /**
     * A server of the login in the site's server list ({@code ajax/instances.php}).
     *
     * @param hasPilot the link logs into an existing pilot ({@code portal.loginByCryptId}); otherwise the
     *                 site would create a new one there ({@code portal.addInstanceByCryptId})
     */
    public record Instance(String server, int gameId, String url, boolean hasPilot) {
        @Override
        public String toString() {
            return server + (hasPilot ? "" : " (нет пилота)"); // the url is a login link: never printed
        }
    }

    private static final Pattern INSTANCE = Pattern.compile(
            "id=\"serverSelection_ini_(\\d+)\"[^>]*?target=\"([^\"]+)\"|target=\"([^\"]+)\"[^>]*?id=\"serverSelection_ini_(\\d+)\"");
    private static final com.fasterxml.jackson.databind.ObjectMapper INSTANCES_JSON =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** The website origin of a server code ({@code ru5} → {@code https://ru5.darkorbit.com}). */
    public String originOf(String server) {
        return endpoints.accountOrigin(server);
    }

    /** The servers of the logged-in account, as the site's «Сервер» button lists them. */
    public java.util.List<Instance> instances(String origin) throws AuthException {
        HttpSession.Response r = request(() -> http.postForm(origin + "/ajax/instances.php",
                Map.of("command", "getInstanceList"), origin + "/indexInternal.es?action=internalStart"));
        String html;
        try {
            com.fasterxml.jackson.databind.JsonNode n = INSTANCES_JSON.readTree(r.body());
            if (!"OK".equals(n.path("result").asText())) {
                throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "Сайт не отдал список серверов.");
            }
            html = n.path("code").asText();
        } catch (IOException e) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "Список серверов не прочитать: " + e.getMessage(), e);
        }
        return parseInstances(html);
    }

    static java.util.List<Instance> parseInstances(String html) {
        java.util.List<Instance> out = new java.util.ArrayList<>();
        Matcher m = INSTANCE.matcher(html);
        while (m.find()) {
            int gameId = Integer.parseInt(m.group(1) != null ? m.group(1) : m.group(4));
            String url = htmlUnescape(m.group(2) != null ? m.group(2) : m.group(3));
            URI u = URI.create(url);
            String host = u.getHost() == null ? "" : u.getHost();
            String server = host.contains(".") ? host.substring(0, host.indexOf('.')) : host;
            out.add(new Instance(server, gameId, url, decodeAction(u).contains("portal.loginByCryptId")));
        }
        return out;
    }

    /** The {@code req} parameter: base64 of a PHP-serialised {action, loginCryptId, gameId, locale}. */
    private static String decodeAction(URI u) {
        String q = u.getRawQuery() == null ? "" : u.getRawQuery();
        for (String part : q.split("&")) {
            if (part.startsWith("req=")) {
                try {
                    String v = java.net.URLDecoder.decode(part.substring(4), java.nio.charset.StandardCharsets.UTF_8);
                    String php = new String(java.util.Base64.getDecoder().decode(v), java.nio.charset.StandardCharsets.UTF_8);
                    int i = php.indexOf("portal.");
                    return i < 0 ? "" : php.substring(i, Math.min(php.length(), i + 40));
                } catch (IllegalArgumentException e) {
                    return "";
                }
            }
        }
        return "";
    }

    /**
     * Another server of the same login, the way the site's server list does it: its login link sets
     * that server's session. A server where the login has no pilot yet is refused (the link would
     * create one).
     */
    public GameSession switchTo(GameSession from, String server) throws AuthException {
        Instance target = instances(from.host()).stream().filter(i -> i.server().equals(server)).findFirst()
                .orElseThrow(() -> new AuthException(AuthException.Reason.UNEXPECTED_PAGE,
                        "Сервера " + server + " нет в списке серверов аккаунта."));
        if (!target.hasPilot()) {
            throw new AuthException(AuthException.Reason.INVALID_CREDENTIALS, "На сервере " + server
                    + " у аккаунта нет пилота — бот не создаёт новых. Зайдите туда один раз в браузере.");
        }
        String origin = endpoints.accountOrigin(server);
        request(() -> http.get(target.url(), from.host() + "/indexInternal.es?action=internalStart"));
        String dosid = http.cookie(origin + "/", "dosid");
        if (dosid == null) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "Сайт не открыл сервер " + server + ".");
        }
        SecretMask.register(dosid);
        log.info("Перешёл на сервер {}", server);
        return loadGameSession(origin, dosid, false);
    }

    private interface Call {
        HttpSession.Response run() throws IOException;
    }

    private static HttpSession.Response request(Call call) throws AuthException {
        try {
            return call.run();
        } catch (IOException e) {
            throw new AuthException(AuthException.Reason.UNAVAILABLE,
                    "Нет связи с сайтом игры: " + e.getMessage(), e);
        }
    }

    private static int parseInt(Map<String, String> vars, String key) throws AuthException {
        try {
            return Integer.parseInt(vars.getOrDefault(key, "").trim());
        } catch (NumberFormatException e) {
            throw new AuthException(AuthException.Reason.UNEXPECTED_PAGE, "Параметр " + key + " не число.");
        }
    }

    private static String queryParam(URI uri, String name) {
        String q = uri.getRawQuery();
        if (q == null) {
            return null;
        }
        for (String pair : q.split("&")) {
            if (pair.startsWith(name + "=")) {
                return pair.substring(name.length() + 1);
            }
        }
        return null;
    }

    private static String htmlUnescape(String s) {
        return s.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&lt;", "<").replace("&gt;", ">");
    }
}
