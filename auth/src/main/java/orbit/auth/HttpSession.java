package orbit.auth;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import java.util.zip.InflaterInputStream;

/**
 * Minimal HTTP client for one account: its own cookie store, optional proxy, gzip and
 * manual redirect handling (so cookies set on every hop are kept, across hosts).
 *
 * <p>Not thread-safe; one instance per account.
 */
public final class HttpSession {

    private static final int MAX_REDIRECTS = 10;
    private static final int TIMEOUT_MS = 30_000;

    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
    private final Proxy proxy;
    private final String proxyAuthorization;
    private String userAgent;

    /** One finished response after following redirects. */
    public record Response(int status, URI url, Map<String, List<String>> headers, String body) {
    }

    /**
     * @param proxy              {@link Proxy#NO_PROXY}, an HTTP or a SOCKS proxy
     * @param proxyAuthorization value for {@code Proxy-Authorization} (HTTP proxies only), or {@code null}
     */
    public HttpSession(String userAgent, Proxy proxy, String proxyAuthorization) {
        this.userAgent = userAgent;
        this.proxy = proxy;
        this.proxyAuthorization = proxyAuthorization;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public Response get(String url) throws IOException {
        return execute(URI.create(url), "GET", null, null);
    }

    public Response postForm(String url, Map<String, String> form, String referer) throws IOException {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> e : form.entrySet()) {
            if (!sb.isEmpty()) {
                sb.append('&');
            }
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return execute(URI.create(url), "POST", sb.toString().getBytes(StandardCharsets.UTF_8), referer);
    }

    /** Value of a cookie for the given URL, or {@code null}. */
    public String cookie(String url, String name) {
        for (HttpCookie c : cookies.getCookieStore().get(URI.create(url))) {
            if (c.getName().equals(name)) {
                return c.getValue();
            }
        }
        return null;
    }

    /** Adds a cookie as if the server at {@code url} had set it (e.g. a saved {@code dosid}). */
    public void setCookie(String url, String name, String value) {
        URI uri = URI.create(url);
        HttpCookie c = new HttpCookie(name, value);
        c.setPath("/");
        c.setDomain(uri.getHost());
        c.setVersion(0);
        cookies.getCookieStore().add(uri, c);
    }

    private Response execute(URI uri, String method, byte[] body, String referer) throws IOException {
        for (int hop = 0; hop <= MAX_REDIRECTS; hop++) {
            HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection(proxy);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(TIMEOUT_MS);
            conn.setReadTimeout(TIMEOUT_MS);
            conn.setRequestMethod(method);
            conn.setRequestProperty("User-Agent", userAgent);
            conn.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            conn.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.8");
            conn.setRequestProperty("Accept-Encoding", "gzip, deflate");
            if (proxyAuthorization != null) {
                conn.setRequestProperty("Proxy-Authorization", proxyAuthorization);
            }
            if (referer != null) {
                conn.setRequestProperty("Referer", referer);
            }
            for (Map.Entry<String, List<String>> e : cookies.get(uri, Map.of()).entrySet()) {
                for (String v : e.getValue()) {
                    conn.addRequestProperty(e.getKey(), v);
                }
            }
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(body);
                }
            }

            int status = conn.getResponseCode();
            Map<String, List<String>> headers = conn.getHeaderFields();
            cookies.put(uri, headers);

            String location = conn.getHeaderField("Location");
            if (status >= 300 && status < 400 && location != null) {
                drain(conn);
                referer = uri.toString();
                uri = uri.resolve(location);
                if (status != 307 && status != 308) {
                    method = "GET";
                    body = null;
                }
                continue;
            }
            return new Response(status, uri, headers, readBody(conn));
        }
        throw new IOException("слишком много переадресаций, последняя: " + uri.getHost());
    }

    private static String readBody(HttpURLConnection conn) throws IOException {
        InputStream raw = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream();
        if (raw == null) {
            return "";
        }
        String enc = String.valueOf(conn.getContentEncoding()).toLowerCase();
        try (InputStream in = enc.contains("gzip") ? new GZIPInputStream(raw)
                : enc.contains("deflate") ? new InflaterInputStream(raw) : raw) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            in.transferTo(out);
            return out.toString(StandardCharsets.UTF_8);
        }
    }

    private static void drain(HttpURLConnection conn) {
        try (InputStream in = conn.getResponseCode() >= 400 ? conn.getErrorStream() : conn.getInputStream()) {
            if (in != null) {
                in.transferTo(OutputStream.nullOutputStream());
            }
        } catch (IOException ignored) {
            // the body of a redirect is irrelevant
        }
    }
}
