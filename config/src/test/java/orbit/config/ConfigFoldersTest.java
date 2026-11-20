package orbit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigFoldersTest {

    static final List<String> DEFAULTS = List.of("Behemoth", "Behemoth Avoid Trains v1", "Behemoth Avoid Trains v2", "Behemoth Train", "Blacklight Impulse", "Blacklight Invoke 1-BL",
            "Blacklight Invoke 2-BL", "Blacklight Invoke 3-BL",
            "Blacklight Invoke Train 1-BL", "Blacklight Invoke Train 2-BL", "Blacklight Invoke Train 3-BL", "Cubikon", "EBG", "Galaxy Gates ABG", "Mimesis EIC 2-4", "Mimesis MMO 1-4",
            "Mimesis VRU 3-4", "Palladium");

    @Test
    void theProgramsDefaultsAreListedFirstAndRead(@TempDir Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir);
        assertEquals(DEFAULTS, store.folders().get(ConfigStore.DEFAULT_FOLDER));
        assertEquals(ConfigStore.DEFAULT_FOLDER, store.folders().keySet().iterator().next());
        for (String n : DEFAULTS) {
            assertTrue(store.isDefault(n), n);
            BotConfig c = store.load(n);
            assertTrue(c.description().startsWith("[Default]"), n);
            assertFalse(c.modules().containsKey("enrich"), n + ": neutral");
        }
        assertEquals(BotConfig.Module.GALAXY_GATE, store.load("Galaxy Gates ABG").module());
        assertEquals(BotConfig.Module.PALLADIUM, store.load("Palladium").module());
        assertEquals(26, store.load("Cubikon").general().workingMap(), "Venus, 3-6");
    }

    @Test
    void aDefaultIsNeverWrittenButCopied(@TempDir Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir);
        BotConfig c = store.load("Cubikon");
        assertThrows(IOException.class, () -> store.save("Cubikon", c));
        assertThrows(IOException.class, () -> store.delete("Cubikon"));
        assertThrows(IOException.class, () -> store.rename("Cubikon", "x"));
        assertThrows(IOException.class, () -> store.move("Cubikon", "Mine"));
        store.copy("Cubikon", "My cubes", "Cubikon");
        assertEquals("Cubikon", store.folderOf("My cubes"));
        assertTrue(Files.exists(dir.resolve("Cubikon/My cubes.json")));
        assertEquals(c.module(), store.load("My cubes").module());
        assertThrows(IllegalArgumentException.class, () -> store.createFolder("Default"));
    }

    @Test
    void foldersHoldConfigsByUniqueNames(@TempDir Path dir) throws IOException {
        ConfigStore store = new ConfigStore(dir, false);
        store.save("a", BotConfig.empty());
        store.createFolder("Blacklight");
        store.move("a", "Blacklight");
        assertEquals("Blacklight", store.folderOf("a"));
        assertEquals(BotConfig.empty(), store.load("a"), "found in its folder by its name");
        store.save("a", BotConfig.empty().withModule(BotConfig.Module.CUBIKON));
        assertTrue(Files.exists(dir.resolve("Blacklight/a.json")), "saved where it is");
        assertFalse(Files.exists(dir.resolve("a.json")));
        assertTrue(store.modified("a") > 0);
        assertThrows(IOException.class, () -> store.deleteFolder("Blacklight"), "not empty");
        store.renameFolder("Blacklight", "BL");
        assertEquals("BL", store.folderOf("a"));
        store.move("a", "");
        store.deleteFolder("BL");
        assertEquals(List.of("a"), store.folders().get(""));
        assertThrows(IOException.class, () -> store.copy("a", "a"));
    }

    @Test
    void aUsersConfigNamedLikeADefaultInAnotherCaseIsItsOwn(@TempDir Path dir) throws IOException {
        // macOS: CUBIKON.json is found by «Cubikon.json» too
        ConfigStore store = new ConfigStore(dir);
        store.createFolder("Cubikon");
        // an old config of the user's, from before the defaults (a new one so named is refused)
        Json.MAPPER.writeValue(dir.resolve("Cubikon/CUBIKON.json").toFile(), BotConfig.empty());
        assertThrows(IOException.class, () -> store.save("Other", "cubikon", BotConfig.empty()), "case only");
        assertTrue(store.isDefault("Cubikon"), "the program's one");
        assertEquals(ConfigStore.DEFAULT_FOLDER, store.folderOf("Cubikon"));
        assertEquals("Cubikon", store.folderOf("CUBIKON"));
        assertEquals(BotConfig.Module.CUBIKON, store.load("Cubikon").module());
        assertEquals(BotConfig.empty(), store.load("CUBIKON"));
    }
}
