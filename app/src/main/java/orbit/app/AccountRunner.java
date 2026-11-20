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
 *
 * <p>The runners are a LoW leader's crew too (docs/LOW.md §11): a leader takes a stopped helper
 * account and starts it as his helper (an account at work is not taken: "already at work"); while held it cannot be
 * started by hand; the leader's stop stops his helpers as by hand; a helper stopped by hand is not taken again till
 * the leader starts anew.
 */
final class AccountRunner implements ManagedAccount {

    private static final Logger log = LoggerFactory.getLogger(AccountRunner.class);

    /** The window's runners by account name, for the LoW crew. */
    private static final java.util.Map<String, AccountRunner> ALL = new java.util.concurrent.ConcurrentHashMap<>();

    static {
        orbit.bot.mod.LowBoard.crew(new orbit.bot.mod.LowBoard.Crew() {
            @Override
            public String take(String leader, String helper) {
                return AccountRunner.take(leader, helper);
            }

            @Override
            public boolean holds(String leader, String helper) {
                AccountRunner h = find(helper);
                return h != null && h.phase != Phase.STOPPED && leader.equalsIgnoreCase(h.heldBy);
            }
        });
    }

    private static AccountRunner find(String name) {
        if (name == null) {
            return null;
        }
        AccountRunner r = ALL.get(name);
        if (r != null) {
            return r;
        }
        for (AccountRunner x : ALL.values()) {
            if (x.name.equalsIgnoreCase(name.strip())) {
                return x;
            }
        }
        return null;
    }

    private static String take(String leaderName, String helperName) {
        AccountRunner leader = find(leaderName);
        AccountRunner helper = find(helperName);
        if (helper == null) {
            return "такого аккаунта нет в программе";
        }
        if (leader == null || leader.phase != Phase.RUNNING) {
            return "лидер не запущен";
        }
        if (helper == leader) {
            return "это сам лидер";
        }
        if (leader.dropped.contains(helper.name)) {
            return "остановлен вручную — не беру до нового запуска лидера";
        }
        synchronized (helper) {
            if (helper.phase != Phase.STOPPED) {
                String by = helper.heldBy;
                return by != null && !by.equalsIgnoreCase(leader.name) ? "уже в работе (LoW для «" + by + "»)" : "уже в работе";
            }
            String config;
            try {
                config = AccountSettings.load(AccountSettings.DEFAULT_FILE).account(helper.name).config();
            } catch (IOException e) {
                return "не прочитать настройки: " + e.getMessage();
            }
            if (config == null) {
                config = leader.config(); // no config of its own: the leader's for repair, buying, the pet
            }
            if (config == null) {
                return "нет конфига";
            }
            // the login (proxy check, site) takes seconds: not on the leader's tick; a failure is in the helper's log
            String cfg = config;
            Thread.ofVirtual().name("crew-" + helper.name).start(() -> {
                MDC.put("account", helper.name);
                String why = helper.launch(0, leader.name, cfg);
                if (why != null) {
                    log.warn("Помощник LoW для «{}» не запущен: {}", leader.name, why);
                }
                MDC.remove("account");
            });
            return null;
        }
    }

    private final String name;
    private final Vault vault;
    private final ClientProfile profile;
    private final WorldFeed feed;
    private volatile Phase phase = Phase.STOPPED;
    private volatile String problem;
    private OrbitMain.Sessions sessions;
    /** The LoW crew leader this account runs for now ({@code null} = its own work). */
    private volatile String heldBy;
    /** A leader's helpers stopped by hand since his start: not taken again. */
    private final java.util.Set<String> dropped = java.util.concurrent.ConcurrentHashMap.newKeySet();

    AccountRunner(String name, Vault vault, ClientProfile profile) {
        this.name = name;
        this.vault = vault;
        this.profile = profile;
        this.feed = new WorldFeed("бот, " + name, name);
        feed.setState("Остановлен");
        ALL.put(name, this);
    }

    @Override
    public String heldBy() {
        return phase == Phase.STOPPED ? null : heldBy;
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
            return "«" + name + "» нет в хранилище";
        }
        AccountSettings.Entry settings;
        try {
            settings = AccountSettings.load(AccountSettings.DEFAULT_FILE).account(name);
        } catch (IOException e) {
            return "Не прочитать настройки: " + e.getMessage();
        }
        if (settings.config() == null && !(settings.schedule().enabled() && !settings.schedule().entries().isEmpty())) {
            return "Не выбран конфиг";
        }
        dropped.clear(); // a leader's new start: his helpers may be taken again
        return launch(delayMs, null, null);
    }

    /** Starts the sessions: its own plan, or for {@code crewLeader} as his LoW helper with {@code crewConfig}. */
    private synchronized String launch(long delayMs, String crewLeader, String crewConfig) {
        if (phase != Phase.STOPPED) {
            return "уже в работе";
        }
        if (vault.get(OrbitMain.loginOf(name)) == null) {
            return "«" + name + "» нет в хранилище";
        }
        heldBy = crewLeader;
        problem = null;
        feed.newSession(); // the last session's clock and results no longer count…
        feed.stopped(); // …and it is not in game until the new login
        feed.setState(delayMs > 0 ? "Очередь входа" : "Запуск");
        OrbitMain.Rig rig;
        MDC.put("account", name);
        try {
            rig = OrbitMain.rig(name, vault, feed);
            sessions = OrbitMain.sessions(OrbitMain.Options.parse(List.of()), name, rig, profile, delayMs, () -> { },
                    crewLeader, crewConfig);
        } catch (AuthException | IOException | RuntimeException e) {
            heldBy = null;
            problem = e.getMessage();
            log.error("Аккаунт «{}» не запущен: {}", name, e.getMessage());
            MDC.remove("account");
            return e.getMessage();
        }
        phase = Phase.RUNNING;
        log.info("Аккаунт «{}» запущен{}{}", name, crewLeader != null ? " помощником LoW для «" + crewLeader + "»" : "",
                delayMs > 0 ? " (вход через " + delayMs / 1_000 + " с)" : "");
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
                problem = feed.state();
            }
            feed.stopped();
            feed.setState("Остановлен");
            feed.setAction(null);
            heldBy = null;
            log.info("Аккаунт «{}» остановлен", name);
            releaseHelpers(); // a leader stopped by itself: his helpers too
        });
        return null;
    }

    /** A crew leader's helpers stopped as by hand (at a portal, revived per their configs). */
    private void releaseHelpers() {
        for (AccountRunner h : ALL.values()) {
            if (h != this && name.equalsIgnoreCase(h.heldBy) && h.phase == Phase.RUNNING) {
                log.info("Лидер LoW «{}» остановлен — останавливаю помощника «{}»", name, h.name);
                h.close();
            }
        }
    }

    /** By hand: a held helper is not taken again by its leader till he starts anew. */
    private void byHand() {
        String by = heldBy;
        AccountRunner leader = by == null ? null : find(by);
        if (leader != null) {
            leader.dropped.add(name);
        }
    }

    @Override
    public void stop() {
        byHand();
        close();
        releaseHelpers();
    }

    private void close() {
        Runnable close;
        synchronized (this) {
            if (phase != Phase.RUNNING) {
                return;
            }
            phase = Phase.STOPPING;
            close = sessions.close();
        }
        feed.setState("Выход у портала…");
        Thread.ofPlatform().name("stop-" + name).start(() -> {
            MDC.put("account", name);
            close.run();
        });
    }

    @Override
    public void stopNow() {
        byHand();
        releaseHelpers();
        Runnable kill;
        synchronized (this) {
            if (phase == Phase.STOPPED || sessions == null) {
                return;
            }
            phase = Phase.STOPPING;
            kill = sessions.kill();
        }
        feed.setState("Закрываю сразу…");
        Thread.ofPlatform().name("kill-" + name).start(() -> {
            MDC.put("account", name);
            kill.run();
        });
    }

    @Override
    public boolean inGame() {
        return phase == Phase.RUNNING && feed.sessionStartNanos() != 0 && !feed.loggedOut() && !feed.linkLost();
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
            all.with(name, all.account(name).withConfig(config)).save(file);
        } catch (IOException e) {
            MDC.put("account", name);
            log.warn("Не сохранить выбор конфига: {}", e.getMessage());
            MDC.remove("account");
        }
    }
}
