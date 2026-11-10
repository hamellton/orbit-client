package orbit.config;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Items a config picks by name (as AstraBot does): laser ammunition, rockets, rocket launcher
 * ammunition, drone formations, pet modes. Ids are the client's menu item ids (decompiled client
 * 2026-11-04, the same ids {@code MenuActionRequest} sends).
 */
public final class ItemCatalog {

    public record Item(String id, String label) {

        @Override
        public String toString() {
            return label;
        }
    }

    /** Boosters of the website shop by code ({@code internalDockBooster}); in game {@code booster_<code>}. */
    public static final List<Item> BOOSTERS = List.of(
            new Item("DMG-B01", "DMG-B01 — урон"), new Item("DMG-B02", "DMG-B02 — урон"),
            new Item("SHD-B01", "SHD-B01 — щит"), new Item("SHD-B02", "SHD-B02 — щит"),
            new Item("HP-B01", "HP-B01 — прочность"), new Item("HP-B02", "HP-B02 — прочность"),
            new Item("REP-B01", "REP-B01 — ремонт"), new Item("REP-B02", "REP-B02 — ремонт"),
            new Item("SREG-B01", "SREG-B01 — восстановление щита"), new Item("SREG-B02", "SREG-B02 — восстановление щита"),
            new Item("EP-B01", "EP-B01 — опыт"), new Item("EP-B02", "EP-B02 — опыт"),
            new Item("HON-B01", "HON-B01 — честь"), new Item("HON-B02", "HON-B02 — честь"),
            new Item("RES-B01", "RES-B01 — ресурсы"), new Item("RES-B02", "RES-B02 — ресурсы"),
            new Item("CD-B01", "CD-B01 — перезарядка способностей"), new Item("CD-B02", "CD-B02 — перезарядка способностей"),
            new Item("PRC-B00", "PRC-B00 — точность"), new Item("DMG-H01", "DMG-H01 — урон (H)"),
            new Item("DMG-PVP01", "DMG-PVP01 — урон по игрокам"));

    /** The game's loot id of a shop booster: {@code DMG-B01} → {@code booster_dmg-b01}. */
    public static String boosterLootId(String code) {
        return "booster_" + code.toLowerCase(java.util.Locale.ROOT);
    }

    public static final List<Item> LASERS = ammo("ammunition_laser_", "lcb-10", "mcb-25", "mcb-50", "ucb-100",
            "rsb-75", "sab-50", "cbo-100", "job-100", "rb-214", "pib-100", "idb-125", "sbl-100", "vb-142",
            "a-bl", "emaa-20", "rcb-140");

    public static final List<Item> ROCKETS = ammo("ammunition_rocket_", "r-310", "plt-2026", "plt-2021", "plt-3030",
            "plt-2030", "bdr-1211", "agt-500");

    public static final List<Item> LAUNCHER = ammo("ammunition_rocketlauncher_", "eco-10", "hstrm-01", "ubr-100",
            "sar-01", "sar-02", "cbr", "pir-100", "obr-100", "bdr1212");

    public static final List<Item> FORMATIONS;

    /** Pet modes ({@code PetGearTypeModule}) the configs use. */
    public static final List<Item> PET_GEARS = List.of(
            new Item("PASSIVE", "Пассивный"), new Item("GUARD", "Охрана"), new Item("AUTO_LOOT", "Автосбор"),
            new Item("AUTO_RESOURCE_COLLECTION", "Сбор ресурсов"), new Item("ENEMY_LOCATOR", "Поиск врагов (Enemy Locator)"),
            new Item("RESOURCE_LOCATOR", "Поиск ресурсов"), new Item("TRADE_POD", "Торговый модуль"),
            new Item("REPAIR_PET", "Ремонт питомца"), new Item("KAMIKAZE", "Камикадзе"), new Item("COMBO_SHIP_REPAIR", "Ремонт корабля"),
            new Item("COMBO_GUARD", "Комбо-охрана"), new Item("FRIENDLY_SACRIFICE", "Жертва ради союзника"),
            new Item("HP_LINK", "Связь HP"), new Item("MEGA_MINE", "Мега-мина"), new Item("COMBAT_BEACON", "Боевой маяк"),
            new Item("HP_BEACON", "Маяк HP"), new Item("HEAT_RELEASE", "Сброс тепла"));

    static {
        Map<String, String> f = new LinkedHashMap<>();
        f.put("f-01-tu", "Turtle");
        f.put("f-02-ar", "Arrow");
        f.put("f-03-la", "Lance");
        f.put("f-04-st", "Star");
        f.put("f-05-pi", "Pincer");
        f.put("f-06-da", "Double Arrow");
        f.put("f-07-di", "Diamond");
        f.put("f-08-ch", "Chevron");
        f.put("f-09-mo", "Moth");
        f.put("f-10-cr", "Crab");
        f.put("f-11-he", "Heart");
        f.put("f-12-ba", "Barrage");
        f.put("f-13-bt", "Bat");
        f.put("f-3d-dm", "Dome");
        f.put("f-3d-dr", "Drill");
        f.put("f-3d-rg", "Ring");
        f.put("f-3d-vt", "Veteran");
        f.put("f-3d-wl", "Wheel");
        f.put("f-3d-wv", "Wave");
        f.put("f-3d-x", "X");
        FORMATIONS = f.entrySet().stream()
                .map(e -> new Item("drone_formation_" + e.getKey(), e.getValue() + " (" + e.getKey().toUpperCase() + ")"))
                .toList();
    }

    private ItemCatalog() {
    }

    private static List<Item> ammo(String prefix, String... names) {
        return java.util.Arrays.stream(names).map(n -> new Item(prefix + n, n.toUpperCase())).toList();
    }

    /** A readable name of an item id: the catalog label or the raw id; {@code —} for none. */
    public static String label(String id) {
        if (id == null) {
            return "—";
        }
        for (List<Item> list : List.of(LASERS, ROCKETS, LAUNCHER, FORMATIONS, PET_GEARS)) {
            for (Item i : list) {
                if (i.id().equals(id)) {
                    return i.label();
                }
            }
        }
        return id;
    }
}
