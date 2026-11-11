package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigStoreTest {

    static BotConfig sample() throws IOException {
        return DarkBotImport.convert(Json.MAPPER.readTree(DarkBotImportTest.PROFILE), "текст").config()
                .withModuleSettings("cubikon", Json.MAPPER.readTree("{\"waitSeconds\": 60}"));
    }

    @Test
    void savesAndLoadsTheSameConfig(@TempDir Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir);
        BotConfig c = sample();
        store.save("Кубы 3-6", c);
        assertEquals(c, store.load("Кубы 3-6"));
        assertEquals(List.of("Кубы 3-6"), store.list());
        // stable output: saving again gives the same bytes
        byte[] first = Files.readAllBytes(dir.resolve("Кубы 3-6.json"));
        store.save("Кубы 3-6", store.load("Кубы 3-6"));
        assertEquals(new String(first), Files.readString(dir.resolve("Кубы 3-6.json")));
    }

    @Test
    void copyRenameDelete(@TempDir Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir);
        store.save("a", BotConfig.empty());
        store.copy("a", "b");
        assertThrows(IOException.class, () -> store.copy("a", "b"));
        store.rename("b", "c");
        assertEquals(List.of("a", "c"), store.list());
        store.delete("a");
        assertEquals(List.of("c"), store.list());
    }

    @Test
    void refusesBadNames(@TempDir Path dir) {
        ConfigStore store = new ConfigStore(dir);
        for (String bad : new String[] {"", "../x", "a/b", ".hidden", "a\\b"}) {
            assertThrows(IllegalArgumentException.class, () -> store.save(bad, BotConfig.empty()), bad);
        }
    }

    @Test
    void missingSectionsReadAsDefaults(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("min.json"), "{\"module\": \"CUBIKON\", \"general\": {\"workingMap\": 26}}");
        BotConfig c = new ConfigStore(dir).load("min");
        assertEquals(BotConfig.Module.CUBIKON, c.module());
        assertEquals(26, c.general().workingMap());
        assertEquals(1, c.general().offensive().config());
        assertEquals(BotConfig.Safety.DEFAULT, c.safety());
        assertEquals(Map.of(), c.npcs());
        assertEquals(BotConfig.SCHEMA, c.schema());
    }

    @Test
    void refusesNewerSchema(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("new.json"), "{\"schema\": 99}");
        IOException e = assertThrows(IOException.class, () -> new ConfigStore(dir).load("new"));
        assertTrue(e.getMessage().contains("обновите"));
    }

    @Test
    void accountSettingsRoundTrip(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("accounts.json");
        assertTrue(AccountSettings.load(f).accounts().isEmpty());
        Schedule s = new Schedule(true, List.of(new Schedule.Entry(Set.of(java.time.DayOfWeek.MONDAY), "08:00", "12:00", "CUBIKON")), 10);
        AccountSettings a = new AccountSettings(null).with("main", new AccountSettings.Entry("ABG", s));
        a.save(f);
        AccountSettings back = AccountSettings.load(f);
        assertEquals(a, back);
        assertFalse(Files.readString(f).contains("password"));
        assertEquals(new AccountSettings.Entry(null, null), back.account("other"));
    }

    @Test
    void theAccountKeepsItsShipsAndThePalladiumPair(@TempDir Path dir) throws IOException {
        Path f = dir.resolve("accounts.json");
        AccountSettings.Entry e = new AccountSettings.Entry("PALLADIUM", null)
                .withHangars(List.of(new AccountSettings.Ship(11, "ship_goliath", null),
                        new AccountSettings.Ship(12, "ship_g-champion", "design_venom")))
                .withPalladium(new AccountSettings.PalladiumShips(11, 12));
        new AccountSettings(null).with("main", e).save(f);
        AccountSettings.Entry back = AccountSettings.load(f).account("main");
        assertEquals(e, back);
        assertEquals("Goliath", back.ships().get(0).label());
        assertEquals("G-Champion (Venom)", back.ships().get(1).label());
        assertEquals(e, back.withConfig("PALLADIUM").withSchedule(back.schedule()), "the config switch keeps the ships");
        assertFalse(new AccountSettings.PalladiumShips(11, 11).usable());
        // as the site lists them (ru1, 2026-11-11): the design is in the ship's id
        assertEquals("Goliath (Razer)", new AccountSettings.Ship(1, "goliath_design_goliath-razer", null).label());
        assertEquals("Goliath-X (Lava)", new AccountSettings.Ship(2, "goliath-x_design_goliath-x-lava", null).label());
        assertEquals("C-Elite", new AccountSettings.Ship(3, "c-elite", null).label());
        assertEquals("Bb-Solemn", new AccountSettings.Ship(4, "bb-solemn", null).label());
    }
}
