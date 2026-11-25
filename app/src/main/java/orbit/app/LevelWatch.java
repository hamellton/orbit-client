package orbit.app;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import orbit.auth.AuthClient;
import orbit.bot.level.AccountState;
import orbit.bot.level.Advisor;
import orbit.bot.level.LevelPlan;
import orbit.bot.level.SiteHangars;
import orbit.config.ConfigStore;
import orbit.game.GameConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The leveling mode's advisor in the running app (docs/LEVELING.md): while the account plays a config of the folder
 * {@link ConfigStore#LEVELING_FOLDER}, it reads the account from the website and the game now and then and writes the
 * state, the goal and what it would buy to the leveling log ({@code orbit.level} → {@code logs/leveling-<account>.log},
 * never the window). It buys, fits and switches nothing.
 */
final class LevelWatch {

    /** The leveling log: its own file per account, kept out of the window and the journal (logback-app.xml). */
    static final Logger LOG = LoggerFactory.getLogger("orbit.level");

    static final long FIRST_MS = 90_000;
    static final long EVERY_MS = 30 * 60_000L;
    /** Between two calls of the site's inventory: it refuses one right after another (live 2026-11-14). */
    static final long GAP_MS = 4_000;

    private LevelWatch() {
    }

    /** Starts the advisor's thread for this game connection; it ends with the game thread. */
    static void start(AuthClient auth, GameConnection conn, WorldFeed feed, String account, Supplier<String> config,
                      BooleanSupplier alive) {
        java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("leveling").start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            java.util.Random random = new java.util.Random();
            boolean shapeLogged = false;
            try {
                Thread.sleep(FIRST_MS + random.nextInt(60_000));
                LevelPlan plan = LevelPlan.bundled();
                while (alive.getAsBoolean()) {
                    String cfg = config.get();
                    if (cfg != null && ConfigStore.LEVELING_FOLDER.equals(ConfigStore.openDefault().folderOf(cfg))) {
                        shapeLogged |= round(auth, conn, feed, account, cfg, plan, !shapeLogged);
                    }
                    Thread.sleep(EVERY_MS + random.nextInt(120_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LOG.error("советчик остановлен: {}", e.toString(), e);
            }
        });
    }

    /** One look: the site's list and active hangar, the game's level and money, the advice. True when the shape was logged. */
    private static boolean round(AuthClient auth, GameConnection conn, WorldFeed feed, String account, String cfg,
                                 LevelPlan plan, boolean logShape) throws InterruptedException {
        var session = conn.session();
        if (session == null || session.host() == null) {
            LOG.info("советчик: нет игровой сессии — пропускаю");
            return false;
        }
        orbit.auth.Hangars site = new orbit.auth.Hangars(auth.http(), session.host());
        SiteHangars.ListInfo list;
        SiteHangars.HangarInfo hangar = null;
        try {
            list = SiteHangars.list(site.listJson());
            if (list.activeHangar() != 0) {
                Thread.sleep(GAP_MS);
                hangar = SiteHangars.hangar(site.hangarJson(list.activeHangar()), list.activeHangar());
            }
        } catch (java.io.IOException | RuntimeException e) {
            LOG.warn("советчик: сайт не прочитан ({}) — повторю в следующий раз", e.getMessage());
            return false;
        }
        int level = 0;
        long credits = -1;
        long uridium = -1;
        var snap = feed.snapshot();
        if (snap != null && snap.hero() != null) {
            level = snap.hero().level();
            credits = (long) snap.hero().credits();
            uridium = (long) snap.hero().uridium();
        }
        AccountState state = SiteHangars.state(list, hangar, level, credits, uridium);
        Advisor.Advice advice = Advisor.advise(state, plan);
        LOG.info("снимок [{}, конфиг «{}», план v{}]: {}", account, cfg, plan.version(), state.describe());
        LOG.info("совет: выполнено {}; {}", advice.done(), advice.text());
        if (advice.purchase() != null) {
            LOG.info("купил бы (режим советчика — не покупаю): {} × {} = {} {}", advice.purchase().amount(),
                    advice.purchase().item(), advice.purchase().total(), advice.purchase().uridium() ? "уридия" : "кредитов");
        }
        if (logShape && hangar != null) {
            LOG.info("поля getHangar по конфигам (без значений): {}", hangar.shape());
            return true;
        }
        return false;
    }
}
