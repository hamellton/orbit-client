package orbit.auth;

import java.net.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Developer tool: performs a login and prints the resulting game session without secrets.
 *
 * <pre>
 *   ORBIT_DOSID=... auth-check --server int1        # log in with a dosid from the environment
 *   auth-check --creds creds.txt                     # username on line 1, password on line 2
 * </pre>
 * Secrets are never accepted as command-line arguments (they would end up in shell history).
 */
public final class AuthCheck {

    private AuthCheck() {
    }

    public static void main(String[] args) throws Exception {
        String server = "int1";
        Path creds = null;
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--server" -> server = args[++i];
                case "--creds" -> creds = Path.of(args[++i]);
                default -> {
                    System.err.println("Неизвестный параметр: " + args[i]);
                    System.exit(2);
                }
            }
        }
        AuthClient client = new AuthClient(new HttpSession("", Proxy.NO_PROXY, null), AuthClient.Endpoints.production());
        try {
            GameSession s;
            if (creds != null) {
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
        }
    }
}
