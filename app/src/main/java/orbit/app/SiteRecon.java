package orbit.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import orbit.auth.AccountLogin;
import orbit.auth.AuthClient;
import orbit.auth.GameSession;
import orbit.auth.HttpSession;
import orbit.auth.proxy.ProxyRoute;
import orbit.secrets.MasterPassword;
import orbit.secrets.SecretMask;
import orbit.secrets.Vault;

/**
 * A development tool for the leveling recon (docs/LEVELING.md, docs/recon/SHOP-AUCTION.md): logs the account in on the
 * website and only reads — every shop tab, the auction page, the hangar list and the active hangar's view — with a
 * person's pause between pages, and saves each answer (secrets masked) to {@code local/recon/<date>-<account>/}. It
 * buys, bids and moves nothing. Usage: {@code SiteRecon <account> [page …]} with {@code ORBIT_MASTER_PASSWORD} set or
 * asked; a page is a path after the site's origin ({@code indexInternal.es?action=…}); without pages the default set.
 * {@code SiteRecon <account> auction}: the auction page and its lists as the page's own script asks for them
 * ({@code js/auction.js}: {@code POST ajax/auction.php command=getAuctionList category=hour|day|week
 * list=current|mybids|history}) — reading only.
 */
public final class SiteRecon {

    static final List<String> SHOP_TABS = List.of("Ships", "Laser", "Generator", "Drones", "Ammo", "Booster", "PetGear",
            "PetProtocols", "ShipModel", "Customization", "Specials", "Extras");

    private SiteRecon() {
    }

    public static void main(String[] args) throws Exception {
        String account = args.length > 0 ? args[0] : "7";
        boolean auction = args.length == 2 && "auction".equals(args[1]);
        boolean fit = args.length == 2 && "fit".equals(args[1]); // the leveling fitting, out of the game
        List<String> pages = new ArrayList<>();
        if (auction) {
            pages.add("indexInternal.es?action=internalAuction");
        }
        for (int i = auction ? 2 : 1; i < args.length; i++) {
            pages.add(args[i]);
        }
        if (pages.isEmpty()) {
            SHOP_TABS.forEach(t -> pages.add("indexInternal.es?action=internalDock&tpl=internalDock" + t));
            pages.add("indexInternal.es?action=internalAuction");
        }
        Vault vault = Vault.open(Path.of("local/vault.bin"), MasterPassword.obtain(false));
        orbit.secrets.Account a = vault.get(OrbitMain.loginOf(account));
        if (a == null) {
            System.err.println("Аккаунта нет в хранилище: " + account);
            System.exit(1);
        }
        Path dir = Path.of("local", "recon", java.time.LocalDate.now() + "-" + account.replace('@', '-')
                + (auction ? "-auction-" + java.time.LocalTime.now().withNano(0).toString().replace(":", "") : ""));
        Files.createDirectories(dir);
        java.util.Random random = new java.util.Random();
        var spec = a.proxySpec();
        try (ProxyRoute route = spec.direct() ? ProxyRoute.none() : ProxyRoute.of(spec)) {
            route.verify();
            AuthClient auth = new AuthClient(new HttpSession("", route.httpProxy()), AuthClient.Endpoints.production());
            GameSession session = new AccountLogin(auth, vault, OrbitMain.loginOf(account), OrbitMain.serverOf(account))
                    .login();
            String origin = session.host();
            if (fit) {
                org.slf4j.MDC.put("account", account);
                int n = LevelWatch.fitOffline(new orbit.auth.Hangars(auth.http(), origin), account);
                System.out.println("Раскладка вне игры: перекладок " + n + " (подробно — logs/leveling-" + account + ".log)");
                return;
            }
            String start = origin + "/indexInternal.es?action=internalStart";
            int n = 0;
            for (String page : pages) {
                pause(random);
                HttpSession.Response r = auth.http().get(origin + "/" + page, start);
                String name = String.format("%02d-%s.html", ++n, page.replaceAll("[^A-Za-z0-9]+", "_"));
                save(dir.resolve(name), r.body());
                System.out.println(page + ": HTTP " + r.status() + ", " + (r.body() == null ? 0 : r.body().length())
                        + " символов → " + name);
            }
            if (auction) {
                String referer = origin + "/indexInternal.es?action=internalAuction";
                for (String category : List.of("hour", "day", "week")) {
                    for (String list : List.of("current", "mybids", "history")) {
                        pause(random);
                        java.util.Map<String, String> form = new java.util.LinkedHashMap<>();
                        form.put("command", "getAuctionList");
                        form.put("category", category);
                        form.put("list", list);
                        HttpSession.Response r = auth.http().postForm(origin + "/ajax/auction.php", form, referer);
                        String name = String.format("%02d-auction-%s-%s.json", ++n, category, list);
                        save(dir.resolve(name), r.body());
                        System.out.println("auction " + category + " " + list + ": HTTP " + r.status() + ", "
                                + (r.body() == null ? 0 : r.body().length()) + " символов → " + name);
                    }
                }
            }
            if (args.length <= 1) {
                orbit.auth.Hangars hangars = new orbit.auth.Hangars(auth.http(), origin);
                pause(random);
                var list = hangars.listJson();
                save(dir.resolve("hangar-list.json"), list.toPrettyString());
                int active = orbit.bot.level.SiteHangars.list(list).activeHangar();
                System.out.println("getHangarList: активный ангар " + active);
                if (active != 0) {
                    pause(random);
                    save(dir.resolve("hangar-" + active + ".json"), hangars.hangarJson(active).toPrettyString());
                    System.out.println("getHangar: сохранён");
                }
            }
            System.out.println("Готово: " + dir);
        }
    }

    private static void save(Path f, String body) throws java.io.IOException {
        Files.writeString(f, SecretMask.mask(body == null ? "" : body), StandardCharsets.UTF_8);
    }

    /** A person's pause between two pages: 4–9 s. */
    private static void pause(java.util.Random random) throws InterruptedException {
        Thread.sleep(4_000 + random.nextInt(5_000));
    }
}
