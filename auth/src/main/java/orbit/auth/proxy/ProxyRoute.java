package orbit.auth.proxy;

import java.io.IOException;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import orbit.auth.AuthException;
import orbit.secrets.ProxySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * How one account reaches the internet: every website request and every game socket of the account
 * goes through here, so a set proxy is never bypassed. With a proxy, HTTP goes through a
 * {@link LocalProxy} in front of the {@link ProxyConnector}.
 */
public final class ProxyRoute implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ProxyRoute.class);
    /** Reached through the proxy to prove it can connect to the game's site. */
    static final String CHECK_HOST = "www.darkorbit.com";
    /** Answers with the caller's public address as plain text. */
    static final String IP_URL = "https://api.ipify.org";
    private static final int CHECK_TIMEOUT_MS = 15_000;

    private final ProxyConnector connector;
    private final LocalProxy local;
    private volatile String externalIp;

    private ProxyRoute(ProxyConnector connector, LocalProxy local) {
        this.connector = connector;
        this.local = local;
    }

    public static ProxyRoute none() {
        return new ProxyRoute(ProxyConnector.DIRECT, null);
    }

    public static ProxyRoute of(ProxySpec spec) throws IOException {
        if (spec.direct()) {
            return none();
        }
        spec.registerSecrets();
        ProxyConnector c = new ProxyConnector(spec);
        return new ProxyRoute(c, LocalProxy.start(c));
    }

    public boolean direct() {
        return local == null;
    }

    /** For game sockets. */
    public ProxyConnector connector() {
        return connector;
    }

    /** For {@code HttpURLConnection}: {@link Proxy#NO_PROXY} or the local relay. */
    public Proxy httpProxy() {
        return local == null ? Proxy.NO_PROXY : local.proxy();
    }

    /** For {@code java.net.http.HttpClient}: the system default or the local relay. */
    public ProxySelector selector() {
        return local == null ? HttpClient.Builder.NO_PROXY : local.selector();
    }

    /** The public address seen through the proxy at the last check, or {@code null}. */
    public String externalIp() {
        return externalIp;
    }

    /** {@code без прокси}, {@code SOCKS5 1.2.3.4:1080 · IP 5.6.7.8} — for the window. */
    public String describe() {
        String d = connector.spec().describe();
        return externalIp == null ? d : d + " · IP " + externalIp;
    }

    /** What a proxy check found. */
    public record Check(ProxySpec spec, long millis, String ip) {
        public String describe() {
            return spec.describe() + " работает: " + (ip != null ? "внешний IP " + ip : "внешний IP не узнать")
                    + ", " + millis + " мс до сайта игры";
        }
    }

    /**
     * Before a website login: the proxy must reach the game's site, else the account does not log in at
     * all (never directly). Direct routes pass without a check.
     *
     * @throws AuthException {@link AuthException.Reason#PROXY_REJECTED} for a wrong login or a wrong
     *                       proxy type, {@link AuthException.Reason#UNAVAILABLE} (retried) when it is down
     */
    public void verify() throws AuthException {
        if (direct()) {
            return;
        }
        try {
            Check c = check();
            log.info("Прокси {}", c.describe());
        } catch (ProxyException e) {
            boolean hopeless = e.kind() == ProxyException.Kind.AUTH || e.kind() == ProxyException.Kind.PROTOCOL;
            throw new AuthException(hopeless ? AuthException.Reason.PROXY_REJECTED : AuthException.Reason.UNAVAILABLE,
                    "Прокси: " + e.getMessage() + (hopeless ? ". Исправьте: vault set-proxy." : "."), e);
        }
    }

    /** Connects through the proxy to the game's site and asks for the public address. */
    public Check check() throws ProxyException {
        long start = System.nanoTime();
        try (Socket s = connector.open(CHECK_HOST, 443, CHECK_TIMEOUT_MS)) {
            log.debug("Прокси: {} открыт через {} ({})", CHECK_HOST, connector.spec().address(), s.getRemoteSocketAddress());
        } catch (ProxyException e) {
            throw e;
        } catch (IOException e) {
            throw new ProxyException(ProxyException.Kind.TARGET,
                    "через прокси не открыть " + CHECK_HOST + ": " + ProxyConnector.reason(e), e);
        }
        long millis = (System.nanoTime() - start) / 1_000_000;
        String ip = lookupIp();
        if (ip != null) {
            externalIp = ip;
        }
        return new Check(connector.spec(), millis, ip);
    }

    private String lookupIp() {
        try {
            HttpClient http = HttpClient.newBuilder().proxy(selector()).connectTimeout(Duration.ofSeconds(10)).build();
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(IP_URL)).timeout(Duration.ofSeconds(10))
                    .GET().build(), HttpResponse.BodyHandlers.ofString());
            String body = r.body().strip();
            return r.statusCode() == 200 && body.matches("[0-9a-fA-F.:]{3,45}") ? body : null;
        } catch (IOException e) {
            log.debug("Внешний IP через прокси не узнать: {}", e.getMessage());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    @Override
    public void close() {
        if (local != null) {
            local.close();
        }
    }
}
