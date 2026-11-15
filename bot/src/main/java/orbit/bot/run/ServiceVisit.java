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
    private Phase phase = Phase.IDLE;
    private int returnTo;
    private long nextAt;
    private boolean disabled;
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
        JsonNode s = settings();
        int service = ctx.ships().serviceShip();
        long now = ctx.now();
        WorldSnapshot w = ctx.world().get();
        switch (phase) {
            case IDLE -> {
                if (disabled || service <= 0 || activeHangar == 0 || activeHangar == service
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
                phase = Phase.GOING;
                log.info("Обслуживание: обогащению не хватает руды — пересаживаюсь на корабль для Скайлаба (ангар {}),"
                        + " потом обратно на {}", service, returnTo);
                return new Step(service, "Обслуживание: пересадка на корабль для Скайлаба");
            }
            case GOING -> {
                if (activeHangar == service) {
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
        long waitMs = Math.max(1, s.path("visitWaitMinutes").asLong(15)) * 60_000;
        if (!ordered) {
            SkylabAccess a = skylab;
            if (a == null) {
                log.warn("Обслуживание: Скайлаб недоступен — обогатил, что было в трюме; обратно");
                return back(null);
            }
            if (!orderStarted) {
                orderStarted = true;
                ordering = true;
                orderError = null;
                boolean fast = s.path("visitFast").asBoolean(false);
                long room = w.cargoFree();
                holdAtOrder = Map.copyOf(w.inventory().ores().entrySet().stream().collect(java.util.stream.Collectors
                        .toMap(Map.Entry::getKey, x -> Math.round(x.getValue()))));
                Thread.ofVirtual().name("service-skylab").start(() -> {
                    try {
                        SkylabAccess.Stock st = a.stock();
                        Map<String, Long> send = new LinkedHashMap<>();
                        long free = room < 0 ? Long.MAX_VALUE : room;
                        for (Map.Entry<String, Long> e : lacking.entrySet()) {
                            long n = Math.min(Math.min(e.getValue(), st.ores().getOrDefault(e.getKey(), 0L)), free);
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
                            a.transport(send, fast);
                            log.info("Обслуживание: со Скайлаба в трюм {}{}", send, fast ? " (быстро)" : "");
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
        if (!arrived && ms(now - orderedAt) > waitMs) {
            log.warn("Обслуживание: руда со Скайлаба не пришла за {} мин — обратно", waitMs / 60_000);
            return back(null);
        }
        return busy(now, s, arrived ? "обогащаю" : "жду руду со Скайлаба");
    }

    /** The lab's time of the login on the service ship: answers before it are the other ship's. */
    private long startNanos() {
        return serviceSince;
    }

    private Step busy(long now, JsonNode s, String what) {
        long max = (Math.max(1, s.path("visitWaitMinutes").asLong(15)) + 10) * 60_000;
        if (ms(now - serviceSince) > max) {
            log.warn("Обслуживание: идёт дольше {} мин — обратно", max / 60_000);
            return back(null);
        }
        return new Step(0, "Обслуживание: " + what);
    }

    private Step back(String why) {
        if (why != null) {
            log.info("Обслуживание: {} — обратно на свой корабль", why);
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

    /** The item's level to fill up to on the service ship: its own {@code fillTo}, else the old common one, else 4× below. */
    static long fillTo(JsonNode s, JsonNode it) {
        long below = it.path("below").asLong(50_000);
        return Math.max(1, it.path("fillTo").asLong(s.path("fillTo").asLong(below * 4)));
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
