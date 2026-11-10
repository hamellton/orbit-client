package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.DayOfWeek;
import java.util.List;
import java.util.Map;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The tabs give back exactly the config they were loaded with (no silent changes on save). */
class SectionsTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    static List<Section> sections() {
        MapCatalog maps = MapCatalog.bundled();
        return List.of(new GeneralSection(maps), new KillSection(maps), new CollectSection(), new PetSection(),
                new SafetySection(), new ZonesSection(maps), new CubikonSection(), new GateSection());
    }

    static BotConfig roundTrip(BotConfig c) {
        List<Section> s = sections();
        s.forEach(x -> x.load(c));
        BotConfig out = c;
        for (Section x : s) {
            out = x.apply(out);
        }
        return out;
    }

    static BotConfig sample() throws Exception {
        List<String> rows = new java.util.ArrayList<>();
        for (int y = 0; y < 30; y++) {
            rows.add(y < 3 ? "###" + ".".repeat(27) : ".".repeat(30));
        }
        BotConfig c = BotConfig.empty()
                .withModule(BotConfig.Module.CUBIKON)
                .withGeneral(new BotConfig.General(26, new BotConfig.ShipMode(1, "drone_formation_f-01-tu"),
                        new BotConfig.ShipMode(2, "drone_formation_f-09-mo"),
                        new BotConfig.ShipMode(2, null), true, false, 300, false, true))
                .withNpcs(Map.of(
                        "-=[ Cubikon ]=-", new BotConfig.NpcRule(550, -100, true, Set.of("IGNORE_OWNERSHIP", "K"), Set.of(18, 22, 26, -1), null),
                        "-=[ Protegit ]=-", new BotConfig.NpcRule(500, 50, false, Set.of("PASSIVE"), Set.of(), "ammunition_laser_mcb-50")))
                .withLoot(new BotConfig.Loot("ammunition_laser_ucb-100", "ammunition_laser_sab-50",
                        0.92, 120_000, null, true, 15_500))
                .withCollect(new BotConfig.Collect(Map.of("BONUS_BOX", new BotConfig.BoxRule(true, 150, 1)), 400, true, true))
                .withPet(new BotConfig.Pet(true, "ENEMY_LOCATOR"))
                .withSafety(new BotConfig.Safety(0.5, 0.65, 0.05, 0.45, new BotConfig.ShipMode(2, "some_future_formation"),
                        new BotConfig.Heal(0.6, -1, -1), 1000, "BASE", 10, 10))
                .withZones(Map.of(26, new BotConfig.Zones(null, new BotConfig.ZoneGrid(30, rows))))
                .withModuleSettings("cubikon", Json.MAPPER.readTree("""
                        {"spots": [[7500, 3900], [13400, 3900], [13400, 7900], [7500, 7900]], "arriveDistance": 450,
                         "waitSeconds": 90, "collectBoxes": true, "boxesWhileCubikonHpAbove": 0.25, "waitKill": "ATTACKERS",
                         "waitKillRange": 1200, "spotMode": "LEARNED", "learnSpawns": false}"""))
                .withModuleSettings("gate", Json.MAPPER.readTree("{\"gateId\": 71, \"kamikaze\": {\"enabled\": true, \"minNpcs\": 5}}"))
                .withDescription("Кубы на 3-6");
        return new BotConfig(c.schema(), c.description(), c.module(), c.general(), c.npcs(), c.loot(), c.collect(), c.pet(),
                c.safety(), c.zones(), Map.of(26, List.of(new BotConfig.SafetySpot("PORTAL", 1000, 2000, "ALWAYS", "NEVER"))),
                c.modules(), Map.of("darkbot.GROUP", Json.MAPPER.readTree("{\"ACCEPT_INVITES\": false}")));
    }

    @Test
    void tabsKeepTheConfig() throws Exception {
        BotConfig c = sample();
        assertEquals(c, roundTrip(c));
    }

    @Test
    void emptyConfigStaysEmpty() {
        BotConfig c = BotConfig.empty();
        assertEquals(c, roundTrip(c));
    }

    @Test
    void badInputIsRefused() throws Exception {
        BotConfig c = sample().withSafety(new BotConfig.Safety(0.9, 0.5, 0.5, 0, null, null, 10, null, 10, 10));
        List<Section> s = sections();
        s.forEach(x -> x.load(c));
        assertThrows(IllegalArgumentException.class, () -> s.get(4).apply(c));
    }

    @Test
    void days() {
        assertEquals(Set.of(), Days.parse("все"));
        assertEquals(Set.of(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY),
                Days.parse("Пн-Пт"));
        assertEquals(Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY, DayOfWeek.MONDAY), Days.parse("Сб-Пн"));
        assertEquals("Пн-Пт", Days.text(Days.parse("пн-пт")));
        assertEquals("Сб, Вс", Days.text(Days.parse("Сб, Вс")));
        assertThrows(IllegalArgumentException.class, () -> Days.parse("Пн-Xx"));
    }
}
