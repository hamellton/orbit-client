package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.BitSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Made-up DarkBot profiles in DarkBot's format (no user data). */
class DarkBotImportTest {

    static String zone(int res, int... cells) {
        BitSet b = new BitSet();
        for (int c : cells) {
            b.set(c);
        }
        return "{\"resolution\": " + res + ", \"data\": \"" + Base64.getEncoder().encodeToString(b.toByteArray()) + "\"}";
    }

    static final String PROFILE = """
            {
              "GENERAL": {
                "CURRENT_MODULE": "eu.darkbot.shared.modules.LootCollectorModule",
                "WORKING_MAP": 26,
                "OFFENSIVE": {"CONFIG": 1, "FORMATION": "6"},
                "ROAM": {"CONFIG": 2, "FORMATION": "7"},
                "RUN": {"CONFIG": 2, "FORMATION": "9"},
                "SAFETY": {"REPAIR_HP_RANGE": {"min": 0.4, "max": 0.8}, "REPAIR_HP_NO_NPC": 0.6, "REPAIR_TO_SHIELD": 0.3,
                           "REPAIR": {"CONFIG": 2, "FORMATION": "9"}, "MAX_DEATHS": -1, "REVIVE": "PORTAL",
                           "WAIT_BEFORE_REVIVE": 7, "WAIT_AFTER_REVIVE": 8},
                "RUNNING": {"RUN_FROM_ENEMIES": true, "REMEMBER_ENEMIES_FOR": 120},
                "ROAMING": {"SEQUENTIAL": true}
              },
              "LOOT": {
                "AMMO_KEY": "1",
                "SAB": {"ENABLED": true, "KEY": "2", "PERCENT": 0.8, "NPC_AMOUNT": 1000},
                "RSB": {"ENABLED": false, "KEY": "3"},
                "GROUP_NPCS": false,
                "NPC_INFOS": {
                  "-=[ Testnpc ]=-": {"radius": 560.0, "priority": -10, "kill": true,
                    "extra": {"flags": ["com.github.manolo8.darkbot.config.NpcExtraIGNORE_OWNERSHIP",
                                        "dev.shared.do_gamer.module.simple_galaxy_gate.config.GateNpcFlagKAMIKAZE", "ODD"]},
                    "mapList": [26, 18]},
                  "-=[ Calm ]=-": {"radius": 500.0, "priority": 50, "kill": false,
                    "extra": {"flags": ["com.github.manolo8.darkbot.config.NpcExtraPASSIVE"]}, "mapList": []}
                }
              },
              "COLLECT": {"RADIUS": 600, "STAY_AWAY_FROM_ENEMIES": false, "IGNORE_CONTESTED_BOXES": true,
                          "BOX_INFOS": {"BONUS_BOX": {"collect": true, "waitTime": 150, "priority": 1}}},
              "PET": {"ENABLED": true, "MODULE_ID": "GUARD"},
              "PREFERRED": {"26": %s},
              "AVOIDED": {"26": {"resolution": 30, "data": ""}},
              "SAFETY": {"26": [{"type": "PORTAL", "x": 1000, "y": 2000, "runMode": "ALWAYS", "jumpMode": "NEVER"}], "18": []},
              "PLUGIN_INFOS": {"SharedPlugin": {"ENABLED_FEATURES": ["dev.shared.do_gamer.behaviour.SimpleHealing", "x.y.Enabled"],
                                                "DISABLED_FEATURES": ["x.y.Disabled"]}},
              "CUSTOM_CONFIGS": {
                "dev.shared.do_gamer.behaviour.SimpleHealing": {"hp": {"enabled": true, "min": 0.65}, "shield": {"enabled": false, "min": 0.5}},
                "x.y.Enabled": {"a": 1},
                "x.y.Disabled": {"b": 2}
              },
              "BOT_SETTINGS": {"BOT_GUI": {"LOCALE": "ru"}},
              "GROUP": {"ACCEPT_INVITES": false}
            }
            """.formatted(zone(30, 0, 1, 31));

    @Test
    void convertsTheSections() throws IOException {
        DarkBotImport.Result r = DarkBotImport.convert(Json.MAPPER.readTree(PROFILE), "заметки");
        BotConfig c = r.config();
        assertEquals(BotConfig.Module.KILL_COLLECT, c.module());
        assertEquals("заметки", c.description());
        assertEquals(26, c.general().workingMap());
        assertEquals(new BotConfig.ShipMode(2, null), c.general().roam(), "slot keys are not carried over");
        assertEquals(120, c.general().rememberEnemiesSec());
        assertTrue(c.general().roamSequential());

        BotConfig.NpcRule npc = c.npc("-=[ Testnpc ]=-");
        assertEquals(560, npc.radius());
        assertEquals(-10, npc.priority());
        assertTrue(npc.kill());
        assertEquals(Set.of("IGNORE_OWNERSHIP", "KAMIKAZE", "ODD"), npc.flags());
        assertTrue(npc.has(BotConfig.NpcFlag.KAMIKAZE));
        assertEquals(Set.of(18, 26), npc.maps());
        assertTrue(c.npc("-=[ Calm ]=-").appliesOn(5));
        assertTrue(r.report().stream().anyMatch(l -> l.contains("ODD")));

        assertNull(c.loot().ammo(), "ammunition is chosen by name, not by key");
        assertEquals("ammunition_laser_sab-50", c.loot().sab());
        assertEquals(0.8, c.loot().sabBelow());
        assertTrue(r.report().stream().anyMatch(l -> l.contains("клавишами")));
        assertNull(c.loot().rsb());
        assertFalse(c.loot().groupNpcs());

        assertEquals(600, c.collect().radius());
        assertEquals(new BotConfig.BoxRule(true, 150, 1), c.collect().boxes().get("BONUS_BOX"));
        assertEquals(new BotConfig.Pet(true, "GUARD"), c.pet());

        BotConfig.Safety s = c.safety();
        assertEquals(0.4, s.repairHpMin());
        assertEquals(0.8, s.repairHpMax());
        assertEquals(-1, s.maxDeaths());
        assertEquals(0.65, s.heal().hpBelow());
        assertEquals(-1, s.heal().shieldBelow());

        BotConfig.Zones z = c.zones().get(26);
        assertNull(z.avoided(), "an empty grid is dropped");
        assertEquals("##", z.preferred().rows().get(0).substring(0, 2));
        assertEquals(".#.", z.preferred().rows().get(1).substring(0, 3));
        assertTrue(z.preferred().contains(100, 100, 21000, 13100));
        assertFalse(z.preferred().contains(20000, 100, 21000, 13100));

        assertEquals(List.of(new BotConfig.SafetySpot("PORTAL", 1000, 2000, "ALWAYS", "NEVER")), c.safetySpots().get(26));
        assertFalse(c.safetySpots().containsKey(18));

        assertTrue(c.extra().containsKey("darkbot.feature.x.y.Enabled"));
        assertFalse(c.extra().containsKey("darkbot.feature.x.y.Disabled"));
        assertTrue(c.extra().containsKey("darkbot.GROUP"));
        assertFalse(c.extra().keySet().stream().anyMatch(k -> k.contains("BOT_SETTINGS")));
    }

    @Test
    void cubikonProfileGetsTheSpawnCamp() throws IOException {
        JsonNode root = Json.MAPPER.readTree("""
                {"GENERAL": {"CURRENT_MODULE": "eu.darkbot.teyrox.CubiModule", "WORKING_MAP": 26},
                 "CUSTOM_CONFIGS": {"eu.darkbot.teyrox.CubiModule": {"KRISTALLINS": true}}}""");
        BotConfig c = DarkBotImport.convert(root, "").config();
        assertEquals(BotConfig.Module.CUBIKON, c.module());
        JsonNode cubi = c.modules().get("cubikon");
        assertEquals(4, cubi.get("spots").size());
        assertEquals(7500, cubi.get("spots").get(0).get(0).asInt());
        assertEquals(3900, cubi.get("spots").get(0).get(1).asInt());
        assertEquals(450, cubi.get("arriveDistance").asInt());
        assertEquals(90, cubi.get("waitSeconds").asInt());
        assertTrue(cubi.get("killWhileWaiting").asBoolean());
    }

    @Test
    void gateSettingsAreKept() throws IOException {
        JsonNode root = Json.MAPPER.readTree("""
                {"GENERAL": {"CURRENT_MODULE": "dev.shared.do_gamer.module.simple_galaxy_gate.SimpleGalaxyGate", "WORKING_MAP": 9},
                 "CUSTOM_CONFIGS": {"dev.shared.do_gamer.module.simple_galaxy_gate.SimpleGalaxyGate":
                     {"gateId": 71, "kamikaze": {"enabled": true, "minNpcs": 5}, "builder": {"enabled": false}}}}""");
        BotConfig c = DarkBotImport.convert(root, "").config();
        assertEquals(BotConfig.Module.GALAXY_GATE, c.module());
        assertEquals(71, c.modules().get("gate").get("gateId").asInt());
        assertEquals(5, c.modules().get("gate").get("kamikaze").get("minNpcs").asInt());
    }

    @Test
    void unknownModuleIsMarked() throws IOException {
        BotConfig c = DarkBotImport.convert(Json.MAPPER.readTree(
                "{\"GENERAL\": {\"CURRENT_MODULE\": \"some.Other\"}}"), "").config();
        assertEquals(BotConfig.Module.UNSUPPORTED, c.module());
        assertEquals("some.Other", c.extra().get("darkbot.module").asText());
    }

    @Test
    void picksProfilesAndNeverCredentials(@TempDir Path dir) throws IOException {
        Path configs = Files.createDirectories(dir.resolve("configs"));
        for (String f : List.of("ABG.json", "ABG_old.json", "X_before_builder_2026.json", "Y_autosave_1.json", "notes.txt")) {
            Files.writeString(configs.resolve(f), "{}");
        }
        Files.writeString(dir.resolve("credentials.json"), "{}");
        Files.writeString(configs.resolve("CUBIKON_README.txt"), "  описание  ");
        Files.writeString(configs.resolve("CUBIKON.json"), "{}");
        assertEquals(List.of("ABG", "CUBIKON"), DarkBotImport.profiles(dir).stream().map(DarkBotImport::nameOf).toList());
        assertEquals("описание", DarkBotImport.read(configs.resolve("CUBIKON.json")).config().description());
        assertThrows(IOException.class, () -> DarkBotImport.read(dir.resolve("credentials.json")));
    }

    @Test
    void flagNames() {
        assertEquals("PASSIVE", DarkBotImport.flagName("com.github.manolo8.darkbot.config.NpcExtraPASSIVE"));
        assertEquals("KAMIKAZE", DarkBotImport.flagName("a.b.GateNpcFlagKAMIKAZE"));
        assertEquals("K", DarkBotImport.flagName("K"));
    }
}
