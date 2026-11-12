package orbit.app;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import orbit.secrets.ProxySpec;

/**
 * Accounts the game can link by their address (plan 5в.5): the same proxy (host, port and login — the type does not
 * matter), both without a proxy (this computer's IP), or the same public IP last seen through different proxies.
 */
public final class ProxySharing {

    private ProxySharing() {
    }

    /** What links an account to others; empty lists for accounts that share nothing. */
    public record Links(List<String> sameProxy, List<String> sameIp) {

        public boolean any() {
            return !sameProxy.isEmpty() || !sameIp.isEmpty();
        }
    }

    /**
     * @param proxies each account's proxy ({@code null} when it does not parse: it links nothing)
     * @param seen    each account's last public IP through its proxy (may lack accounts)
     */
    public static Map<String, Links> of(Map<String, ProxySpec> proxies, Map<String, String> seen) {
        Map<String, Links> out = new LinkedHashMap<>();
        for (String n : proxies.keySet()) {
            String key = key(proxies.get(n));
            String ip = proxies.get(n) == null || proxies.get(n).direct() ? null : seen.get(n);
            List<String> same = new ArrayList<>();
            List<String> sameIp = new ArrayList<>();
            for (String o : proxies.keySet()) {
                if (o.equals(n)) {
                    continue;
                }
                if (key != null && key.equals(key(proxies.get(o)))) {
                    same.add(o);
                } else if (ip != null && proxies.get(o) != null && !proxies.get(o).direct() && ip.equals(seen.get(o))) {
                    sameIp.add(o);
                }
            }
            out.put(n, new Links(List.copyOf(same), List.copyOf(sameIp)));
        }
        return out;
    }

    /** {@code direct} or {@code host:port user}, the same for one proxy written twice; {@code null} unknown. */
    static String key(ProxySpec p) {
        if (p == null) {
            return null;
        }
        if (p.direct()) {
            return "direct";
        }
        return p.host().toLowerCase(Locale.ROOT) + ":" + p.port() + " " + (p.user() == null ? "" : p.user());
    }

    /** For the account list: {@code SOCKS5 1.2.3.4:1080 · DE · тот же прокси, что у «b»}. */
    public static String describe(ProxySpec p, IpHistory.Seen seen, Links links) {
        StringBuilder s = new StringBuilder(p.direct() ? "напрямую — IP этого компьютера" : p.describe());
        if (!p.direct() && seen != null && seen.country() != null) {
            s.append(" · ").append(seen.country());
        }
        if (links != null && !links.sameProxy().isEmpty()) {
            s.append(p.direct() ? " · один IP с " : " · тот же прокси, что у ").append(names(links.sameProxy()));
        }
        if (links != null && !links.sameIp().isEmpty()) {
            s.append(" · тот же IP, что у ").append(names(links.sameIp()));
        }
        return s.toString();
    }

    static String names(List<String> names) {
        return String.join(", ", names.stream().map(n -> "«" + n + "»").toList());
    }
}
