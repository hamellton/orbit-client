package orbit.app;

import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import orbit.auth.AuthClient;
import orbit.bot.level.AccountState;
import orbit.bot.level.AuctionBoard;
import orbit.bot.level.BidPlanner;
import orbit.bot.level.LevelPlan;
import orbit.config.ConfigStore;
import orbit.game.GameConnection;

/**
 * The leveling mode's bids on the website auction (docs/LEVELING.md «Аукцион», with the user's consent 2026-11-25): while
 * the account plays a config of the folder {@link ConfigStore#LEVELING_FOLDER}, it wakes a few minutes before the hourly
 * auction ends, reads the hourly page and the daily list, places the bids {@link BidPlanner} gives for now (one form per
 * lot, as the page's {@code placeBid}), reads the page back to see each bid stand, and looks again till the end — an
 * outbid lot is bid on anew when the budget allows. Everything goes to the leveling log only.
 */
final class AuctionWatch {

    /** Wake this long before the hour ends (a random part on top). */
    static final long WAKE_S = 150;
    /** Between two looks inside the last minutes. */
    static final long LOOK_GAP_S = 45;
    /** No bid this close to the end: the page may not take it in time. */
    static final long LAST_S = 15;

    private AuctionWatch() {
    }

    static void start(AuthClient auth, GameConnection conn, WorldFeed feed, String account, Supplier<String> config,
                      BooleanSupplier alive) {
        java.util.Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread.ofVirtual().name("auction").start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            java.util.Random random = new java.util.Random();
            try {
                Thread.sleep(120_000 + random.nextInt(60_000)); // after the login's pages and the advisor's first look
                LevelPlan plan = LevelPlan.bundled();
                while (alive.getAsBoolean()) {
                    String cfg = config.get();
                    var session = conn.session();
                    if (cfg == null || session == null || session.host() == null
                            || !ConfigStore.LEVELING_FOLDER.equals(ConfigStore.openDefault().folderOf(cfg))) {
                        Thread.sleep(300_000);
                        continue;
                    }
                    String origin = session.host();
                    AuctionBoard board = read(auth, origin);
                    long left = board == null ? -1 : board.secondsLeft("hour");
                    if (left < 0) {
                        Thread.sleep(600_000);
                        continue;
                    }
                    long wake = WAKE_S + random.nextInt(30);
                    if (left > wake) {
                        Thread.sleep(Math.min(left - wake, 1_800) * 1_000);
                        continue;
                    }
                    window(auth, origin, feed, account, plan, alive);
                    // past the end: the next hour
                    Thread.sleep(120_000 + random.nextInt(60_000));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (RuntimeException e) {
                LevelWatch.LOG.error("аукцион: поток остановлен: {}", e.toString(), e);
            }
        });
    }

    /** The last minutes of an hour: bid, check, look again till the end. */
    private static void window(AuthClient auth, String origin, WorldFeed feed, String account, LevelPlan plan,
                               BooleanSupplier alive) throws InterruptedException {
        java.util.Random random = new java.util.Random();
        while (alive.getAsBoolean()) {
            AuctionBoard board = readWithDay(auth, origin);
            if (board == null) {
                return;
            }
            long left = board.secondsLeft("hour");
            if (left < LAST_S) {
                LevelWatch.LOG.info("аукцион: до конца часа {} с — больше не ставлю, итог — после часа по ангару", left);
                return;
            }
            AccountState state = current(account, feed);
            if (state == null) {
                LevelWatch.LOG.info("аукцион: снимка аккаунта ещё нет (советчик не обходил) — не ставлю");
                return;
            }
            BidPlanner.Plan p = BidPlanner.plan(state, plan, board);
            LevelWatch.LOG.info("аукцион, до конца часа {} с: {}", left, p.describe());
            for (BidPlanner.Bid b : p.bids()) {
                if (!b.now()) {
                    continue;
                }
                board = bid(auth, origin, board, b);
                if (board == null) {
                    return;
                }
                Thread.sleep(2_000 + random.nextInt(3_000));
            }
            Thread.sleep((LOOK_GAP_S + random.nextInt(15)) * 1_000);
        }
    }

    /** One bid: the form posted, the page that comes back read for the lot's «my bid». The new page (new token). */
    private static AuctionBoard bid(AuthClient auth, String origin, AuctionBoard board, BidPlanner.Bid b) {
        if (board.token().isEmpty()) {
            LevelWatch.LOG.warn("аукцион: на странице нет токена ставки — не ставлю");
            return null;
        }
        String url = origin + "/indexInternal.es?action=internalAuction&reloadToken=" + board.token();
        try {
            var r = auth.http().postForm(url, board.bidForm(b.lot(), b.credits()),
                    origin + "/indexInternal.es?action=internalAuction");
            AuctionBoard after = AuctionBoard.parse(r.body());
            AuctionBoard.Lot lot = after.lots().stream().filter(l -> l.key().equals(b.lot().key())).findFirst().orElse(null);
            String message = notice(r.body());
            LevelWatch.LOG.info("аукцион: ставка {} на {} ({}, {}, цель {}) — HTTP {}; лот теперь: ставка {}, моя {}{}", b.credits(),
                    b.lot().name(), b.lot().loot(), b.lot().auction(), b.goal(), r.status(),
                    lot == null ? "?" : lot.current(), lot == null ? "?" : lot.mine(),
                    message.isEmpty() ? "" : "; сайт: " + message);
            return after.token().isEmpty() ? null : after;
        } catch (java.io.IOException | RuntimeException e) {
            LevelWatch.LOG.warn("аукцион: ставка на {} не ушла ({})", b.lot().name(), e.getMessage());
            return null;
        }
    }

    /** The page's own message after a bid (an error or a success box), as text; {@code ""} none found. */
    static String notice(String html) {
        if (html == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile(
                "class=\"[^\"]*(?:error|success|message|infobox)[^\"]*\"[^>]*>(.*?)</(?:div|p|span|td)>",
                java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE).matcher(html);
        while (m.find()) {
            String t = m.group(1).replaceAll("<[^>]+>", " ").replaceAll("\\s+", " ").strip();
            if (!t.isEmpty() && t.length() < 300) {
                return t;
            }
        }
        return "";
    }

    /** The account as the advisor last read it, with the game's credits now (spent or earned since). */
    private static AccountState current(String account, WorldFeed feed) {
        AccountState s = LevelWatch.lastState(account);
        if (s == null) {
            return null;
        }
        var snap = feed.snapshot();
        if (snap == null || snap.hero() == null) {
            return s;
        }
        return new AccountState(snap.hero().level(), (long) snap.hero().credits(), (long) snap.hero().uridium(),
                s.activeHangar(), s.activeShip(), s.ownedShips(), s.slots(), s.fitted(), s.spare(), s.drones());
    }

    private static AuctionBoard read(AuthClient auth, String origin) {
        try {
            var page = auth.http().get(origin + "/indexInternal.es?action=internalAuction",
                    origin + "/indexInternal.es?action=internalStart");
            return AuctionBoard.parse(page.body());
        } catch (java.io.IOException | RuntimeException e) {
            LevelWatch.LOG.warn("аукцион не прочитан ({})", e.getMessage());
            return null;
        }
    }

    private static AuctionBoard readWithDay(AuthClient auth, String origin) throws InterruptedException {
        AuctionBoard board = read(auth, origin);
        if (board == null) {
            return null;
        }
        Thread.sleep(2_000);
        try {
            java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
            form.put("command", "getAuctionList");
            form.put("category", "day");
            form.put("list", "current");
            var day = auth.http().postForm(origin + "/ajax/auction.php", form, origin + "/indexInternal.es?action=internalAuction");
            String code = orbit.config.Json.MAPPER.readTree(day.body() == null ? "{}" : day.body()).path("code").asText("");
            return board.with(AuctionBoard.parse(code));
        } catch (java.io.IOException | RuntimeException e) {
            return board; // the hourly lots alone
        }
    }
}
