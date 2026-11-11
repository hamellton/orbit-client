package orbit.bot.nav;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import orbit.bot.act.Actions;
import orbit.world.WorldSnapshot;
import orbit.world.maps.MapCatalog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A jump to another map with the jump CPU, the client's way (recorded 2026-11-11 on ru1): {@code JCPU|GET}
 * for the price table, {@code JCPU|S|<map>} and the server's confirmation, a moment later {@code JCPU|J},
 * then a countdown (10 s) and the ship is on the map. The server pays with a voucher while there are any,
 * with uridium after — uridium only within a daily budget. Anything wrong: the caller flies by portals, and
 * that map is not jumped to for a while.
 */
public final class CpuJumper {

    private static final Logger log = LoggerFactory.getLogger(CpuJumper.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    public enum Result { RUNNING, DONE, FAILED }

    private enum Step { PRICES, SELECT, CONFIRMED, JUMPING }

    static final long ANSWER_MS = 6_000;
    /** After the countdown the server moves the ship; this much longer is a jump that did not happen. */
    static final long LANDING_MS = 15_000;
    /** A map whose jump failed is reached by portals for this long. */
    static final long RETRY_MS = 10 * 60_000L;
    /** …and after the server called the countdown off (seen under fire, 2026-11-11): soon again. */
    static final long RETRY_CANCELLED_MS = 2 * 60_000L;

    private final Supplier<WorldSnapshot> world;
    private final Actions actions;
    private final MapCatalog catalog;
    private final LongSupplier clock;
    private final Random random;
    private final Path ledger;
    private final Map<Integer, Long> failedAt = new HashMap<>();
    private final Map<Integer, Long> retryAfter = new HashMap<>();
    private int target;
    private Step step;
    private long since;
    private long jumpAt;
    private int price;
    private boolean paidWithUridium;
    private String failure;

    /** @param ledger where the uridium spent on jumps today is kept ({@code null}: in memory, tests) */
    public CpuJumper(Supplier<WorldSnapshot> world, Actions actions, MapCatalog catalog, LongSupplier clock, Random random,
                     Path ledger) {
        this.world = world;
        this.actions = actions;
        this.catalog = catalog;
        this.clock = clock;
        this.random = random;
        this.ledger = ledger;
    }

    /** Why the last jump did not happen (for the log), or {@code null}. */
    public String failure() {
        return failure;
    }

    /** False for a while after a jump to this map failed: fly by portals meanwhile. */
    public boolean worthTrying(int map) {
        Long at = failedAt.get(map);
        return at == null || clock.getAsLong() - at > retryAfter.getOrDefault(map, RETRY_MS) * 1_000_000;
    }

    /**
     * One step towards {@code map}; call every tick until DONE or FAILED. The ship should keep still meanwhile.
     *
     * @param uridiumPerDay uridium allowed on jumps today when no voucher is left
     */
    public Result tick(int map, int uridiumPerDay) {
        WorldSnapshot w = world.get();
        WorldSnapshot.Hero h = w.hero();
        WorldSnapshot.JumpCpu cpu = w.jumpCpu();
        long now = clock.getAsLong();
        if (h.mapId() == map) {
            if (target == map && paidWithUridium) {
                spend(price);
            }
            reset();
            return Result.DONE;
        }
        if (map != target || step == null) {
            if (!cpu.equipped()) {
                return fail(map, "на корабле нет CPU прыжка");
            }
            if (!h.attackers().isEmpty()) {
                // standing still for a countdown under fire: the server seems to call it off; fly instead
                return fail(map, "корабль под огнём", 30_000);
            }
            target = map;
            step = Step.PRICES;
            since = now;
            paidWithUridium = false;
            actions.jumpCpuPrices();
            return Result.RUNNING;
        }
        switch (step) {
            case PRICES -> {
                if (cpu.pricesNanos() <= since) {
                    return now - since > ANSWER_MS * 1_000_000 ? fail(map, "сервер не дал цены прыжков") : Result.RUNNING;
                }
                price = cpu.prices().getOrDefault(map, -1);
                if (price < 0) {
                    return fail(map, "на " + name(map) + " прыжком нельзя");
                }
                if (cpu.vouchers() <= 0) {
                    int left = uridiumPerDay - spentToday();
                    if (price > left) {
                        return fail(map, "купонов нет, а уридия на прыжки сегодня осталось " + Math.max(0, left)
                                + " из " + uridiumPerDay + " (нужно " + price + ")");
                    }
                    if (h.uridium() < price) {
                        return fail(map, "купонов нет, уридия " + (long) h.uridium() + " — нужно " + price);
                    }
                    paidWithUridium = true;
                }
                actions.jumpCpuSelect(map);
                step = Step.SELECT;
                since = now;
            }
            case SELECT -> {
                if (cpu.confirmedNanos() > since && cpu.confirmedMap() == map) {
                    step = Step.CONFIRMED;
                    jumpAt = now + (1_500 + (long) (random.nextDouble() * 2_000)) * 1_000_000; // the client: 2.6 s
                } else if (now - since > ANSWER_MS * 1_000_000) {
                    return fail(map, "сервер не подтвердил прыжок на " + name(map));
                }
            }
            case CONFIRMED -> {
                if (now >= jumpAt) {
                    log.info("Прыжок CPU на {} — {}", name(map),
                            paidWithUridium ? "за " + price + " уридия" : "за купон (осталось " + cpu.vouchers() + ")");
                    actions.jumpCpuJump();
                    step = Step.JUMPING;
                    since = now;
                }
            }
            case JUMPING -> {
                boolean counting = cpu.countdownNanos() > since && cpu.countdownMap() == map;
                if (counting && cpu.countdownSeconds() <= 0) {
                    return fail(map, "сервер отменил отсчёт (" + cpu.countdownSeconds() + ")", RETRY_CANCELLED_MS);
                }
                long limit = (counting ? cpu.countdownSeconds() * 1_000L : ANSWER_MS) + LANDING_MS;
                if (now - since > limit * 1_000_000) {
                    return fail(map, counting ? "отсчёт прошёл, а корабль не на " + name(map) : "сервер не начал прыжок");
                }
            }
        }
        return Result.RUNNING;
    }

    private Result fail(int map, String why) {
        return fail(map, why, RETRY_MS);
    }

    private Result fail(int map, String why, long retryMs) {
        failure = why;
        failedAt.put(map, clock.getAsLong());
        retryAfter.put(map, retryMs);
        log.warn("Прыжок CPU не вышел: {} — лечу порталами", why);
        reset();
        return Result.FAILED;
    }

    private void reset() {
        target = 0;
        step = null;
    }

    private String name(int map) {
        return catalog.info(map).name();
    }

    // ---- uridium spent today, kept across restarts ----

    private int memorySpent;
    private LocalDate memoryDay = LocalDate.now();

    int spentToday() {
        LocalDate today = LocalDate.now();
        if (ledger == null) {
            return today.equals(memoryDay) ? memorySpent : 0;
        }
        try {
            if (!Files.exists(ledger)) {
                return 0;
            }
            JsonNode n = JSON.readTree(ledger.toFile());
            return today.toString().equals(n.path("day").asText()) ? n.path("uridium").asInt(0) : 0;
        } catch (IOException e) {
            log.warn("Не прочитать {}: {}", ledger, e.getMessage());
            return 0;
        }
    }

    private void spend(int uridium) {
        int total = spentToday() + uridium;
        if (ledger == null) {
            memoryDay = LocalDate.now();
            memorySpent = total;
            return;
        }
        try {
            Files.createDirectories(ledger.toAbsolutePath().getParent());
            ObjectNode n = JSON.createObjectNode();
            n.put("day", LocalDate.now().toString());
            n.put("uridium", total);
            Files.writeString(ledger, n.toPrettyString());
        } catch (IOException e) {
            log.warn("Не записать {}: {}", ledger, e.getMessage());
        }
        log.info("Уридий на прыжки сегодня: {}", total);
    }
}
