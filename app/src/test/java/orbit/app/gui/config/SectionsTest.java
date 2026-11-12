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
                new SafetySection(), new ZonesSection(maps), new CubikonSection(), new GateSection(), new BuySection(),
                new GroupSection(), new PalladiumSection(), new QuestsSection());
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
                        new BotConfig.ShipMode(2, null), true, false, 300, false, true, true, false))
                .withNpcs(Map.of(
                        "-=[ Cubikon ]=-", new BotConfig.NpcRule(550, -100, true, Set.of("IGNORE_OWNERSHIP", "K"), Set.of(18, 22, 26, -1), null),
                        "-=[ Protegit ]=-", new BotConfig.NpcRule(500, 50, false, Set.of("PASSIVE"), Set.of(), "ammunition_laser_mcb-50")))
                .withLoot(new BotConfig.Loot("ammunition_laser_ucb-100", "ammunition_laser_sab-50",
                        0.92, 120_000, null, true, 15_500, "ammunition_rocket_plt-2021", true, true,
                        List.of("ammunition_laser_mcb-25", "ammunition_laser_lcb-10")))
                .withCollect(new BotConfig.Collect(Map.of("BONUS_BOX", new BotConfig.BoxRule(true, 150, 1)), 400, true, true))
                .withPet(new BotConfig.Pet(true, "ENEMY_LOCATOR"))
                .withSafety(new BotConfig.Safety(0.5, 0.65, 0.05, 0.45, new BotConfig.ShipMode(2, "some_future_formation"),
                        new BotConfig.Heal(0.6, -1, -1), 1000, "BASE", 10, 10))
                .withZones(Map.of(26, new BotConfig.Zones(null, new BotConfig.ZoneGrid(30, rows))))
                .withModuleSettings("cubikon", Json.MAPPER.readTree("""
                        {"spots": [[7500, 3900], [13400, 3900], [13400, 7900], [7500, 7900]], "arriveDistance": 450,
                         "waitSeconds": 90, "collectBoxes": true, "boxesDuringFight": false, "boxesWhileCubikonHpAbove": 0.25, "waitKill": "ATTACKERS",
                         "waitKillRange": 1200, "chaseFromSpot": true, "spotMode": "LEARNED", "learnSpawns": false,
                         "maps": [22, 26], "mapMode": "ROUND",
                         "mapSchedule": [{"from": "08:00", "to": "12:00", "maps": [18]}, {"from": "22:00", "to": "02:00", "maps": [18, 26]}],
                         "travel": "JUMP_CPU", "jumpUridiumPerDay": 3000}"""))
                .withModuleSettings("gate", Json.MAPPER.readTree("{\"gateId\": 71, \"kamikaze\": {\"enabled\": true, \"minNpcs\": 5}}"))
                .withDescription("Кубы на 3-6");
        return new BotConfig(c.schema(), c.description(), c.module(), c.general(), c.npcs(), c.loot(), c.collect(), c.pet(),
                c.safety(), c.zones(), Map.of(26, List.of(new BotConfig.SafetySpot("PORTAL", 1000, 2000, "ALWAYS", "NEVER"))),
                c.modules(), Map.of("darkbot.GROUP", Json.MAPPER.readTree("{\"ACCEPT_INVITES\": false}")),
                new BotConfig.Buy(true, List.of(new BotConfig.BuyRule("ammunition_laser_lcb-10", 10_000, 50_000),
                        new BotConfig.BuyRule("ammunition_rocket_plt-2026", 200, 1_000)), 1_000_000, 5_000))
                .withSession(new BotConfig.Session(120, 20, 10))
                .withFlee(new BotConfig.Flee(BotConfig.FleeMode.LIST, Set.of("theKing", "[RS]"), Set.of("[FR]"), 1_500, 45,
                        false, 120))
                .withGroup(new BotConfig.GroupRules(BotConfig.Accept.LIST, true, true, Set.of("Friend", "[TAG]"), true, false,
                        "Friend", Boolean.TRUE));
    }

    @Test
    void tabsKeepTheConfig() throws Exception {
        BotConfig c = sample();
        assertEquals(c, roundTrip(c));
    }

    @Test
    void palladiumKeepsTheDarkBotSettings() throws Exception {
        // the user's DmPlugin «Palladium Hangar» settings, as imported (invented hangar numbers)
        com.fasterxml.jackson.databind.node.ObjectNode m = Json.MAPPER.createObjectNode();
        m.put("goPortalChange", false).put("sellOnDie", true).put("ignoreSID", false)
                .put("collectHangar", 123_456).put("sellHangar", 123_457).put("aditionalWaitingTime", 5);
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.PALLADIUM).withModuleSettings("palladium", m);
        assertEquals(Json.MAPPER.valueToTree(c), Json.MAPPER.valueToTree(roundTrip(c)));
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

    @Test
    void theQuestsTabKeepsItsChoices() {
        com.fasterxml.jackson.databind.node.ObjectNode q = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
        q.putArray("types").add("DAILY").add("MISSION");
        q.putArray("do").add("KILL").add("MAP");
        q.put("revisitMinutes", 120);
        q.putArray("first").add(122004);
        q.putArray("skip").add(122001);
        BotConfig c = BotConfig.empty().withModule(BotConfig.Module.QUESTS).withModuleSettings("quests", q);
        BotConfig out = roundTrip(c);
        var m = out.modules().get("quests");
        org.junit.jupiter.api.Assertions.assertEquals("[\"DAILY\",\"MISSION\"]", m.path("types").toString());
        org.junit.jupiter.api.Assertions.assertEquals("[\"KILL\",\"MAP\"]", m.path("do").toString());
        org.junit.jupiter.api.Assertions.assertEquals(120, m.path("revisitMinutes").asInt());
        org.junit.jupiter.api.Assertions.assertEquals("[122004]", m.path("first").toString(), "marks kept");
        org.junit.jupiter.api.Assertions.assertEquals("[122001]", m.path("skip").toString());
        // a config of another module gets no quests section
        org.junit.jupiter.api.Assertions.assertFalse(roundTrip(BotConfig.empty()).modules().containsKey("quests"));
    }
}
