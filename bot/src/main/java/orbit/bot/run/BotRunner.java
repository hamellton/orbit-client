package orbit.bot.run;

import java.util.Comparator;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import orbit.bot.nav.Traveler;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runs the config's module: in order of precedence each tick —
 * <ol>
 *   <li>wait until in game;</li>
 *   <li>the kill screen and the pause after reviving ({@link Revive});</li>
 *   <li>stopping: to the nearest portal, log out ({@link #stop});</li>
 *   <li>safety: healing, repair at a portal, escape ({@link Safety});</li>
 *   <li>travel to the module's map;</li>
 *   <li>the module.</li>
 * </ol>
 * Call {@link #tick} about ten times a second from one thread.
 */
public final class BotRunner {

    private static final Logger log = LoggerFactory.getLogger(BotRunner.class);

    public enum State { STARTING, DEAD, REPAIRING, TRAVELLING, WORKING, STOPPING, STOPPED, FAILED }

    private final BotContext ctx;
    private final BooleanSupplier loggedOut;
    private final Consumer<String> statusSink;
    private final Safety safety;
    private final Revive revive;
    private BotModule module;
    private BotConfig.Module moduleKind;
    private State state = State.STARTING;
    private String status = "вход в игру";
    private String stopReason;
    private int travelTarget;
    private long logoutAt;
    private long logoutPressedAt;
    private String lastStatus = "";

    /**
     * @param loggedOut true once the server ended the session after a logout ({@code 0|l})
     * @param statusSink receives the status line when it changes (window, log)
     */
    public BotRunner(BotContext ctx, BooleanSupplier loggedOut, Consumer<String> statusSink) {
        this.ctx = ctx;
        this.loggedOut = loggedOut;
        this.statusSink = statusSink;
        this.safety = new Safety(ctx);
        this.revive = new Revive(ctx);
        loadModule();
    }

    public State state() {
        return state;
    }

    public String status() {
        return status;
    }

    public int deaths() {
        return revive.deaths();
    }

    /**
     * The config changed (switched or edited): a different main module replaces the running one; the
     * same module drops its plans so the new settings apply at once.
     */
    public void configChanged() {
        if (module != null) {
            module.pause();
        }
        if (ctx.config().get().module() != moduleKind) {
            ctx.nav().stop();
            loadModule();
        }
    }

    private void loadModule() {
        moduleKind = ctx.config().get().module();
        module = Modules.create(moduleKind, ctx);
        if (module == null) {
            log.error("Модуль {} пока не поддерживается", moduleKind);
        } else {
            log.info("Модуль: {}", moduleKind);
        }
    }

    /** Finish: leave the fight, fly to the nearest portal and log out. */
    public void stop(String reason) {
        if (state == State.STOPPING || state == State.STOPPED) {
            return;
        }
        stopReason = reason;
        log.info("Остановка: {}", reason);
        if (module != null) {
            module.pause();
        }
        ctx.nav().stop();
        state = State.STOPPING;
        logoutAt = 0;
    }

    public void tick() {
        if (state == State.STOPPED || state == State.FAILED) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0) {
            show("вход в игру");
            return;
        }
        if (revive.tick()) {
            if (module != null) {
                module.pause();
            }
            if (revive.limitReached()) {
                fail("смертей " + revive.deaths() + " — достигнут лимит из конфига");
                return;
            }
            if (state != State.STOPPING) {
                state = State.DEAD;
            }
            show(w.death() != null ? "уничтожен, жду ремонта" : "возрождение");
            return;
        }
        if (module != null) {
            module.observe();
        }
        if (state == State.STOPPING) {
            stopping(w);
            return;
        }
        if (safety.tick(module != null && module.busy())) {
            if (module != null) {
                module.pause();
            }
            state = State.REPAIRING;
            show("ремонт (HP " + Safety.pct(h.health().hpPercent()) + "%)");
            return;
        }
        if (module == null) {
            fail("модуль " + moduleKind + " пока не поддерживается");
            return;
        }
        int map = module.workingMap();
        if (map != 0 && h.mapId() != map) {
            travel(map);
            return;
        }
        travelTarget = 0;
        state = State.WORKING;
        module.tick();
        show(module.status());
    }

    private void travel(int map) {
        Traveler t = ctx.traveler();
        if (travelTarget != map || t.state() != Traveler.State.TRAVELLING) {
            if (travelTarget == map && t.state() == Traveler.State.FAILED) {
                fail("не могу долететь до " + ctx.maps().info(map).name() + ": " + t.failure());
                return;
            }
            module.pause();
            travelTarget = map;
            log.info("Лечу на рабочую карту {}", ctx.maps().info(map).name());
            t.travel(map, Double.NaN, Double.NaN);
        }
        state = State.TRAVELLING;
        ctx.ship().want(ctx.config().get().general().travel());
        t.tick();
        show("перелёт на " + ctx.maps().info(map).name());
    }

    /** To the nearest portal (out of the crowd), wait a moment, press logout, keep still until the server ends it. */
    private void stopping(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        long now = ctx.now();
        if (loggedOut.getAsBoolean()) {
            state = State.STOPPED;
            show("вышел из игры: " + stopReason);
            log.info("Вышел из игры");
            return;
        }
        if (safety.tick(false)) {
            show("ремонт перед выходом");
            logoutAt = 0;
            return;
        }
        MapCatalog.Portal p = ctx.maps().portals(h.mapId()).stream()
                .filter(x -> x.faction() == 0 || x.faction() == h.factionId())
                .min(Comparator.comparingDouble(x -> Math.hypot(x.x() - h.x(), x.y() - h.y()))).orElse(null);
        if (p != null && Math.hypot(p.x() - h.x(), p.y() - h.y()) > Safety.AT_PORTAL) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != p.x()) {
                ctx.nav().moveTo(p.x(), p.y(), true);
            }
            ctx.nav().tick();
            logoutAt = 0;
            show("лечу к порталу, чтобы выйти");
            return;
        }
        ctx.nav().stop();
        if (logoutAt == 0) {
            logoutAt = now + ctx.pause(1_000, 3_000);
        }
        if (now >= logoutAt && !h.moving() && now - logoutPressedAt > 30_000_000_000L) {
            log.info("Выход из игры");
            ctx.actions().logout();
            logoutPressedAt = now;
        }
        show("выход из игры");
    }

    private void fail(String why) {
        log.error("Бот остановлен: {}", why);
        state = State.FAILED;
        stopReason = why;
        ctx.nav().stop();
        show("остановлен: " + why);
    }

    private void show(String s) {
        status = s;
        if (!s.equals(lastStatus)) {
            lastStatus = s;
            statusSink.accept(s);
        }
    }
}
