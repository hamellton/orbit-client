package orbit.bot.run;

import java.util.List;
import orbit.config.BotConfig;
import orbit.config.ConfigCheck;
import orbit.config.ConfigStore;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The running bot's config, picked up again when its file changes (saved in the config window). A
 * changed config with errors is not taken: the bot keeps the last good one and says why.
 */
public final class ConfigWatch {

    private static final Logger log = LoggerFactory.getLogger(ConfigWatch.class);

    private final ConfigStore store;
    private volatile String name;
    private volatile String pending;
    private final MapCatalog maps;
    private volatile BotConfig current;
    private long modified;

    public ConfigWatch(ConfigStore store, String name, BotConfig initial, MapCatalog maps) {
        this.store = store;
        this.name = name;
        this.maps = maps;
        this.current = initial;
        this.modified = store.modified(name);
    }

    public BotConfig current() {
        return current;
    }

    public String name() {
        return name;
    }

    /** Run another config from the next poll on (chosen in the bot window). */
    public void switchTo(String other) {
        pending = other;
    }

    /** Re-reads the file if it changed, or takes the config asked for; true when a new config was taken. */
    public boolean poll() {
        String other = pending;
        if (other != null) {
            pending = null;
            if (!other.equals(name)) {
                return take(other);
            }
        }
        long m = store.modified(name);
        if (m == 0 || m == modified) {
            return false;
        }
        modified = m;
        BotConfig c;
        try {
            c = store.load(name);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Конфиг «{}» изменён, но не читается: {} — работаю по прежнему", name, e.getMessage());
            return false;
        }
        if (c.equals(current)) {
            return false;
        }
        List<ConfigCheck.Problem> problems = ConfigCheck.check(c, maps);
        if (ConfigCheck.hasErrors(problems)) {
            problems.forEach(p -> log.warn("Конфиг «{}»: {}", name, p));
            log.warn("Изменения конфига «{}» с ошибками — работаю по прежнему", name);
            return false;
        }
        current = c;
        log.info("Конфиг «{}» обновлён", name);
        return true;
    }

    private boolean take(String other) {
        BotConfig c;
        try {
            c = store.load(other);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Конфиг «{}» не читается: {} — остаюсь на «{}»", other, e.getMessage(), name);
            return false;
        }
        List<ConfigCheck.Problem> problems = ConfigCheck.check(c, maps);
        if (ConfigCheck.hasErrors(problems)) {
            problems.forEach(p -> log.warn("Конфиг «{}»: {}", other, p));
            log.warn("Конфиг «{}» с ошибками — остаюсь на «{}»", other, name);
            return false;
        }
        log.info("Конфиг сменён: «{}» → «{}»", name, other);
        name = other;
        modified = store.modified(other);
        current = c;
        return true;
    }
}
