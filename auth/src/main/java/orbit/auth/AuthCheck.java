package orbit.auth;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import orbit.secrets.MasterPassword;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;
import orbit.secrets.VaultTool;

/**
 * Developer tool: performs a login and prints the resulting game session without secrets.
 *
 * <pre>
 *   ORBIT_DOSID=... auth-check --server int1        # log in with a dosid from the environment
 *   auth-check --creds creds.txt                     # username on line 1, password on line 2
 *   auth-check --account main                        # from local/vault.bin (master password asked)
 * </pre>
 * Secrets are never accepted as command-line arguments (they would end up in shell history).
 */
public final class AuthCheck {

    private AuthCheck() {
    }

    public static void main(String[] args) throws Exception {
        String server = "int1";
        Path creds = null;
        String account = null;
        Path vaultFile = VaultTool.DEFAULT_FILE;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = args[++i];
                case "--creds" -> creds = Path.of(args[++i]);
                case "--account" -> account = args[++i];
                case "--vault" -> vaultFile = Path.of(args[++i]);
                default -> {
                    System.err.println("Неизвестный параметр: " + args[i]);
                    System.exit(2);
                }
            }
        }
        try {
            Vault vault = null;
            orbit.auth.proxy.ProxyRoute route = orbit.auth.proxy.ProxyRoute.none();
            if (account != null) {
                char[] master = MasterPassword.obtain(false);
                try {
                    vault = Vault.open(vaultFile, master);
                } finally {
                    Arrays.fill(master, '\0');
                }
                if (vault.get(account) != null) {
                    route = orbit.auth.proxy.ProxyRoute.of(vault.get(account).proxySpec());
                }
            }
            AuthClient client = new AuthClient(new HttpSession("", route.httpProxy()), AuthClient.Endpoints.production());
            GameSession s;
            if (account != null) {
                route.verify();
                s = new AccountLogin(client, vault, account).login();
            } else if (creds != null) {
                List<String> lines = Files.readAllLines(creds);
                s = client.loginWithPassword(lines.get(0).strip(), lines.get(1).strip());
            } else {
                String dosid = System.getenv("ORBIT_DOSID");
                if (dosid == null || dosid.isBlank()) {
                    System.err.println("Задайте ORBIT_DOSID или --creds");
                    System.exit(2);
                }
                s = client.loginWithDosid(server, dosid.strip());
            }
            System.out.println("OK: " + s);
        } catch (AuthException e) {
            System.out.println("Ошибка входа [" + e.reason() + "]: " + e.getMessage());
            System.exit(1);
        } catch (VaultException e) {
            System.out.println("Ошибка хранилища: " + e.getMessage());
            System.exit(1);
        }
    }
}
