package orbit.bot.run;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Buys what the config's buy list asks for when it runs low — only those items, never below the money
 * to keep, at most the shop's limit per purchase, and one item at a time, not more than once every
 * {@link #RETRY_MS} per item (the counter may take a moment to come back from the game). The website
 * requests run on their own thread, so the fight never waits for the shop.
 */
final class BuyWatch {

    private static final Logger log = LoggerFactory.getLogger(BuyWatch.class);

    static final long CHECK_MS = 10_000;
    static final long RETRY_MS = 10 * 60_000;

    private final BotContext ctx;
    private volatile ShopAccess shop;
    private final Set<String> told = new HashSet<>();
    private final Map<String, Long> triedAt = new HashMap<>();
    /**
     * Bought, waiting for the game's count to rise: item → {count before, when}. No rise within {@link #EFFECT_MS} —
     * the item is not bought again this run (CARNAGE's log: 19 cloak purchases of 21 took the uridium and gave nothing).
     */
    private final Map<String, double[]> bought = new java.util.concurrent.ConcurrentHashMap<>();
    private final Set<String> noEffect = new HashSet<>();
    static final long EFFECT_MS = 120_000;
    private final AtomicBoolean busy = new AtomicBoolean();
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "shop");
        t.setDaemon(true);
        return t;
    });
    private long nextCheck;
    /** No purchase before the game has listed the items: right after a login nothing is known yet. */
    static final long SETTLE_MS = 30_000;

    /** The account's log context, carried into the shop thread. */
    private final java.util.Map<String, String> logContext = org.slf4j.MDC.getCopyOfContextMap();

    BuyWatch(BotContext ctx) {
        this.ctx = ctx;
        this.nextCheck = ctx.now() + SETTLE_MS * 1_000_000;
        this.noEffectFile = ctx.dataDir() == null ? null : ctx.dataDir().resolve("buy-no-effect.json");
        loadNoEffect();
    }

    /**
     * «Bought, nothing came» outlives a restart for {@link #NO_EFFECT_KEEP_MS} (live 2026-11-13 on the VPS: the cloak went
     * to the inventory, the restart bought another 15 750 uridium's worth).
     */
    static final long NO_EFFECT_KEEP_MS = 24 * 3_600_000L;
    private final java.nio.file.Path noEffectFile;

    private void loadNoEffect() {
        if (noEffectFile == null || !java.nio.file.Files.exists(noEffectFile)) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.JsonNode n = orbit.config.Json.MAPPER.readTree(noEffectFile.toFile());
            long wall = System.currentTimeMillis();
            n.properties().forEach(e -> {
                if (wall - e.getValue().asLong() < NO_EFFECT_KEEP_MS && !fired(e.getKey())) {
                    noEffect.add(e.getKey());
                    log.info("{}: куплено раньше, но в игре не появилось — не покупаю до {}", ItemCatalog.label(e.getKey()),
                            java.time.Instant.ofEpochMilli(e.getValue().asLong() + NO_EFFECT_KEEP_MS)
                                    .atZone(java.time.ZoneId.systemDefault()).toLocalDateTime().withNano(0));
                }
            });
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Не прочитал {}: {}", noEffectFile.getFileName(), e.toString());
        }
    }

    private void saveNoEffect(String id) {
        if (noEffectFile == null) {
            return;
        }
        try {
            com.fasterxml.jackson.databind.node.ObjectNode n = java.nio.file.Files.exists(noEffectFile)
                    ? (com.fasterxml.jackson.databind.node.ObjectNode) orbit.config.Json.MAPPER.readTree(noEffectFile.toFile())
                    : orbit.config.Json.MAPPER.createObjectNode();
            n.put(id, System.currentTimeMillis());
            java.nio.file.Path tmp = noEffectFile.resolveSibling(noEffectFile.getFileName() + ".new");
            orbit.config.Json.MAPPER.writeValue(tmp.toFile(), n);
            java.nio.file.Files.move(tmp, noEffectFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Не записал {}: {}", noEffectFile.getFileName(), e.toString());
        }
    }

    /**
     * Rockets (laser rockets, launcher rockets) are fired as soon as they come: the count may never rise between two looks,
     * so a purchase of them is never taken for «nothing came» (live 2026-11-26 on int1: 100 ECO-10 bought, the launcher fired
     * them 4 s later, the count stayed 0 and ECO-10 was not bought for a day by any account of the server; PLT-2026 the
     * same on int7).
     */
    static boolean fired(String item) {
        return item.startsWith("ammunition_rocket_") || item.startsWith("ammunition_rocketlauncher_");
    }

    /**
     * Laser ammunition bought in an amount the lasers can shoot within {@link #EFFECT_MS} (a salvo a second, one round a
     * laser): the count may fall all the while, so it is never taken for «nothing came» (live 2026-11-27 on int7: 22 LCB-10
     * bought with the last 220 credits during a fight, the count never rose, and LCB-10 was not bought for a day by any of
     * the server's five accounts — their lasers stood silent). Lasers unknown: judged as before.
     */
    static boolean firedWithin(String item, long amount, int lasers) {
        return item.startsWith("ammunition_laser_") && lasers > 0 && amount <= lasers * (EFFECT_MS / 1000);
    }

    /** The wall time of the last purchase of each item (for {@code modules.buyLimit}'s pause). */
    private final Map<String, Long> boughtAt = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * {@code modules.buyLimit} (the leveling configs, a user's choice 2026-11-27): {item: {keepCredits, everyMin}} — the item
     * is bought only while the credits are at least {@code keepCredits} and not again within {@code everyMin} minutes (100
     * ECO-10 cost 150 000 credits: on 2026-11-26 st1 bought them 5 times in 50 minutes). {@code null}: may be bought.
     */
    private String limited(WorldSnapshot w, String item) {
        com.fasterxml.jackson.databind.JsonNode n = ctx.config().get().modules().get("buyLimit");
        if (n == null || !n.path(item).isObject()) {
            return null;
        }
        com.fasterxml.jackson.databind.JsonNode l = n.path(item);
        long keep = l.path("keepCredits").asLong(0);
        double credits = credits(w);
        if (keep > 0 && credits < keep) {
            return "кредитов " + orbit.world.Num.group(credits) + " — покупаю, когда будет от " + orbit.world.Num.group(keep);
        }
        long every = l.path("everyMin").asLong(0) * 60_000L;
        Long last = boughtAt.get(item);
        if (every > 0 && last != null && System.currentTimeMillis() - last < every) {
            return "куплено " + (System.currentTimeMillis() - last) / 60_000 + " мин назад — не чаще раза в "
                    + every / 60_000 + " мин";
        }
        return null;
    }

    void setShop(ShopAccess s) {
        shop = s;
    }

    /** The config's purchases as last seen: a change of them is acted on at once. */
    private BotConfig.Buy lastBuy;

    /**
     * {@code modules.buyScale} (the leveling configs, a user's choice 2026-11-26): {item: {below, amount}} per laser on the
     * ship — the laser ammunition bought by the ship's growth (3 000 at a time ran out within minutes with 15 lasers); the
     * config's own numbers while the lasers are not known.
     */
    /**
     * {@code modules.buyNeeds} (the leveling configs, a user's choice 2026-11-26): {item: CPU item} — the item is bought only
     * while that CPU is in the game and switched on (the launcher's ECO-10 only with the launcher CPU RLLB-X on).
     */
    private boolean neededOn(WorldSnapshot w, String item) {
        com.fasterxml.jackson.databind.JsonNode n = ctx.config().get().modules().get("buyNeeds");
        if (n == null || !n.path(item).isTextual()) {
            return true;
        }
        WorldSnapshot.Item cpu = w.items().get(n.path(item).asText());
        return cpu != null && cpu.selected();
    }

    private BotConfig.BuyRule scaled(BotConfig.BuyRule r) {
        com.fasterxml.jackson.databind.JsonNode s = ctx.config().get().modules().get("buyScale");
        int lasers = ctx.stats().lasers;
        if (s == null || lasers <= 0 || !s.path(r.item()).isObject()) {
            return r;
        }
        com.fasterxml.jackson.databind.JsonNode k = s.path(r.item());
        return new BotConfig.BuyRule(r.item(), k.path("below").asInt(0) * lasers, k.path("amount").asInt(0) * lasers);
    }

    void tick() {
        long now = ctx.now();
        BotConfig.Buy seen = ctx.config().get().buy();
        if (lastBuy != null && !seen.equals(lastBuy)) {
            // the purchases were changed (2026-11-19: the uridium reserve lowered on int7, the boosters
            // still waited 10 min for their next try): the waits are over, checked now
            log.info("Покупки в конфиге изменены — проверяю сразу");
            triedAt.clear();
            nextCheck = Math.min(nextCheck, now);
        }
        lastBuy = seen;
        if (now < nextCheck) {
            return;
        }
        nextCheck = now + CHECK_MS * 1_000_000;
        BotConfig.Buy b = seen;
        if (!b.enabled()) {
            return;
        }
        WorldSnapshot w = ctx.world().get();
        if (w.hero().id() == 0 || w.items().isEmpty()) {
            return; // not in game or the item bar not received yet
        }
        bought.forEach((id, p) -> {
            WorldSnapshot.Item now2 = w.items().get(ItemCatalog.barItem(id));
            if (now2 != null && now2.counter() > p[0]) {
                bought.remove(id);
            } else if (now - (long) p[1] > EFFECT_MS * 1_000_000 && firedWithin(id, (long) p[2], ctx.stats().lasers)) {
                bought.remove(id); // shot away while waiting: no sign either way
            } else if (now - (long) p[1] > EFFECT_MS * 1_000_000) {
                bought.remove(id);
                noEffect.add(id);
                saveNoEffect(id);
                log.warn("{}: куплено, но в игре не прибавилось — в этом запуске больше не покупаю (проверьте ангар)",
                        ItemCatalog.label(id));
            }
        });
        for (BotConfig.BuyRule raw : b.items()) {
            BotConfig.BuyRule r = scaled(raw);
            if (noEffect.contains(r.item())) {
                continue;
            }
            if (!neededOn(w, r.item())) {
                continue;
            }
            String limit = limited(w, r.item());
            if (limit != null) {
                if (told.add("l:" + r.item())) {
                    log.info("{}: {}", ItemCatalog.label(r.item()), limit); // once till it may be bought again
                }
                continue;
            }
            told.remove("l:" + r.item());
            WorldSnapshot.Item item = w.items().get(ItemCatalog.barItem(r.item()));
            boolean noCloak = item == null && ItemCatalog.CLOAKS.contains(r.item()) && !w.items().isEmpty();
            if (noCloak) {
                // a pilot with no cloaking CPU may get no slot for it at all: the item bar is there, the CPU is not — none
                // (bought once: no slot after it either and noEffect stops the buying)
                item = new WorldSnapshot.Item(r.item(), "cpus", 0, 0, false, false, false, false, false, false, 0, 0, 0, 0, 0);
            }
            if (item == null) {
                // unknown is not zero: never buy what the game has not counted
                if (told.add("?" + r.item())) {
                    log.warn("{}: игра не прислала количество — не покупаю", ItemCatalog.label(r.item()));
                }
                continue;
            }
            double left = item.counter();
            if (left >= r.below()) {
                told.remove(r.item());
                continue;
            }
            if (shop == null) {
                if (told.add(r.item())) {
                    log.warn("Пора докупить {}: осталось {}, по конфигу купить {} (магазин недоступен)",
                            ItemCatalog.label(r.item()), (long) left, r.amount());
                }
                continue;
            }
            Long last = triedAt.get(r.item());
            if (last != null && now - last < RETRY_MS * 1_000_000) {
                continue;
            }
            if (busy.compareAndSet(false, true)) {
                triedAt.put(r.item(), now);
                double credits = credits(w);
                double uridium = uridium(w);
                ShopAccess s = shop;
                worker.execute(() -> {
                    if (logContext != null) {
                        org.slf4j.MDC.setContextMap(logContext);
                    }
                    try {
                        buy(s, r, (long) left, credits, uridium, b);
                    } finally {
                        busy.set(false);
                    }
                });
            }
            return; // one purchase at a time
        }
        boosters(b, w, now);
    }

    /**
     * The config's boosters: one whose time left is under {@code boosterMinutes} (or that has run out) is
     * bought again, for uridium, never below the uridium to keep. Nothing before the game has listed the
     * boosters: unknown is not "none left".
     */
    private void boosters(BotConfig.Buy b, WorldSnapshot w, long now) {
        if (b.boosters().isEmpty()) {
            return;
        }
        if (!w.boosters().known()) {
            if (told.add("?boosters")) {
                log.info("Бустеры: игра ещё не прислала, какие активны — жду");
            }
            return;
        }
        for (String code : b.boosters()) {
            WorldSnapshot.Booster have = w.boosters().active().get(ItemCatalog.boosterLootId(code));
            long left = have == null ? 0 : Math.max(0, have.secondsLeftAt(now));
            if (left > 0 && left >= b.boosterMinutes() * 60L) {
                told.remove("b:" + code);
                continue;
            }
            if (shop == null) {
                if (told.add("b:" + code)) {
                    log.warn("Пора купить бустер {} (магазин недоступен)", code);
                }
                continue;
            }
            Long last = triedAt.get("b:" + code);
            if (last != null && now - last < RETRY_MS * 1_000_000) {
                continue;
            }
            if (busy.compareAndSet(false, true)) {
                triedAt.put("b:" + code, now);
                double uridium = uridium(w);
                ShopAccess s = shop;
                worker.execute(() -> {
                    if (logContext != null) {
                        org.slf4j.MDC.setContextMap(logContext);
                    }
                    try {
                        buyBooster(s, code, left, uridium, b);
                    } finally {
                        busy.set(false);
                    }
                });
            }
            return; // one purchase at a time
        }
    }

    private void buyBooster(ShopAccess s, String code, long left, double uridium, BotConfig.Buy b) {
        try {
            ShopAccess.Offer o = s.boosterOffers().get(code);
            if (o == null || o.price() <= 0) {
                log.warn("Бустер {}: в магазине такого нет — не покупаю", code);
                return;
            }
            if (!o.uridium()) {
                log.warn("Бустер {}: продаётся не за уридий — не покупаю", code);
                return;
            }
            if (uridium <= 0) {
                log.warn("Бустер {}: баланс уридия неизвестен — не покупаю", code);
                return;
            }
            if (uridium - o.price() < b.keepUridium()) {
                log.warn("Бустер {}: не хватает уридия (есть {}, запас {}, цена {})", code, orbit.world.Num.group(uridium),
                        orbit.world.Num.group(b.keepUridium()), orbit.world.Num.group(o.price()));
                return;
            }
            log.info("Покупаю бустер {} за {} уридия (у бустера оставалось {}, уридия {})", code, orbit.world.Num.group(o.price()),
                    clock(left), orbit.world.Num.group(uridium));
            String answer = s.buy(o.item(), 1);
            told(answer, -1, uridium);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("Бустер {}: покупка не удалась — {}", code, e.getMessage());
        }
    }

    /*
     * The balance the shop reported after the last purchase. The game does not always send the new balance
     * for a website purchase, so until its own figure changes the shop's counts.
     */
    private volatile double shopCredits = -1;
    private volatile double shopUridium = -1;
    private volatile double gameCreditsThen = -1;
    private volatile double gameUridiumThen = -1;

    private double credits(WorldSnapshot w) {
        double game = w.hero().credits();
        return shopCredits >= 0 && game == gameCreditsThen ? shopCredits : game;
    }

    private double uridium(WorldSnapshot w) {
        double game = w.hero().uridium();
        return shopUridium >= 0 && game == gameUridiumThen ? shopUridium : game;
    }

    private static final java.util.regex.Pattern BALANCE_URIDIUM =
            java.util.regex.Pattern.compile("\"uridium\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern BALANCE_CREDITS =
            java.util.regex.Pattern.compile("\"credits\"\\s*:\\s*(\\d+)");
    private static final java.util.regex.Pattern MESSAGE =
            java.util.regex.Pattern.compile("\"message\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** The shop's answer in words, what the purchase cost and what is left (its {@code userBalance}). */
    private void told(String answer, double creditsBefore, double uridiumBefore) {
        java.util.regex.Matcher m = MESSAGE.matcher(answer);
        log.info("Магазин: {}", m.find() ? unescape(m.group(1)) : answer);
        WorldSnapshot w = ctx.world().get();
        java.util.regex.Matcher u = BALANCE_URIDIUM.matcher(answer);
        if (u.find()) {
            double left = Double.parseDouble(u.group(1));
            if (uridiumBefore > left) {
                log.info("Потрачено: {} уридия (осталось {})", orbit.world.Num.group(uridiumBefore - left),
                        orbit.world.Num.group(left));
            }
            shopUridium = left;
            gameUridiumThen = w.hero().uridium();
        }
        java.util.regex.Matcher c = BALANCE_CREDITS.matcher(answer);
        if (c.find()) {
            double left = Double.parseDouble(c.group(1));
            if (creditsBefore > left) {
                log.info("Потрачено: {} кредитов (осталось {})", orbit.world.Num.group(creditsBefore - left),
                        orbit.world.Num.group(left));
            }
            shopCredits = left;
            gameCreditsThen = w.hero().credits();
        }
    }

    /** JSON string escapes back to text (a backslash-u escape of a Cyrillic letter → the letter). */
    static String unescape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '\\' && i + 1 < s.length()) {
                char n = s.charAt(++i);
                if (n == 'u' && i + 4 < s.length()) {
                    sb.append((char) Integer.parseInt(s.substring(i + 1, i + 5), 16));
                    i += 4;
                } else {
                    sb.append(n == 'n' ? ' ' : n);
                }
            } else {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    static String clock(long seconds) {
        return seconds <= 0 ? "0:00:00" : String.format("%d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60);
    }

    private void buy(ShopAccess s, BotConfig.BuyRule r, long left, double credits, double uridium, BotConfig.Buy b) {
        String name = ItemCatalog.label(r.item());
        try {
            ShopAccess.Offer o = s.offers().get(r.item());
            if (o == null || o.price() < 0) {
                log.warn("{}: в магазине такого нет — не покупаю", name);
                return;
            }
            int amount = r.amount();
            if (o.maxAmount() > 0) {
                amount = Math.min(amount, o.maxAmount());
            }
            double balance = o.uridium() ? uridium : credits;
            double keep = o.uridium() ? b.keepUridium() : b.keepCredits();
            String currency = o.uridium() ? "уридия" : "кредитов";
            if (balance <= 0) {
                log.warn("{}: баланс {} неизвестен — не покупаю", name, currency);
                return;
            }
            double spendable = balance - keep;
            if (o.price() > 0 && Math.ceil(o.price() * amount) > spendable) {
                amount = (int) Math.floor(spendable / o.price());
            }
            if (amount <= 0) {
                log.warn("{}: не хватает {} (есть {}, запас {}, цена {} за шт.)", name, currency, (long) balance,
                        (long) keep, o.price());
                return;
            }
            long cost = (long) Math.ceil(o.price() * amount);
            log.info("Покупаю {} ×{} за {} {} (осталось {})", name, amount, cost, currency, left);
            String answer = s.buy(r.item(), amount);
            if (!fired(r.item())) {
                bought.put(r.item(), new double[] {left, ctx.now(), amount});
            }
            boughtAt.put(r.item(), System.currentTimeMillis());
            told(answer, credits, uridium);
        } catch (java.io.IOException | RuntimeException e) {
            log.warn("{}: покупка не удалась — {}", name, e.getMessage());
        }
    }
}
