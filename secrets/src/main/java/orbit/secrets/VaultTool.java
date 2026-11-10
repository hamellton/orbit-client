package orbit.secrets;

import java.io.Console;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Command-line management of the account vault. Secrets are typed into the console or read
 * from a file, never passed as arguments (they would end up in shell history).
 *
 * <pre>
 *   vault list
 *   vault add &lt;name&gt; [--server int1] [--proxy socks5://host:port]
 *   vault import &lt;name&gt; &lt;creds.txt&gt; [--server int1]     # username on line 1, password on line 2, optional dosid on line 3
 *   vault set-dosid &lt;name&gt;
 *   vault set-server &lt;name&gt; &lt;server&gt;   # the default server, e.g. ru5 (others: orbit bot --account name@ru5)
 *   vault set-dosid &lt;name&gt; --server ru5   # the website session of one server of the login
 *   vault remove &lt;name&gt;
 *   options: --vault local/vault.bin
 * </pre>
 */
public final class VaultTool {

    public static final Path DEFAULT_FILE = Path.of("local", "vault.bin");

    private VaultTool() {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (VaultException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    static int run(String[] args) throws VaultException {
        Path file = DEFAULT_FILE;
        String server = "int1";
        boolean serverGiven = false;
        String proxy = null;
        List<String> positional = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "--vault" -> file = Path.of(args[++i]);
                case "--server" -> {
                    server = args[++i];
                    serverGiven = true;
                }
                case "--proxy" -> proxy = args[++i];
                default -> positional.add(args[i]);
            }
        }
        if (positional.isEmpty()) {
            System.err.println("Команды: list, add <имя>, import <имя> <файл>, set-dosid <имя>, set-server <имя> <сервер>, remove <имя>");
            return 2;
        }
        String command = positional.get(0);
        boolean creating = !Files.exists(file);
        if (creating && !command.equals("add") && !command.equals("import")) {
            System.out.println("Хранилище " + file + " ещё не создано. Добавьте аккаунт: vault add <имя>");
            return 1;
        }
        char[] master = MasterPassword.obtain(creating);
        Vault vault;
        try {
            vault = Vault.open(file, master);
        } finally {
            Arrays.fill(master, '\0');
        }

        switch (command) {
            case "list" -> {
                if (vault.accounts().isEmpty()) {
                    System.out.println("Аккаунтов нет.");
                }
                for (Map.Entry<String, Account> e : vault.accounts().entrySet()) {
                    System.out.println(e.getKey() + ": " + e.getValue());
                }
                return 0;
            }
            case "add" -> {
                String name = arg(positional, 1);
                Console c = console();
                String username = c.readLine("Логин: ").strip();
                String password = new String(c.readPassword("Пароль (Enter — только dosid): "));
                String dosid = new String(c.readPassword("dosid (Enter — пропустить): ")).strip();
                vault.put(name, new Account(username, password.isEmpty() ? null : password, server,
                        dosid.isEmpty() ? null : dosid, proxy));
            }
            case "import" -> {
                String name = arg(positional, 1);
                Path creds = Path.of(arg(positional, 2));
                List<String> lines;
                try {
                    lines = Files.readAllLines(creds);
                } catch (IOException e) {
                    throw new VaultException("Не удалось прочитать " + creds + ": " + e.getMessage(), e);
                }
                if (lines.size() < 2 || lines.get(0).isBlank() || lines.get(1).isBlank()) {
                    throw new VaultException("В " + creds + " нужны логин (строка 1) и пароль (строка 2).");
                }
                String dosid = lines.size() > 2 ? dosidOf(lines.get(2)) : null;
                vault.put(name, new Account(lines.get(0).strip(), lines.get(1).strip(), server, dosid, proxy));
                System.out.println("Импортировано" + (dosid != null ? " (с dosid)" : "") + ". Файл " + creds
                        + " теперь можно удалить.");
            }
            case "set-dosid" -> {
                String name = arg(positional, 1);
                Account a = existing(vault, name);
                String dosid = new String(console().readPassword("dosid: ")).strip();
                // with --server: the session of that server of the login (each server has its own)
                vault.put(name, serverGiven ? a.withDosidFor(server, dosid.isEmpty() ? null : dosid)
                        : a.withDosid(dosid.isEmpty() ? null : dosid));
            }
            case "set-server" -> {
                String name = arg(positional, 1);
                String to = arg(positional, 2).strip().toLowerCase(java.util.Locale.ROOT);
                if (!to.matches("[a-z]+\\d+")) {
                    throw new VaultException("Код сервера вида int1, ru5: " + to);
                }
                vault.put(name, existing(vault, name).withServer(to));
                System.out.println("Аккаунт «" + name + "»: сервер по умолчанию " + to
                        + " (сохранённые сессии других серверов остаются; другой сервер — --account " + name + "@ru5).");
            }
            case "remove" -> {
                String name = arg(positional, 1);
                if (!vault.remove(name)) {
                    throw new VaultException("Аккаунт «" + name + "» не найден.");
                }
            }
            default -> {
                System.err.println("Неизвестная команда: " + command);
                return 2;
            }
        }
        vault.save();
        System.out.println("Сохранено в " + vault.file());
        return 0;
    }

    private static Account existing(Vault vault, String name) throws VaultException {
        Account a = vault.get(name);
        if (a == null) {
            throw new VaultException("Аккаунт «" + name + "» не найден.");
        }
        return a;
    }

    private static String arg(List<String> positional, int i) throws VaultException {
        if (positional.size() <= i) {
            throw new VaultException("Не хватает параметра команды " + positional.get(0) + ".");
        }
        return positional.get(i);
    }

    private static Console console() throws VaultException {
        Console c = System.console();
        if (c == null) {
            throw new VaultException("Для ввода нужна консоль: запустите из терминала.");
        }
        return c;
    }

    /** Line 3 of a credentials file: {@code dosid}, {@code dosid: X}, {@code dosid=X}; {@code null} if empty. */
    static String dosidOf(String line) {
        String v = line.strip();
        int cut = Math.max(v.lastIndexOf(' '), Math.max(v.lastIndexOf(':'), v.lastIndexOf('=')));
        v = v.substring(cut + 1).strip();
        return v.isEmpty() || v.equalsIgnoreCase("dosid") ? null : v;
    }

}
