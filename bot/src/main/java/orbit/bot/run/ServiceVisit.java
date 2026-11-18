package orbit.bot.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.Map;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A service visit on the account's service ship (the user, 2026-11-15: «присылать со Скайлаба лучше на Цитадель, а
 * текущий ангар не всегда Цитадель; бот выбирал лучшее время пересесть, прислать со Скайлаба, обогатить из трюма»):
 * when an enriched item falls below {@code visitBelow} and the hold lacks its ore, at a calm moment of the module the
 * bot switches to the service ship ({@code AccountShips.serviceShip}), brings the ore from the Skylab into its big hold,
 * enriches every item up to {@code fillTo}, and switches back to the ship it came from.
 *
 * <p>Settings in {@code modules.enrich}: {@code visit} (on/off), {@code visitBelow}, {@code fillTo} (the lab's units),
 * {@code visitFast} (the quick paid transport), {@code visitWaitMinutes} (how long the transport is awaited),
 * {@code visitEveryHours} (no sooner after the last visit). The items and their ores are the enrichment's own.
 *
 * <p>Whether the enrichment is the account's or the ship's was not known when this was written: after the switch the lab is
 * compared with the one before — another enrichment there means the ship's own, and the visits stop for the session.
 */
public final class ServiceVisit {

    private static final Logger log = LoggerFactory.getLogger(ServiceVisit.class);

    public enum Phase { IDLE, GOING, SERVICE, RETURNING }

    /** What the runner is to do this tick. */
    public record Step(int switchTo, String status) {
        static final Step NONE = new Step(0, null);

        boolean busy() {
            return status != null;
        }
    }

    private final BotContext ctx;
    private volatile SkylabAccess skylab;
    /** The service ship's hangar of the visit (its hold's capacity is remembered by it). */
    private int serviceHangar;
    /** Hold capacities by hangar, as the game told them at a login on that ship ({@code local/hangar-holds.json}). */
    private final Map<Integer, Long> holds = new java.util.concurrent.ConcurrentHashMap<>();
    private boolean holdsRead;
    /** A hold this old after the ship came in, the ores still not listed: ordered by the login's free room. */
    static final long HOLD_WAIT_MS = 15_000;
    private Phase phase = Phase.IDLE;
    private int returnTo;
    private long nextAt;
    private boolean disabled;
    /** «No service ship chosen» told once (the user, 2026-11-16: the visit set in the config, silently never made). */
    private boolean toldNoShip;
    private WorldSnapshot.Lab labBefore = WorldSnapshot.Lab.NONE;
    private long serviceSince;
    private boolean compared;
    private boolean ordered;
    private long orderedAt;
    private Map<Integer, Long> holdAtOrder = Map.of();
    private long sentAt = Long.MIN_VALUE / 2;
    private long sentLabNanos;
    private volatile boolean ordering;
    private boolean orderStarted;
    private volatile String orderError;
    /** The ore of the order came into the hold: once it is in the lab, the next trip (the hold limits one). */
    private boolean landed;
    /** Trips this visit (the user, 2026-11-16: «один раз отправил — обогатил, второй раз отправил — обогатил»). */
    private int trips;
    /** A visit cut by a stop is finished after the next start: its ship to go back to is kept on disk. */
    private boolean savedChecked;
    /** What the visit brought, by ore name, and each item's level when it began — for the visit's one summary line. */
    private final Map<String, Long> brought = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<Integer, Long> levelAtStart = new java.util.concurrent.ConcurrentHashMap<>();
    static final int MAX_TRIPS = 10;

    public ServiceVisit(BotContext ctx) {
        this.ctx = ctx;
    }

    public void setSkylab(SkylabAccess access) {
        this.skylab = access;
    }

    /** The state of a visit, over a ship change (the runner is a new one after each login). */
    public void takeFrom(ServiceVisit p) {
        phase = p.phase;
        returnTo = p.returnTo;
        nextAt = p.nextAt;
        disabled = p.disabled;
        labBefore = p.labBefore;
        serviceSince = 0;
        compared = p.compared;
        ordered = p.ordered;
        orderedAt = p.orderedAt;
        holdAtOrder = p.holdAtOrder;
        landed = p.landed;
        trips = p.trips;
        skylab = skylab != null ? skylab : p.skylab;
    }

    public Phase phase() {
        return phase;
    }

    private JsonNode settings() {
        JsonNode s = ctx.config().get().modules().get("enrich");
        return s == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : s;
    }

    /**
     * @param activeHangar the site's hangar in use ({@code 0} not known yet)
     * @param calm         the module may be left now for a few minutes (no fight, not in a gate…)
     */
    public Step tick(int activeHangar, boolean calm) {
        return tick(activeHangar, calm, true);
    }

    /** @param calmVisits visits at calm moments too; {@code false}: only on the module's own ship changes ({@link #divert}) */
    public Step tick(int activeHangar, boolean calm, boolean calmVisits) {
        JsonNode s = settings();
        int service = ctx.ships().serviceShip();
        long now = ctx.now();
        WorldSnapshot w = ctx.world().get();
        switch (phase) {
            case IDLE -> {
                if (!savedChecked && service > 0 && activeHangar != 0) {
                    savedChecked = true;
                    int back = savedReturn(service);
                    if (back > 0 && back != service && activeHangar == service) {
                        // stopped on the service ship mid-visit (the user, 2026-11-16: started again on the Citadel,
                        // it flew off to the cubes on it): never work on it — the visit finished first when the
                        // enrichment is on (the user, 2026-11-18: stopped mid-visit, it went back and waited 3 h with
                        // the lasers running low), the service phase going back by itself when nothing is needed
                        returnTo = back;
                        if (!disabled && s.path("enabled").asBoolean(false) && s.path("visit").asBoolean(false)) {
                            serviceHangar = service;
                            labBefore = WorldSnapshot.Lab.NONE;
                            compared = false;
                            ordered = false;
                            orderStarted = false;
                            landed = false;
                            trips = 0;
                            brought.clear();
                            levelAtStart.clear();
                            phase = Phase.SERVICE;
                            serviceSince = now;
                            log.info("Обслуживание: вошёл на корабле для Скайлаба после прерванного визита — довожу визит,"
                                    + " потом обратно на {}", back);
                            return service(s, w, now);
                        }
                        phase = Phase.RETURNING;
                        log.info("Обслуживание: вошёл на корабле для Скайлаба после прерванного визита — обратно на {}",
                                back);
                        return new Step(back, "Обслуживание: обратно на свой корабль");
                    }
                    if (back > 0 && activeHangar != service) {
                        saveReturn(service, 0); // already on another ship: nothing to finish
                    }
                }
                if (service <= 0 && !toldNoShip && s.path("enabled").asBoolean(false) && s.path("visit").asBoolean(false)
                        && w.hero().id() != 0 && needed(s, w)) {
                    toldNoShip = true;
                    log.warn("Обслуживание: в конфиге «пересаживаться на корабль для Скайлаба», но у аккаунта корабль не"
                            + " выбран («Аккаунты» → «Изменить» → «Скайлаб и обогащение») — не пересаживаюсь");
                }
                if (!calmVisits || disabled || service <= 0 || activeHangar == 0 || activeHangar == service
                        || !s.path("enabled").asBoolean(false) || !s.path("visit").asBoolean(false) || now < nextAt
                        || !calm || w.hero().id() == 0) {
                    return Step.NONE;
                }
                if (!needed(s, w)) {
                    return Step.NONE;
                }
                returnTo = activeHangar;
                labBefore = w.inventory().lab();
                compared = false;
                ordered = false;
                orderStarted = false;
                landed = false;
                trips = 0;
                brought.clear();
                levelAtStart.clear();
                saveReturn(service, returnTo);
                phase = Phase.GOING;
                log.info("Обслуживание: обогащению не хватает руды — пересаживаюсь на корабль для Скайлаба (ангар {}),"
                        + " потом обратно на {}", service, returnTo);
                return new Step(service, "Обслуживание: пересадка на корабль для Скайлаба");
            }
            case GOING -> {
                if (activeHangar == service) {
                    serviceHangar = service;
                    phase = Phase.SERVICE;
                    serviceSince = now;
                    return service(s, w, now);
                }
                return activeHangar == 0 ? new Step(0, "Обслуживание: вход на корабль для Скайлаба") : back("корабль не сменился");
            }
            case SERVICE -> {
                if (serviceSince == 0) {
                    serviceSince = now;
                }
                return service(s, w, now);
            }
            case RETURNING -> {
                if (activeHangar == returnTo || activeHangar != 0 && activeHangar != service) {
                    phase = Phase.IDLE;
                    saveReturn(service, 0);
                    nextAt = now + (long) (Math.max(0.5, s.path("visitEveryHours").asDouble(3)) * 3_600_000_000_000L);
                    log.info("Обслуживание: снова на своём корабле (ангар {})", activeHangar);
                    return Step.NONE;
                }
                return activeHangar == 0 ? new Step(0, "Обслуживание: вход на свой корабль") : new Step(returnTo,
                        "Обслуживание: обратно на свой корабль");
            }
        }
        return Step.NONE;
    }

    /**
     * A ship change the module asks for anyway (the two-ship palladium's «sold — back to collecting»): when a visit is
     * due, the service ship first and the module's ship after it (the user, 2026-11-16: «после продажи палладия — на
     * ангар для Скайлаба, сделали, что нужно, — на ангар для сбора; только когда нужно, иначе всё как сейчас»).
     *
     * @return the hangar to switch to now: the service ship, or {@code wanted} as it was
     */
    public int divert(int activeHangar, int wanted) {
        JsonNode s = settings();
        int service = ctx.ships().serviceShip();
        WorldSnapshot w = ctx.world().get();
        if (phase != Phase.IDLE || disabled || service <= 0 || wanted == service || activeHangar == service
                || activeHangar == 0 || !s.path("enabled").asBoolean(false) || !s.path("visit").asBoolean(false)
                || ctx.now() < nextAt || !needed(s, w)) {
            return wanted;
        }
        returnTo = wanted;
        labBefore = w.inventory().lab();
        compared = false;
        ordered = false;
        orderStarted = false;
        landed = false;
        trips = 0;
        brought.clear();
        levelAtStart.clear();
        saveReturn(service, returnTo);
        phase = Phase.GOING;
        log.info("Обслуживание: перед ангаром {} — на корабль для Скайлаба (ангар {}): обогащению не хватает руды",
                wanted, service);
        return service;
    }

    /** Some item set on is below {@code visitBelow} and the hold has not enough of its ore to top it up. */
    boolean needed(JsonNode s, WorldSnapshot w) {
        WorldSnapshot.Lab lab = w.inventory().lab();
        if (lab.items().isEmpty() && lab.nanos() == 0) {
            return false;
        }
        for (int item = 0; item < Enricher.ITEMS.length; item++) {
            JsonNode it = s.path("items").path(Enricher.ITEMS[item]);
            if (!it.path("on").asBoolean(false)) {
                continue;
            }
            WorldSnapshot.Enrichment e = lab.items().get(item);
            long left = e == null ? 0 : e.left();
            if (left >= it.path("below").asLong(s.path("visitBelow").asLong(50_000))) {
                continue;
            }
            int ore = ore(it, e);
            long load = Math.max(1, it.path("load").asLong(10_000));
            if (w.inventory().ore(ore) < load) {
                return true;
            }
        }
        return false;
    }

    private Step service(JsonNode s, WorldSnapshot w, long now) {
        WorldSnapshot.Lab lab = w.inventory().lab();
        if (lab.nanos() == 0 && lab.items().isEmpty() || lab.nanos() < startNanos()) {
            if (ms(now - sentAt) > 15_000) {
                sentAt = now;
                ctx.actions().labInfo(); // the service ship's lab after the login
            }
            return busy(now, s, "ответ лаборатории");
        }
        if (!compared) {
            compared = true;
            if (otherEnrichment(labBefore, lab)) {
                disabled = true;
                log.warn("Обслуживание: на корабле для Скайлаба обогащение другое ({} против {}) — оно у кораблей своё,"
                        + " обслуживание на другом корабле бессмысленно; возвращаюсь, до перезапуска больше не пересаживаюсь",
                        lab.items(), labBefore.items());
                return back("обогащение у кораблей своё");
            }
        }
        Map<Integer, Long> fill = new LinkedHashMap<>(); // item → its level to fill up to (its own units)
        Map<Integer, Long> want = new LinkedHashMap<>(); // ore → units to fill every item up to fillTo
        Map<Integer, Integer> itemOre = new LinkedHashMap<>();
        for (int item = 0; item < Enricher.ITEMS.length; item++) {
            JsonNode it = s.path("items").path(Enricher.ITEMS[item]);
            if (!it.path("on").asBoolean(false)) {
                continue;
            }
            WorldSnapshot.Enrichment e = lab.items().get(item);
            long left = e == null ? 0 : e.left();
            levelAtStart.putIfAbsent(item, left);
            long fillTo = fillTo(s, it);
            if (left >= fillTo) {
                continue;
            }
            int ore = ore(it, e);
            itemOre.put(item, ore);
            fill.put(item, fillTo);
            want.merge(ore, (fillTo - left + WorldSnapshot.Lab.PER_ORE - 1) / WorldSnapshot.Lab.PER_ORE, Long::sum);
        }
        if (want.isEmpty()) {
            log.info("Обслуживание: всё обогащено до своих уровней — обратно");
            return back(null);
        }
        // the enrichment from the hold, one at a time, the lab's answer awaited
        boolean waiting = lab.nanos() <= sentLabNanos && ms(now - sentAt) < 10_000;
        if (!waiting) {
            for (Map.Entry<Integer, Integer> e : itemOre.entrySet()) {
                int item = e.getKey();
                int ore = e.getValue();
                long have = w.inventory().ore(ore);
                if (have <= 0) {
                    continue;
                }
                WorldSnapshot.Enrichment en = lab.items().get(item);
                long left = en == null ? 0 : en.left();
                long fillTo = fill.get(item);
                long need = (fillTo - left + WorldSnapshot.Lab.PER_ORE - 1) / WorldSnapshot.Lab.PER_ORE;
                long n = Math.min(need, have);
                if (orderStarted && have > holdAtOrder.getOrDefault(ore, 0L)) {
                    // the order's ore: once it is in the lab, the next trip — also while the order is still being sent
                    // (live 2026-11-16 on gb2: the quick one landed and went into the lab before the site's page was
                    // read again, the visit then waited 15 min for ore already spent)
                    landed = true;
                }
                ctx.actions().labEnrich(item, ore, n);
                sentAt = now;
                sentLabNanos = lab.nanos();
                log.info("Обслуживание: {} ← {} {} (было {}, до {})", Enricher.ITEM_NAMES[item], Enricher.oreName(ore), n,
                        left, fillTo);
                return busy(now, s, "обогащаю");
            }
        }
        // the ore not in the hold: from the Skylab, once, then awaited
        Map<String, Long> lacking = new LinkedHashMap<>();
        for (Map.Entry<Integer, Long> e : want.entrySet()) {
            long missing = e.getValue() - w.inventory().ore(e.getKey());
            String name = SkylabCare.NAMES.get(e.getKey());
            if (missing > 0 && name != null) {
                lacking.put(name, missing);
            }
        }
        if (lacking.isEmpty()) {
            return busy(now, s, "обогащаю");
        }
        if (ordered && landed) {
            // what came is in the lab and more is wanted: another trip, as long as the storage and the hold give some
            if (trips >= MAX_TRIPS) {
                log.warn("Обслуживание: уже {} рейсов Скайлаба — хватит; обратно", trips);
                return back(null);
            }
            ordered = false;
            orderStarted = false;
            landed = false;
            log.info("Обслуживание: привезённое в лаборатории, нужно ещё {} — снова со Скайлаба", lacking);
        }
        long waitMs = Math.max(1, s.path("visitWaitMinutes").asLong(15)) * 60_000;
        if (!ordered && !orderStarted && now < nextOrderAt) {
            // the site takes no new quick transport a moment after the last one (live 2026-11-19 on int1: every order
            // sent 1–4 s after a delivery refused)
            return busy(now, s, "пауза между рейсами Скайлаба");
        }
        if (!ordered) {
            SkylabAccess a = skylab;
            if (a == null) {
                log.warn("Обслуживание: Скайлаб недоступен — обогатил, что было в трюме; обратно");
                return back(null);
            }
            if (!orderStarted) {
                // the hold of this ship, not the last one's: its ores listed since the login, or a moment for them
                // (live 2026-11-18 on gb2: ordered 7 s in, the quest ship's numbers — 14 192 into a hold of 8 000)
                if (!w.inventory().oresSinceLogin() && ms(now - serviceSince) < HOLD_WAIT_MS) {
                    return busy(now, s, "жду данные о трюме");
                }
                long told = w.cargoFree();
                long cap = holdCap(w);
                if (cap >= 0 && (told < 0 || cap < told)) {
                    log.info("Обслуживание: свободно в трюме по игре {}, по вместимости ангара {} — заказываю не больше {}",
                            told, cap, cap);
                }
                long room = cap >= 0 && (told < 0 || cap < told) ? cap : told;
                orderStarted = true;
                ordering = true;
                orderError = null;
                boolean fast = s.path("visitFast").asBoolean(false);
                holdAtOrder = Map.copyOf(w.inventory().ores().entrySet().stream().collect(java.util.stream.Collectors
                        .toMap(Map.Entry::getKey, x -> Math.round(x.getValue()))));
                java.util.Map<String, String> mdc = org.slf4j.MDC.getCopyOfContextMap();
                Thread.ofVirtual().name("service-skylab").start(() -> {
                    if (mdc != null) {
                        org.slf4j.MDC.setContextMap(mdc);
                    }
                    try {
                        SkylabAccess.Stock st = a.stock();
                        Map<String, Long> send = new LinkedHashMap<>();
                        long free = room < 0 ? Long.MAX_VALUE : room;
                        for (Map.Entry<String, Long> e : lacking.entrySet()) {
                            long there = st.ores().getOrDefault(e.getKey(), 0L);
                            if (there < e.getValue()) {
                                // told, not refused: what there is goes (the user, 2026-11-16: 14 000 set, 2 997 came)
                                log.warn("Обслуживание: на складе Скайлаба {} {} из нужных {} — везу сколько есть",
                                        e.getKey(), there, e.getValue());
                            }
                            long n = Math.min(Math.min(e.getValue(), there), free);
                            if (n < Math.min(e.getValue(), there)) {
                                // the hold, not the storage (live 2026-11-16 on gb2: 5 000 seprom in an 8 000 hold, 2 997
                                // of the 14 000 promerium came)
                                log.warn("Обслуживание: в трюме место на {} {} из нужных {} — везу {}", e.getKey(), free,
                                        e.getValue(), n);
                            }
                            if (n > 0) {
                                send.put(e.getKey(), n);
                                free -= n;
                            }
                        }
                        if (send.isEmpty()) {
                            orderError = room == 0 ? "в трюме нет места" : "на складе Скайлаба нет нужной руды ("
                                    + lacking + ", там " + st.ores() + ")";
                        } else if (st.transporting()) {
                            orderError = "транспорт Скайлаба уже в пути";
                        } else {
                            Map<String, Long> went = transportTrying(a, send, fast);
                            if (went != send) { // a smaller one went: that is what is counted as brought
                                send.clear();
                                send.putAll(went);
                            }
                            trips++;
                            nextOrderAt = ctx.now() + TRIP_GAP_MS * 1_000_000;
                            send.forEach((k, v) -> brought.merge(k, v, Long::sum));
                            log.info("Обслуживание: рейс {} — со Скайлаба в трюм {}{}", trips, send,
                                    fast ? " (быстро)" : "");
                        }
                    } catch (java.io.IOException | RuntimeException e) {
                        orderError = e.getMessage();
                    } finally {
                        ordering = false;
                    }
                });
                return busy(now, s, "заказываю руду со Скайлаба");
            }
            if (ordering) {
                return busy(now, s, "заказываю руду со Скайлаба");
            }
            if (orderError != null) {
                log.warn("Обслуживание: Скайлаб — {}; обратно", orderError);
                return back(null);
            }
            ordered = true;
            orderedAt = now;
            return busy(now, s, "жду руду со Скайлаба");
        }
        boolean arrived = want.keySet().stream().anyMatch(o -> w.inventory().ore(o) > holdAtOrder.getOrDefault(o, 0L));
        if (arrived) {
            landed = true;
        }
        if (!arrived && ms(now - orderedAt) > waitMs) {
            log.warn("Обслуживание: руда со Скайлаба не пришла за {} мин — обратно", waitMs / 60_000);
            return back(null);
        }
        return busy(now, s, arrived ? "обогащаю" : "жду руду со Скайлаба");
    }

    /** The next order no sooner than this after a trip (the site refused quick ones sent 1–4 s after the last). */
    static final long TRIP_GAP_MS = 15_000;
    /** A refused transport: the same again after this, at most {@link #TRANSPORT_TRIES} sends. */
    static final long REFUSED_WAIT_MS = 25_000;
    static final int TRANSPORT_TRIES = 3;
    private volatile long nextOrderAt = Long.MIN_VALUE / 2;
    /** For tests: the wait after a refusal. */
    long refusedWaitMs = REFUSED_WAIT_MS;

    /**
     * The transport, tried again when the site refuses: the same amounts and the same kind after
     * {@link #REFUSED_WAIT_MS} — not half (the amount was never the cause), never the normal one in place of a quick one
     * (the user, 2026-11-19: «автопереключение на не платный транспорт убрать»; live on int1: the slow one held the
     * visit for minutes); each refusal's page is kept in {@code local/debug} for its reason, which the site sends only
     * by a later ajax call.
     *
     * @return what was sent at last
     */
    private Map<String, Long> transportTrying(SkylabAccess a, Map<String, Long> send, boolean fast) throws java.io.IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                a.transport(send, fast);
                return send;
            } catch (java.io.IOException e) {
                keepRefusal(e);
                if (attempt >= TRANSPORT_TRIES || !String.valueOf(e.getMessage()).contains("не принял")) {
                    throw e;
                }
                log.warn("Обслуживание: {} — через {} с снова то же{}", e.getMessage(), refusedWaitMs / 1000,
                        fast ? " (быстро)" : "");
                try {
                    Thread.sleep(refusedWaitMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** The site's page of a refused transport, its tokens cut out, into {@code local/debug} (when the site sent one). */
    private void keepRefusal(java.io.IOException e) {
        if (!(e instanceof SkylabAccess.Refused r) || r.page() == null || ctx.dataDir() == null) {
            return;
        }
        try {
            java.nio.file.Path dir = java.nio.file.Path.of("local", "debug");
            java.nio.file.Files.createDirectories(dir);
            String clean = r.page().replaceAll("(?i)(dosid|sid|sessionid|session|reloadtoken|token|auth)([\"'=:\\s]+)[A-Za-z0-9%._\\-]{6,}",
                    "$1$2…");
            java.nio.file.Path f = dir.resolve("skylab-refused-" + java.time.LocalDateTime.now().format(
                    java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + ".html");
            java.nio.file.Files.writeString(f, clean);
            log.info("Обслуживание: ответ сайта на отказ сохранён в {}", f);
        } catch (java.io.IOException | RuntimeException x) {
            // kept for nothing: the log has the refusal
        }
    }

    /**
     * The service ship's hold: its capacity as remembered by its hangar less the ore in it now ({@code -1} when the
     * capacity is not known yet); the game's capacity of this login is remembered for the next visits.
     */
    private long holdCap(WorldSnapshot w) {
        java.nio.file.Path file = ctx.dataDir() == null ? null : ctx.dataDir().resolve("hangar-holds.json");
        if (!holdsRead) {
            holdsRead = true;
            if (file != null && java.nio.file.Files.isRegularFile(file)) {
                try {
                    orbit.config.Json.MAPPER.readTree(file.toFile()).fields().forEachRemaining(
                            e -> holds.put(Integer.parseInt(e.getKey()), e.getValue().asLong()));
                } catch (java.io.IOException | RuntimeException e) {
                    log.debug("Обслуживание: вместимость ангаров не прочитана: {}", e.getMessage());
                }
            }
        }
        WorldSnapshot.Hero h = w.hero();
        if (serviceHangar != 0 && h.cargoMax() > 0 && w.inventory().oresSinceLogin()
                && !Long.valueOf(h.cargoMax()).equals(holds.get(serviceHangar))) {
            holds.put(serviceHangar, (long) h.cargoMax());
            if (file != null) {
                try {
                    com.fasterxml.jackson.databind.node.ObjectNode o = orbit.config.Json.MAPPER.createObjectNode();
                    holds.forEach((k, v) -> o.put(String.valueOf(k), v));
                    java.nio.file.Files.createDirectories(file.getParent());
                    orbit.config.Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), o);
                } catch (java.io.IOException e) {
                    log.debug("Обслуживание: вместимость ангаров не записана: {}", e.getMessage());
                }
            }
        }
        Long capacity = holds.get(serviceHangar);
        if (capacity == null || capacity <= 0) {
            return -1;
        }
        long used = w.inventory().oresSinceLogin() ? w.inventory().cargoUsed()
                : h.cargoMax() > 0 && h.cargoFree() >= 0 ? Math.max(0, h.cargoMax() - h.cargoFree()) : 0;
        return Math.max(0, capacity - used);
    }

    private java.nio.file.Path returnFile() {
        return ctx.dataDir() == null ? null : ctx.dataDir().resolve("service-return.json");
    }

    /** The ship a visit from the service ship {@code service} goes back to, {@code 0} = none (kept on disk). */
    int savedReturn(int service) {
        java.nio.file.Path f = returnFile();
        if (f == null || !java.nio.file.Files.exists(f)) {
            return memoryReturn.getOrDefault(service, 0);
        }
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().readTree(f.toFile()).path(String.valueOf(service)).asInt(0);
        } catch (java.io.IOException | RuntimeException e) {
            return 0;
        }
    }

    private final Map<Integer, Integer> memoryReturn = new java.util.HashMap<>();

    private void saveReturn(int service, int back) {
        java.nio.file.Path f = returnFile();
        if (f == null) {
            memoryReturn.put(service, back);
            return;
        }
        try {
            com.fasterxml.jackson.databind.ObjectMapper json = new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode all = java.nio.file.Files.exists(f)
                    && json.readTree(f.toFile()) instanceof com.fasterxml.jackson.databind.node.ObjectNode o ? o
                    : json.createObjectNode();
            if (back > 0) {
                all.put(String.valueOf(service), back);
            } else {
                all.remove(String.valueOf(service));
            }
            java.nio.file.Files.createDirectories(f.toAbsolutePath().getParent());
            json.writeValue(f.toFile(), all);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Обслуживание: не сохранить корабль для возврата: {}", e.getMessage());
        }
    }

    /** The lab's time of the login on the service ship: answers before it are the other ship's. */
    private long startNanos() {
        return serviceSince;
    }

    /** «; за визит: 2 рейса, promerium 14 056; лазеры 49 440 → 190 000» — empty without trips. */
    private String summary(WorldSnapshot.Lab lab) {
        if (trips == 0) {
            return "";
        }
        StringBuilder b = new StringBuilder("; за визит: рейсов ").append(trips);
        brought.forEach((k, v) -> b.append(", ").append(k).append(' ').append(String.format("%,d", v)));
        levelAtStart.forEach((item, was) -> {
            WorldSnapshot.Enrichment e = lab.items().get(item);
            b.append("; ").append(Enricher.ITEM_NAMES[item]).append(' ').append(String.format("%,d", was)).append(" → ")
                    .append(String.format("%,d", e == null ? 0 : e.left()));
        });
        return b.toString();
    }

    private Step busy(long now, JsonNode s, String what) {
        long max = (Math.max(1, s.path("visitWaitMinutes").asLong(15)) + 10) * 60_000;
        if (ms(now - Math.max(serviceSince, ordered ? orderedAt : 0)) > max) {
            log.warn("Обслуживание: идёт дольше {} мин — обратно", max / 60_000);
            return back(null);
        }
        return new Step(0, "Обслуживание: " + what);
    }

    private Step back(String why) {
        if (why != null) {
            log.info("Обслуживание: {} — обратно на свой корабль", why);
        }
        if (trips > 0) {
            // one line for the whole visit (the user, 2026-11-16: «в логах не понятно, он 2 транспорта сделал?»)
            log.info("Обслуживание: итог{}", summary(ctx.world().get().inventory().lab()).replaceFirst("^; ", " — "));
        }
        phase = Phase.RETURNING;
        ordered = false;
        orderStarted = false;
        return new Step(returnTo, "Обслуживание: обратно на свой корабль");
    }

    /**
     * Another enrichment on the service ship: an item of another ore, or with much more left than before (more is not
     * spent by switching ships); less is the use while switching.
     */
    static boolean otherEnrichment(WorldSnapshot.Lab before, WorldSnapshot.Lab now) {
        if (before.items().isEmpty()) {
            return false;
        }
        for (Map.Entry<Integer, WorldSnapshot.Enrichment> e : before.items().entrySet()) {
            WorldSnapshot.Enrichment n = now.items().get(e.getKey());
            WorldSnapshot.Enrichment b = e.getValue();
            if (b.left() <= 0) {
                continue;
            }
            if (n == null || n.ore() != b.ore() || n.left() > b.left() + 100) {
                return true;
            }
        }
        return false;
    }

    /**
     * The item's level to fill up to on the service ship: its threshold and one load more (the user, 2026-11-16:
     * «двигатель меньше 100 часов — доставка 1 000 промериума»); an explicit {@code fillTo} of an older config wins.
     */
    static long fillTo(JsonNode s, JsonNode it) {
        long below = it.path("below").asLong(50_000);
        long load = Math.max(1, it.path("load").asLong(10_000));
        return Math.max(1, it.path("fillTo").asLong(s.path("fillTo").asLong(below + load * WorldSnapshot.Lab.PER_ORE)));
    }

    private static int ore(JsonNode it, WorldSnapshot.Enrichment e) {
        if (e != null && e.left() > 0) {
            return e.ore();
        }
        Integer o = Enricher.ORES.get(it.path("ore").asText("PROMERIUM").toUpperCase(java.util.Locale.ROOT));
        return o != null ? o : 6;
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
