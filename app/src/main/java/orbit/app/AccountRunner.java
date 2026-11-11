package orbit.app;

import java.io.IOException;
import java.util.List;
import orbit.app.gui.ManagedAccount;
import orbit.auth.AuthException;
import orbit.config.AccountSettings;
import orbit.game.ClientProfile;
import orbit.secrets.Vault;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

/**
 * One account in the manager window: each start builds a fresh web login, proxy route and sessions
 * (so edits of the login or proxy apply), the feed stays — the tab and the log keep going.
 */
final class AccountRunner implements ManagedAccount {

    private static final Logger log = LoggerFactory.getLogger(AccountRunner.class);

    private final String name;
    private final Vault vault;
    private final ClientProfile profile;
    private final WorldFeed feed;
    private volatile Phase phase = Phase.STOPPED;
    private volatile String problem;
    private OrbitMain.Sessions sessions;

    AccountRunner(String name, Vault vault, ClientProfile profile) {
        this.name = name;
        this.vault = vault;
        this.profile = profile;
        this.feed = new WorldFeed("бот, " + name, name);
        feed.setState("остановлен");
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public WorldFeed feed() {
        return feed;
    }

    @Override
    public Phase phase() {
        return phase;
    }

    @Override
    public String problem() {
        return problem;
    }

    @Override
    public synchronized String start(long delayMs) {
        if (phase != Phase.STOPPED) {
            return null;
        }
        if (vault.get(OrbitMain.loginOf(name)) == null) {
            return "аккаунта «" + name + "» нет в хранилище";
        }
        AccountSettings.Entry settings;
        try {
            settings = AccountSettings.load(AccountSettings.DEFAULT_FILE).account(name);
        } catch (IOException e) {
            return "не прочитать настройки аккаунтов: " + e.getMessage();
        }
        if (settings.config() == null && !(settings.schedule().enabled() && !settings.schedule().entries().isEmpty())) {
            return "не выбран конфиг — выберите его в «Изменить…» или задайте расписание";
        }
        problem = null;
        feed.newSession(); // the last session's clock and results no longer count…
        feed.stopped(); // …and it is not in game until the new login
        feed.setState(delayMs > 0 ? "очередь входа" : "запуск");
        OrbitMain.Rig rig;
        MDC.put("account", name);
        try {
            rig = OrbitMain.rig(name, vault, feed);
            sessions = OrbitMain.sessions(OrbitMain.Options.parse(List.of()), name, rig, profile, delayMs, () -> { });
        } catch (AuthException | IOException | RuntimeException e) {
            problem = e.getMessage();
            log.error("Аккаунт «{}» не запущен: {}", name, e.getMessage());
            MDC.remove("account");
            return e.getMessage();
        }
        phase = Phase.RUNNING;
        log.info("Аккаунт «{}» запущен{}", name, delayMs > 0 ? " (вход через " + delayMs / 1_000 + " с)" : "");
        MDC.remove("account");
        Thread thread = sessions.thread();
        Thread.ofVirtual().name("watch-" + name).start(() -> {
            MDC.put("account", name); // its lines belong to this account's log
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            rig.route().close();
            boolean byItself;
            synchronized (this) {
                byItself = phase == Phase.RUNNING;
                phase = Phase.STOPPED;
                sessions = null;
            }
            if (byItself) {
                problem = "остановился сам: " + feed.state();
            }
            feed.stopped();
            feed.setState("остановлен");
            feed.setAction(null);
            log.info("Аккаунт «{}» остановлен", name);
        });
        return null;
    }

    @Override
    public void stop() {
        Runnable close;
        synchronized (this) {
            if (phase != Phase.RUNNING) {
                return;
            }
            phase = Phase.STOPPING;
            close = sessions.close();
        }
        feed.setState("выход из игры…");
        Thread.ofPlatform().name("stop-" + name).start(() -> {
            MDC.put("account", name);
            close.run();
        });
    }

    @Override
    public String config() {
        OrbitMain.Sessions s = sessions;
        if (s != null) {
            return s.config().get();
        }
        try {
            return AccountSettings.load(AccountSettings.DEFAULT_FILE).account(name).config();
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    public void switchConfig(String config) {
        OrbitMain.Sessions s = sessions;
        if (s != null) {
            s.switchConfig().accept(config);
            return;
        }
        try {
            var file = AccountSettings.DEFAULT_FILE;
            var all = AccountSettings.load(file);
            all.with(name, new AccountSettings.Entry(config, all.account(name).schedule())).save(file);
        } catch (IOException e) {
            MDC.put("account", name);
            log.warn("Не сохранить выбор конфига: {}", e.getMessage());
            MDC.remove("account");
        }
    }
}
