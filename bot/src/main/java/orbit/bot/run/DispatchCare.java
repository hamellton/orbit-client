package orbit.bot.run;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The dispatch's retrievers (as in DarkBot and AstraBot), by packets as AstraBot does —
 * no window: the list in progress asked every {@link #REFRESH_MS} ({@code DispatchGetRetrieverListRequest} 1 — the
 * official client asks only this one; the server answers it with {@code DispatchSlotInfoCommand}, and the retrievers one
 * may send are the client's own definitions, no list of them comes — live 2026-11-19 on gb2: waited for one, asked every
 * 2 s, nothing done), a finished one collected ({@code DispatchCollectRetrieverRequest}), into a free slot the chosen retriever
 * sent ({@code DispatchSendRetrieverRequest}) — PikaPika's DispatchNew's limits: no more of it in progress than
 * {@code count}, its price checked first, {@code freeSlots} left unused (AstraBot); at most one action every
 * {@link #ACTION_MS}, a human's pace. Optional: a running one finished at once for a quick coupon ({@code instant}).
 * Never in a gate or the Mimesis escort (PikaPika {@code only_outside_gg}). The server's refusal (no resource, no
 * slot) — no new send for {@link #REFUSED_MS}.
 *
 * <p>Settings {@code modules.dispatch}: {@code enabled} (false), {@code retriever} («R-01», 200 000 credits),
 * {@code count} (5), {@code freeSlots} (0), {@code instant} (false), {@code keepCredits} (0: credits left after a send).
 */
public final class DispatchCare {

    private static final Logger log = LoggerFactory.getLogger(DispatchCare.class);

    public static final String KEY = "dispatch";
    static final long REFRESH_MS = 60_000;
    static final long ACTION_MS = 4_000;
    static final long AFTER_ACTION_MS = 2_000;
    static final long REFUSED_MS = 30 * 60_000;
    /** The game counts a slot's time down from when the list came: done this close to 0. */
    static final double DONE_WITHIN_S = 1;

    /** A retriever as the client defines it (DispatchRetrieverDefinition, 2026-11-04): number, name, price, hours. */
    public record Kind(int id, String name, long credits, long uridium, int permits, int permitsPlus, double hours) {

        public String label() {
            StringBuilder s = new StringBuilder(name).append(" — ");
            if (credits > 0) {
                s.append(String.format("%,d", credits).replace(',', ' ')).append(" кредитов");
            } else {
                s.append(String.format("%,d", uridium).replace(',', ' ')).append(" уридия");
            }
            if (permits > 0) {
                s.append(" + Permit");
            }
            if (permitsPlus > 0) {
                s.append(" + Permit+");
            }
            return s.append(", ").append(hours % 1 == 0 ? String.valueOf((int) hours) : String.valueOf(hours)).append(" ч")
                    .toString();
        }
    }

    public static final List<Kind> KINDS = kinds();

    private static List<Kind> kinds() {
        List<Kind> out = new java.util.ArrayList<>();
        String[] series = {"A", "B", "R"};
        int[] base = {0, 6, 12};
        long[][] price = {{200_000, 0, 0, 0}, {0, 2_000, 0, 0}, {0, 2_000, 1, 0}, {0, 5_000, 1, 0}, {0, 10_000, 1, 0},
            {0, 10_000, 0, 1}};
        double[] hours = {3, 6, 6, 9, 12, 12}; // the client's durations: 10 800 … 43 200 s
        for (int s = 0; s < series.length; s++) {
            for (int t = 1; t <= 6; t++) {
                long[] p = price[t - 1];
                out.add(new Kind(base[s] + t, series[s] + "-0" + t, p[0], p[1], (int) p[2], (int) p[3],
                        hours[t - 1]));
            }
        }
        out.add(new Kind(19, "H-03", 0, 5_000, 1, 0, 5));
        out.add(new Kind(20, "H-04", 0, 10_000, 1, 0, 7));
        out.add(new Kind(21, "H-05", 0, 15_000, 1, 0, 9));
        return List.copyOf(out);
    }

    public static Kind byName(String name) {
        String n = name == null ? "" : name.strip().toUpperCase(java.util.Locale.ROOT);
        return KINDS.stream().filter(k -> k.name().equals(n)).findFirst().orElse(null);
    }

    static Kind byId(int id) {
        return KINDS.stream().filter(k -> k.id() == id).findFirst().orElse(null);
    }

    private final BotContext ctx;
    private long askedAt = Long.MIN_VALUE / 2;
    private long actedAt = Long.MIN_VALUE / 2;
    private long refusedUntil = Long.MIN_VALUE / 2;
    private final Map<String, String> told = new LinkedHashMap<>();
    private long messageSeen;

    DispatchCare(BotContext ctx) {
        this.ctx = ctx;
    }

    static JsonNode settings(BotConfig c) {
        JsonNode s = c.modules().get(KEY);
        return s != null ? s : com.fasterxml.jackson.databind.node.MissingNode.getInstance();
    }

    void tick() {
        BotConfig c = ctx.config().get();
        JsonNode s = settings(c);
        if (!s.path("enabled").asBoolean(false)) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0 || w.death() != null || orbit.config.GateCatalog.instanced(h.mapId())) {
            return; // not in a gate or the escort (no window there, PikaPika's only_outside_gg)
        }
        long now = ctx.now();
        WorldSnapshot.Dispatch d = w.gates().dispatch();
        message(d, now);
        if (ms(now - actedAt) < ACTION_MS) {
            return;
        }
        // the list in progress: at the start, every minute, and a moment after each action — never more often (no answer
        // is no reason to ask again soon)
        boolean due = ms(now - askedAt) > REFRESH_MS || actedAt > askedAt && ms(now - actedAt) > AFTER_ACTION_MS;
        if (due) {
            ctx.actions().dispatchList(1);
            askedAt = now;
            return;
        }
        if (d.inProgressNanos() == 0 || d.inProgressNanos() < askedAt) {
            if (ms(now - askedAt) > 20_000) {
                tell("noanswer", "Диспетчер: сервер не ответил на запрос слотов — спрошу через минуту");
            }
            return; // the answer to the last ask not here yet
        }
        state(d, now);
        // finished ones: collected one at a time
        for (WorldSnapshot.Retriever r : d.inProgress()) {
            double left = r.duration() - (now - d.inProgressNanos()) / 1e9;
            if (r.slotId() > 0 && left <= DONE_WITHIN_S) {
                Kind k = byId(r.id());
                log.info("Диспетчер: забираю {} из слота {}", k != null ? k.name() : "№" + r.id(), r.slotId());
                ctx.actions().dispatchCollect(r.slotId());
                actedAt = now;
                return;
            }
        }
        if (s.path("instant").asBoolean(false)) {
            WorldSnapshot.Retriever r = d.inProgress().stream().filter(x -> x.slotId() > 0).findFirst().orElse(null);
            if (r != null && now >= refusedUntil) {
                log.info("Диспетчер: завершаю мгновенно слот {} (Quick Coupon)", r.slotId());
                ctx.actions().dispatchInstant(r.slotId());
                actedAt = now;
                return;
            }
        }
        send(s, d, h, w, now);
    }

    private void send(JsonNode s, WorldSnapshot.Dispatch d, WorldSnapshot.Hero h, WorldSnapshot w, long now) {
        Kind k = byName(s.path("retriever").asText("R-01"));
        if (k == null) {
            tell("kind", "Диспетчер: сборщик «" + s.path("retriever").asText() + "» неизвестен — не отправляю");
            return;
        }
        int free = d.freeSlots() >= 0 ? d.freeSlots()
                : Math.max(0, (d.maxSlots() > 0 ? d.maxSlots() : 1) - d.inProgress().size());
        if (free - Math.max(0, s.path("freeSlots").asInt(0)) <= 0 || now < refusedUntil) {
            return;
        }
        long running = d.inProgress().stream().filter(r -> r.id() == k.id()).count();
        if (running >= Math.max(1, s.path("count").asInt(5))) {
            return;
        }
        // keepCredits: credits left after the price (0 by default; the leveling configs keep their reserve and the next
        // equipment purchase, a user's choice 2026-11-25)
        long keep = Math.max(0, s.path("keepCredits").asLong(0));
        if (k.credits() > 0 && h.credits() >= 0 && h.credits() < k.credits() + keep
                || k.uridium() > 0 && h.uridium() >= 0 && h.uridium() < k.uridium()) {
            tell("money", "Диспетчер: на " + k.label() + " не хватает " + (k.credits() > 0 ? "кредитов" : "уридия"));
            return;
        }
        if (lacking(w, "resource_collectable_permit", k.permits()) || lacking(w, "resource_collectable_permit-plus",
                k.permitsPlus())) {
            tell("permit", "Диспетчер: на " + k.name() + " нет Permit" + (k.permitsPlus() > 0 ? "+" : ""));
            return;
        }
        told.clear();
        log.info("Диспетчер: отправляю {} (свободных слотов {}, в работе таких {})", k.label(), free, running);
        ctx.actions().dispatchSend(k.id());
        actedAt = now;
    }

    /** The slots as the server told them, once each time they change: how many, what runs, the soonest end. */
    private void state(WorldSnapshot.Dispatch d, long now) {
        StringBuilder s = new StringBuilder("Диспетчер: слотов ").append(d.maxSlots() >= 0 ? d.maxSlots() : "?")
                .append(", свободно ").append(d.freeSlots() >= 0 ? d.freeSlots() : "?");
        if (!d.inProgress().isEmpty()) {
            Map<String, Integer> kinds = new LinkedHashMap<>();
            double soonest = Double.MAX_VALUE;
            for (WorldSnapshot.Retriever r : d.inProgress()) {
                Kind k = byId(r.id());
                kinds.merge(k != null ? k.name() : "№" + r.id(), 1, Integer::sum);
                soonest = Math.min(soonest, r.duration());
            }
            s.append(", в работе ");
            kinds.forEach((n, c) -> s.append(n).append(" ×").append(c).append(' '));
            long m = (long) Math.max(0, soonest) / 60;
            s.append("(ближайший через ").append(m / 60).append(':').append(String.format("%02d", m % 60)).append(')');
        }
        tell("state", s.toString().strip());
    }

    /** A resource the inventory counts and has fewer of than needed (one it does not count is not judged). */
    private static boolean lacking(WorldSnapshot w, String id, int need) {
        if (need <= 0) {
            return false;
        }
        Double have = w.inventory().resources().get(id);
        return have != null && have < need;
    }

    /** The server's word: a refusal stops the sending a while. */
    private void message(WorldSnapshot.Dispatch d, long now) {
        if (d.messageNanos() == 0 || d.messageNanos() == messageSeen) {
            return;
        }
        messageSeen = d.messageNanos();
        String why = switch (d.message()) {
            case orbit.protocol.gen.DispatchMessageCommand.MESSAGETYPE_DISPATCH_ERROR_NO_RESOURCE -> "не хватает ресурсов";
            case orbit.protocol.gen.DispatchMessageCommand.MESSAGETYPE_DISPATCH_ERROR_NO_SLOT -> "нет свободного слота";
            case orbit.protocol.gen.DispatchMessageCommand.MESSAGETYPE_DISPATCH_ERROR_SEND_RETRIEVER -> "отправка не прошла";
            case orbit.protocol.gen.DispatchMessageCommand.MESSAGETYPE_DISPATCH_ERROR_COLLECT -> "забрать не вышло";
            case orbit.protocol.gen.DispatchMessageCommand.MESSAGETYPE_DISPATCH_ERROR_NOT_READY -> "ещё не готов";
            default -> null;
        };
        if (why != null) {
            refusedUntil = now + REFUSED_MS * 1_000_000;
            log.warn("Диспетчер: сервер отказал — {}; снова через {} мин", why, REFUSED_MS / 60_000);
        }
    }

    private void tell(String key, String text) {
        if (!text.equals(told.get(key))) {
            told.put(key, text);
            log.info(text);
        }
    }

    private static long ms(long nanos) {
        return nanos / 1_000_000;
    }
}
