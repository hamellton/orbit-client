package orbit.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import orbit.world.maps.MapCatalog;

/**
 * {@code configs} command: list, check and import configs; assign them to accounts.
 *
 * <pre>
 * configs list
 * configs show &lt;name&gt;
 * configs check &lt;name&gt;
 * configs import-darkbot &lt;DarkBot folder | profile.json&gt; [--overwrite] [--prefix PFX]
 * configs copy|rename &lt;from&gt; &lt;to&gt;
 * configs delete &lt;name&gt;
 * configs assign &lt;account&gt; &lt;config&gt;
 * configs accounts
 * </pre>
 */
public final class ConfigTool {

    private ConfigTool() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length == 0) {
            usage();
            return;
        }
        ConfigStore store = ConfigStore.openDefault();
        try {
            switch (args[0]) {
                case "list" -> list(store);
                case "show" -> System.out.println(Json.MAPPER.writeValueAsString(store.load(arg(args, 1))));
                case "check" -> check(store.load(arg(args, 1)));
                case "import-darkbot" -> importDarkBot(store, args);
                case "copy" -> store.copy(arg(args, 1), arg(args, 2));
                case "rename" -> store.rename(arg(args, 1), arg(args, 2));
                case "delete" -> store.delete(arg(args, 1));
                case "assign" -> assign(store, arg(args, 1), arg(args, 2));
                case "accounts" -> accounts();
                default -> usage();
            }
        } catch (IllegalArgumentException | IOException e) {
            System.err.println("Ошибка: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void list(ConfigStore store) throws IOException {
        List<String> names = store.list();
        if (names.isEmpty()) {
            System.out.println("Конфигов нет (" + store.dir() + ")");
        }
        MapCatalog maps = MapCatalog.bundled();
        for (String n : names) {
            BotConfig c = store.load(n);
            System.out.printf("%-24s %-12s %s%n", n, c.module(), maps.info(c.general().workingMap()).name());
        }
    }

    private static boolean check(BotConfig c) {
        List<ConfigCheck.Problem> problems = ConfigCheck.check(c, MapCatalog.bundled());
        problems.forEach(p -> System.out.println("  " + p));
        if (problems.isEmpty()) {
            System.out.println("  замечаний нет");
        }
        return !ConfigCheck.hasErrors(problems);
    }

    private static void importDarkBot(ConfigStore store, String[] args) throws IOException {
        Path source = Path.of(arg(args, 1));
        boolean overwrite = List.of(args).contains("--overwrite");
        String prefix = "";
        int p = List.of(args).indexOf("--prefix");
        if (p > 0) {
            prefix = arg(args, p + 1);
        }
        List<Path> files = Files.isDirectory(source) ? DarkBotImport.profiles(source) : List.of(source);
        for (Path f : files) {
            String name = prefix + DarkBotImport.nameOf(f);
            if (store.exists(name) && !overwrite) {
                System.out.println(name + ": уже есть, пропущен (--overwrite чтобы заменить)");
                continue;
            }
            DarkBotImport.Result r = DarkBotImport.read(f);
            store.save(name, r.config());
            System.out.println(name + ":");
            r.report().forEach(line -> System.out.println("  " + line));
            check(r.config());
        }
    }

    private static void assign(ConfigStore store, String account, String config) throws IOException {
        if (!store.exists(config)) {
            throw new IOException("Конфиг «" + config + "» не найден");
        }
        AccountSettings s = AccountSettings.load(AccountSettings.DEFAULT_FILE);
        AccountSettings.Entry e = s.account(account);
        s.with(account, new AccountSettings.Entry(config, e.schedule())).save(AccountSettings.DEFAULT_FILE);
        System.out.println(account + " → " + config);
    }

    private static void accounts() throws IOException {
        AccountSettings s = AccountSettings.load(AccountSettings.DEFAULT_FILE);
        LocalDateTime now = LocalDateTime.now();
        s.accounts().forEach((name, e) -> {
            Schedule.Decision d = e.decide(now, name.hashCode());
            String state = d.online() ? "сейчас: " + d.config() : "сейчас: перерыв";
            if (d.until() != null) {
                state += " до " + d.until().withNano(0);
            }
            System.out.printf("%-20s конфиг %-20s расписание %-5s %s%n", name, e.config(),
                    e.schedule().enabled() ? "вкл" : "выкл", state);
        });
    }

    private static String arg(String[] args, int i) {
        if (i >= args.length) {
            throw new IllegalArgumentException("не хватает аргументов");
        }
        return args[i];
    }

    private static void usage() {
        System.out.println("""
                configs list                         — конфиги в local/configs
                configs show <имя>                   — конфиг целиком (JSON)
                configs check <имя>                  — проверка перед запуском
                configs import-darkbot <папка|файл> [--overwrite] [--prefix П]
                                                     — импорт профилей DarkBot (credentials.json не читается)
                configs copy|rename <из> <в>
                configs delete <имя>
                configs assign <аккаунт> <конфиг>    — конфиг аккаунта (local/accounts.json)
                configs accounts                     — аккаунты, их конфиги и расписание""");
    }
}
