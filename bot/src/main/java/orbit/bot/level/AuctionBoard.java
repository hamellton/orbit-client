package orbit.bot.level;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The website auction as its page shows it (docs/recon/SHOP-AUCTION.md, 2026-11-25): the lots of the hourly auction on
 * {@code indexInternal.es?action=internalAuction}, the daily and weekly ones from {@code ajax/auction.php
 * command=getAuctionList list=current} ({@code {code: <html>}}, the same rows), and the time left from the page's script
 * ({@code counterHour/Day/Week}). Bids are credits; «buy now» is uridium.
 *
 * @param secondsLeft per auction ({@code hour}, {@code day}, {@code week}) the seconds till it ends, {@code -1} unknown
 * @param token       the page's {@code reloadToken} the bid form sends ({@code ""} none: only the page has it)
 * @param button      the bid button's value the form sends ({@code ПОСТАВИТЬ} on a Russian page)
 */
public record AuctionBoard(List<Lot> lots, java.util.Map<String, Long> secondsLeft, String token, String button) {

    public AuctionBoard(List<Lot> lots, java.util.Map<String, Long> secondsLeft) {
        this(lots, secondsLeft, "", "");
    }

    /** The bid with no rival: every lot nobody else wanted went for it (12 hourly auctions, 2026-11-25). */
    public static final long MIN_BID = 10_000;

    public AuctionBoard {
        lots = List.copyOf(lots);
        secondsLeft = java.util.Map.copyOf(secondsLeft);
        token = token == null ? "" : token;
        button = button == null ? "" : button;
    }

    /**
     * The bid form as the page sends it ({@code placeBid}: {@code POST indexInternal.es?action=internalAuction&reloadToken=…}),
     * docs/recon/SHOP-AUCTION.md: token, auction type, {@code subAction=bid}, loot id, item key, credits, the button.
     */
    public java.util.Map<String, String> bidForm(Lot lot, long credits) {
        java.util.Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("reloadToken", token);
        f.put("auctionType", lot.auction());
        f.put("subAction", "bid");
        f.put("lootId", lot.loot());
        f.put("itemId", lot.key());
        f.put("credits", String.valueOf(credits));
        f.put("auction_buy_button", button.isEmpty() ? "BID" : button);
        return f;
    }

    /**
     * One lot.
     *
     * @param key        the page's {@code itemKey} ({@code item_hour_39}), the bid's {@code itemId}
     * @param auction    {@code hour}, {@code day} or {@code week}
     * @param highest    someone leads (the «highest bidder» column is not {@code -})
     * @param current    the bid now, credits
     * @param mine       my bid, credits ({@code 0} none)
     * @param buyNow     the instant price in uridium ({@code -1}: no instant buy)
     */
    public record Lot(String key, String auction, String loot, String name, String section, boolean highest, long current,
                      long mine, long buyNow) {

        /** I lead: my bid is the current one. */
        public boolean leading() {
            return mine > 0 && mine >= current;
        }

        /** I bid and someone bid more. */
        public boolean outbid() {
            return mine > 0 && current > mine;
        }
    }

    public long secondsLeft(String auction) {
        return secondsLeft.getOrDefault(auction, -1L);
    }

    public List<Lot> of(String auction) {
        return lots.stream().filter(l -> l.auction().equals(auction)).toList();
    }

    private static final Pattern ROW = Pattern.compile(
            "itemKey=\"(item_(hour|day|week)_\\d+)\".*?auction_item_name_col\">\\s*(.*?)\\s*</td>.*?auction_item_type\">\\s*(.*?)\\s*</td>"
                    + ".*?auction_item_highest\"[^>]*>\\s*(.*?)\\s*</td>.*?auction_item_current\">\\s*(.*?)\\s*</td>"
                    + ".*?auction_item_you\">\\s*(.*?)\\s*</td>.*?auction_item_instant\">\\s*([^<]*?)\\s*<"
                    + ".*?_lootId\" value=\"([^\"]*)\".*?_instantBuy\" value=\"([^\"]*)\"",
            Pattern.DOTALL);

    private static final Pattern COUNTER = Pattern.compile(
            "var\\s+counter(Hour|Day|Week)\\s*=\\s*\\{\\s*second:\\s*(\\d+),\\s*minute:\\s*(\\d+),\\s*hour:\\s*(\\d+),\\s*day:\\s*(\\d+)");

    /** The lots and counters of one page or list answer's HTML (any of hour, day, week). */
    public static AuctionBoard parse(String html) {
        String s = html == null ? "" : html;
        List<Lot> lots = new ArrayList<>();
        java.util.Set<String> keys = new java.util.HashSet<>();
        Matcher m = ROW.matcher(s);
        while (m.find()) {
            if (!keys.add(m.group(1))) {
                continue; // the same lot again in the page's «my bids» table (live 2026-11-25: led twice in the plan)
            }
            long buy = "1".equals(m.group(10)) ? amount(m.group(8)) : -1;
            lots.add(new Lot(m.group(1), m.group(2), m.group(9), text(m.group(3)), text(m.group(4)),
                    !text(m.group(5)).equals("-") && !text(m.group(5)).isEmpty(), amount(m.group(6)), amount(m.group(7)), buy));
        }
        java.util.Map<String, Long> left = new java.util.HashMap<>();
        Matcher c = COUNTER.matcher(s);
        while (c.find()) {
            long sec = Long.parseLong(c.group(2)) + 60 * Long.parseLong(c.group(3)) + 3600 * Long.parseLong(c.group(4))
                    + 86_400 * Long.parseLong(c.group(5));
            left.put(c.group(1).toLowerCase(java.util.Locale.ROOT), sec);
        }
        Matcher t = Pattern.compile("reloadToken\\s*=\\s*'([0-9a-fA-F]+)'").matcher(s);
        Matcher b = Pattern.compile("id=\"auction_place_bid\"\\s+value=\"([^\"]*)\"").matcher(s);
        return new AuctionBoard(lots, left, t.find() ? t.group(1) : "", b.find() ? b.group(1) : "");
    }

    /** This board with another's lots added (the daily or weekly list); the counters kept. */
    public AuctionBoard with(AuctionBoard other) {
        List<Lot> all = new ArrayList<>(lots);
        other.lots().stream().filter(l -> all.stream().noneMatch(x -> x.key().equals(l.key()))).forEach(all::add);
        java.util.Map<String, Long> left = new java.util.HashMap<>(other.secondsLeft());
        left.putAll(secondsLeft);
        return new AuctionBoard(all, left, token.isEmpty() ? other.token() : token, button.isEmpty() ? other.button() : button);
    }

    /** {@code 222.706.666} → 222706666; {@code -1} unreadable. */
    static long amount(String s) {
        String d = s == null ? "" : s.replaceAll("[^0-9]", "");
        if (d.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(d);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String text(String s) {
        String t = (s == null ? "" : s).replaceAll("<[^>]+>", " ").replace("&quot;", "\"").replace("&#039;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ").replace("&amp;", "&");
        return t.replaceAll("\\s+", " ").strip();
    }
}
