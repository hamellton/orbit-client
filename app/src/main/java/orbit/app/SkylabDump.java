package orbit.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import orbit.auth.AccountLogin;
import orbit.auth.AuthClient;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.proxy.ProxyRoute;
import orbit.secrets.MasterPassword;
import orbit.secrets.Vault;

/**
 * A development tool: reads an account's Skylab page from the website (one GET, nothing changed there) and saves it
 * to {@code local/skylab-<account>.html} — the sample the Skylab parser is written against. Usage:
 * {@code SkylabDump <account>} with {@code ORBIT_MASTER_PASSWORD} set.
 */
public final class SkylabDump {

    private SkylabDump() {
    }

    public static void main(String[] args) throws Exception {
        String account = args.length > 0 ? args[0] : "3";
        Vault vault = Vault.open(Path.of("local/vault.bin"), MasterPassword.obtain(false));
        orbit.secrets.Account a = vault.get(OrbitMain.loginOf(account));
        if (a == null) {
            System.err.println("Аккаунта нет в хранилище: " + account);
            System.exit(1);
        }
        var spec = a.proxySpec();
        try (ProxyRoute route = spec.direct() ? ProxyRoute.none() : ProxyRoute.of(spec)) {
            route.verify();
            AuthClient auth = new AuthClient(new HttpSession("", route.httpProxy()), AuthClient.Endpoints.production());
            GameSession session = new AccountLogin(auth, vault, OrbitMain.loginOf(account), OrbitMain.serverOf(account))
                    .login();
            String origin = session.host();
            HttpSession.Response r = auth.http().get(origin + "/indexInternal.es?action=internalSkylab",
                    origin + "/indexInternal.es?action=internalStart");
            Path out = Path.of("local", "skylab-" + account + ".html");
            Files.writeString(out, r.body(), StandardCharsets.UTF_8);
            System.out.println("Skylab: HTTP " + r.status() + ", " + r.body().length() + " символов → " + out);
        }
    }
}
