package orbit.bot.run;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import orbit.config.ItemCatalog;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A new cloaking CPU when the fitted one has no charges left (the user, 2026-11-14: «крафт невидов и установка их на акк,
 * если закончились — когда есть свободное окно: полёт на свою базу на x-8, установка нового процессора и продолжение — как
 * в даркботе в CARNAGE»; CARNAGE's BLCycle log: «cloak craft to base» 2-BL → 2-8 and back in ~1.5 min). In a free
 * window ({@link BotModule#freeFor}) the ship flies to its company's x-8, into the base, and there:
 * <ol>
 *   <li>asks the site for the account's CL04K-XL CPUs ({@link HangarAccess#cpus});</li>
 *   <li>no spare with charges and {@link CloakPlan#craft} — crafts one in the assembly
 *       ({@code recipe_blueprint-cloakcpu-blacklight}, as {@link CraftWatch}) and asks again;</li>
 *   <li>fits the spare with the most charges into each config that had a cloak (both when none had), the empty one
 *       taken out first — the inventory page's own move ({@link HangarAccess#move});</li>
 *   <li>waits for the game's item bar to count the charges; no count in {@link #GAME_MS} — logs in again
 *       ({@code refresh}), as the game reads the equipment at a login.</li>
 * </ol>
 * Then the module takes the ship back. A failed trip is not tried again for {@link #RETRY_MS}.
 */
final class CloakCare {

    private static final Logger log = LoggerFactory.getLogger(CloakCare.class);

    static final String CLOAK = "equipment_extra_cpu_cl04k-xl";
    static final String RECIPE = "recipe_blueprint-cloakcpu-blacklight";
    /** The free window the trip needs: there, the base, back (BLCycle: ~1.5 min from 2-BL). */
    static final long WINDOW_MS = 4 * 60_000L;
    /** Nothing before the game has sent its item bar for a while after a login. */
    static final long SETTLE_MS = 30_000;
    static final long RETRY_MS = 15 * 60_000L;
    /** A trip longer than this is given up (the module's work comes first). */
    static final long TRIP_MS = 10 * 60_000L;
    /** In the base this long without the station flag: on anyway (the site says if it minds). */
    static final long STATION_MS = 15_000;
    /** A started build still not seen building after this: it did not start. */
    static final long BUILD_START_MS = 20_000;
    static final long BUILD_MS = 5 * 60_000L;
    /** The game counts the new CPU's charges within this, else a new login. */
    static final long GAME_MS = 20_000;
    static final long AFTER_REFRESH_MS = 120_000;

    private enum Step { TO_BASE, TO_STATION, ASK, CRAFT, FIT, GAME }

    private final BotContext ctx;
    private volatile HangarAccess hangar;
    private volatile Runnable refresh;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "hangar");
        t.setDaemon(true);
        return t;
    });
    private final java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();

    private Step step;
    private long tripAt;
    private long since;
    private long failedAt = Long.MIN_VALUE / 2;
    private final long startedAt;
    private int baseMap;
    private boolean crafted;
    private boolean buildAsked;
    private boolean collectAsked;
    private boolean refreshed;
    private int charges;
    /** The site's answer, set by the worker: the CPUs, or the failure. */
    private volatile List<HangarAccess.Cpu> answer;
    private volatile String error;
    private volatile boolean working;
    private String told = "";
    /** The plan the trip started with (the config may change under way). */
    private CloakPlan currentPlan;

    CloakCare(BotContext ctx) {
        this.ctx = ctx;
        this.startedAt = ctx.now();
    }

    void setHangar(HangarAccess hangar) {
        this.hangar = hangar;
    }

    void setRefresh(Runnable refresh) {
        this.refresh = refresh;
    }

    /** Busy with a trip (the runner shows it). */
    boolean active() {
        return step != null;
    }

    /** No charges left in any cloaking CPU, by the item bar; false while the item bar is not there. */
    static boolean empty(WorldSnapshot w) {
        if (w.items().isEmpty()) {
            return false;
        }
        for (String id : ItemCatalog.CLOAKS) {
            WorldSnapshot.Item i = w.items().get(id);
            if (i != null && i.counter() > 0) {
                return false;
            }
        }
        return true;
    }

    /**
     * One step; true while it flies the ship or waits in the base.
     *
     * @param free the module has a free window of {@link #WINDOW_MS}
     */
    boolean tick(boolean busy, boolean free, CloakPlan plan) {
        WorldSnapshot w = ctx.world().get();
        long now = ctx.now();
        if (step == null) {
            if (plan == null || hangar == null || busy || !free || w.hero().id() == 0 || w.death() != null
                    || w.jumpingTo() != 0 // mid-jump the map is about to change (live 2026-11-14: decided on 2-8 as it left)
                    || ms(now - startedAt) < SETTLE_MS || ms(now - failedAt) < RETRY_MS || !empty(w)) {
                if (plan != null && hangar == null && empty(w)) {
                    tell("Маскировка кончилась, а сайт аккаунта недоступен — новый процессор не поставить");
                }
                return false;
            }
            baseMap = homeBase(w.hero());
            if (baseMap == 0) {
                return fail("нет пути на свою x-8");
            }
            log.info("Маскировка кончилась — лечу на {} за новым процессором (окно до бегемота есть)",
                    ctx.maps().info(baseMap).name());
            step = Step.TO_BASE;
            tripAt = now;
            since = now;
            crafted = false;
            refreshed = false;
            currentPlan = plan;
            ctx.traveler().travel(baseMap, Double.NaN, Double.NaN);
        }
        if (ms(now - tripAt) > TRIP_MS) {
            return fail("за " + TRIP_MS / 60_000 + " мин не вышло");
        }
        return switch (step) {
            case TO_BASE -> toBase(w, now);
            case TO_STATION -> toStation(w, now);
            case ASK -> ask(now);
            case CRAFT -> craft(w, now);
            case FIT -> fit(now);
            case GAME -> game(w, now);
        };
    }

    private boolean toBase(WorldSnapshot w, long now) {
        if (w.hero().mapId() != baseMap) {
            orbit.bot.nav.Traveler.State s = ctx.traveler().state();
            if (s == orbit.bot.nav.Traveler.State.FAILED) {
                return fail("нет пути на " + ctx.maps().info(baseMap).name());
            }
            if (s != orbit.bot.nav.Traveler.State.TRAVELLING) {
                ctx.traveler().travel(baseMap, Double.NaN, Double.NaN); // after a revive or a pause
            }
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.traveler().tick();
            return true;
        }
        go(Step.TO_STATION, now);
        return true;
    }

    private boolean toStation(WorldSnapshot w, long now) {
        WorldSnapshot.Hero h = w.hero();
        if (h.mapId() != baseMap) {
            // left the base map after all (a jump under way, a death): fly back first
            ctx.traveler().travel(baseMap, Double.NaN, Double.NaN);
            go(Step.TO_BASE, now);
            return true;
        }
        SafePlaces.Place base = SafePlaces.bases(w).stream().min(Comparator.comparingDouble(p -> p.distance(h))).orElse(null);
        if (base == null) {
            return ms(now - since) > 10_000 ? fail("на " + ctx.maps().info(h.mapId()).name() + " не видно своей базы") : true;
        }
        if (base.distance(h) > base.reach()) {
            if (ctx.nav().idle() || ctx.nav().destination().x() != base.x()) {
                ctx.nav().moveTo(base.x(), base.y(), true);
            }
            ctx.ship().want(ctx.config().get().general().travel());
            ctx.nav().tick();
            since = now;
            return true;
        }
        ctx.nav().stop();
        if (!h.beacon().inStation() && ms(now - since) < STATION_MS) {
            return true;
        }
        log.info("На базе — смотрю на сайте запасные процессоры маскировки");
        askSite(now);
        return true;
    }

    private void askSite(long now) {
        answer = null;
        error = null;
        HangarAccess site = hangar;
        go(Step.ASK, now);
        run(() -> answer = site.cpus(CLOAK));
    }

    private boolean ask(long now) {
        if (working) {
            return true;
        }
        if (error != null) {
            return fail("сайт: " + error);
        }
        List<HangarAccess.Cpu> cpus = answer;
        if (cpus == null) {
            return fail("сайт не ответил");
        }
        HangarAccess.Cpu spare = best(cpus);
        if (spare != null) {
            log.info("Запасной процессор маскировки: {} зарядов — ставлю", spare.charges());
            fitAll(cpus, spare, now);
            return true;
        }
        CloakPlan plan = currentPlan;
        if (crafted || plan == null || !plan.craft()) {
            return fail(crafted ? "после крафта запасного процессора на сайте нет" : "запасного процессора нет, крафт выключен");
        }
        log.info("Запасного процессора нет — крафчу {} шт.", plan.amount());
        buildAsked = false;
        collectAsked = false;
        go(Step.CRAFT, now);
        return true;
    }

    private boolean craft(WorldSnapshot w, long now) {
        WorldSnapshot.Recipe r = w.inventory().recipes().get(RECIPE);
        if (r == null) {
            return ms(now - since) > 10_000 ? fail("рецепта процессора маскировки нет в сборке игры") : true;
        }
        switch (r.state()) {
            case AVAILABLE -> {
                if (!buildAsked) {
                    buildAsked = true;
                    int n = currentPlan == null ? 1 : currentPlan.amount();
                    ctx.actions().assemblyWindow();
                    ctx.actions().assemblyBuild(RECIPE, n);
                    ctx.actions().assemblyWindow();
                    since = now;
                } else if (collectAsked) {
                    // collected: the new CPU is in the inventory a moment later
                    crafted = true;
                    if (ms(now - since) > 3_000) {
                        log.info("Скрафтил — смотрю на сайте ещё раз");
                        askSite(now);
                    }
                } else if (ms(now - since) > BUILD_START_MS) {
                    return fail("сборка не началась (не хватает ресурсов?)");
                }
            }
            case BUILDING -> {
                buildAsked = true;
                if (ms(now - since) > BUILD_MS) {
                    return fail("сборка идёт дольше " + BUILD_MS / 60_000 + " мин");
                }
            }
            case READY -> {
                if (!collectAsked || ms(now - since) > 30_000) {
                    collectAsked = true;
                    since = now;
                    ctx.actions().assemblyWindow();
                    ctx.actions().assemblyCollect(RECIPE);
                    ctx.actions().assemblyWindow();
                }
            }
            case UNAVAILABLE -> {
                return fail(r.unavailable() == 0 ? "процессоров маскировки уже максимум" : "рецепт процессора исчерпан");
            }
            default -> {
                return ms(now - since) > 10_000 ? fail("рецепт процессора в непонятном состоянии") : true;
            }
        }
        return true;
    }

    /** A spare with the most charges, none when no spare has any. */
    static HangarAccess.Cpu best(List<HangarAccess.Cpu> cpus) {
        return cpus.stream().filter(c -> c.spare() && c.charges() != 0)
                .max(Comparator.comparingInt(HangarAccess.Cpu::charges)).orElse(null);
    }

    /** The configs to fit: those whose ship had a cloak, both when none had. */
    static Set<Integer> configs(List<HangarAccess.Cpu> cpus) {
        Set<Integer> out = new java.util.TreeSet<>();
        cpus.forEach(c -> out.addAll(c.configs()));
        return out.isEmpty() ? Set.of(1, 2) : out;
    }

    private void fitAll(List<HangarAccess.Cpu> cpus, HangarAccess.Cpu spare, long now) {
        HangarAccess site = hangar;
        Set<Integer> configs = configs(cpus);
        charges = spare.charges();
        error = null;
        answer = null;
        go(Step.FIT, now);
        run(() -> {
            for (int config : configs) {
                for (HangarAccess.Cpu old : cpus) {
                    if (old.configs().contains(config) && old.charges() <= 0) {
                        site.move(old.id(), config, false); // the empty one out: its slot for the new
                    }
                }
                site.move(spare.id(), config, true);
            }
            List<HangarAccess.Cpu> after = site.cpus(CLOAK);
            HangarAccess.Cpu now2 = after.stream().filter(c -> c.id().equals(spare.id())).findFirst().orElse(null);
            if (now2 == null || !now2.configs().containsAll(configs)) {
                throw new java.io.IOException("после установки процессор не в конфигах " + configs);
            }
            answer = after;
        });
    }

    private boolean fit(long now) {
        if (working) {
            return true;
        }
        if (error != null) {
            return fail("установка: " + error);
        }
        log.info("Процессор маскировки поставлен на сайте ({} зарядов) — жду, пока игра его увидит", charges);
        go(Step.GAME, now);
        return true;
    }

    private boolean game(WorldSnapshot w, long now) {
        if (!empty(w)) {
            log.info("Маскировка снова есть — возвращаюсь к работе");
            return done();
        }
        if (!refreshed && ms(now - since) > GAME_MS) {
            Runnable r = refresh;
            if (r == null) {
                return fail("игра не видит новый процессор, а перевойти нельзя");
            }
            refreshed = true;
            since = now;
            log.info("Игра не видит новый процессор — перевхожу, чтобы она перечитала оснащение");
            Thread.ofVirtual().name("refresh").start(r);
            return true;
        }
        if (refreshed && ms(now - since) > AFTER_REFRESH_MS) {
            return fail("и после перевхода игра не видит новый процессор");
        }
        return true;
    }

    private void run(Job job) {
        working = true;
        worker.execute(() -> {
            if (logContext != null) {
                org.slf4j.MDC.setContextMap(logContext);
            }
            try {
                job.run();
            } catch (Exception e) {
                error = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            } finally {
                working = false;
            }
        });
    }

    private interface Job {
        void run() throws Exception;
    }

    private void go(Step s, long now) {
        step = s;
        since = now;
    }

    private boolean done() {
        step = null;
        told = "";
        return false;
    }

    private boolean fail(String why) {
        log.warn("Новый процессор маскировки не поставлен: {} — попробую через {} мин", why, RETRY_MS / 60_000);
        failedAt = ctx.now();
        step = null;
        ctx.nav().stop();
        return false;
    }

    /** The company's x-8 (the cloak's base, as BLCycle's 2-8), when a way there is known. */
    private int homeBase(WorldSnapshot.Hero h) {
        MapCatalog.MapInfo m = ctx.maps().byName(h.factionId() + "-8");
        if (m == null) {
            return 0;
        }
        if (m.id() == h.mapId()) {
            return m.id();
        }
        return orbit.bot.nav.Router.route(ctx.maps(), h.mapId(), h.x(), h.y(), m.id(), h.factionId(),
                h.level() > 0 ? h.level() : Integer.MAX_VALUE).isEmpty() ? 0 : m.id();
    }

    private void tell(String s) {
        if (!s.equals(told)) {
            told = s;
            log.info(s);
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
