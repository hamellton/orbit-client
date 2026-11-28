package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HandPlayTest {

    @Test
    void theMacAppStartsItsProgramInsideAndTheWindowsProgramItself(@TempDir Path dir) throws Exception {
        Path app = dir.resolve("DarkOrbit Client.app");
        Path inside = Files.createDirectories(app.resolve("Contents").resolve("MacOS")).resolve("DarkOrbit Client");
        Files.writeString(inside, "");
        assertEquals(inside, HandPlay.program(app), "the macOS app: the program inside it");

        Path exe = Files.writeString(dir.resolve("DarkOrbit Client.exe"), "");
        assertEquals(exe, HandPlay.program(exe), "the Windows install: the program itself");

        assertNull(HandPlay.program(dir.resolve("missing.exe")), "no client there");
        assertNull(HandPlay.program(Files.createDirectories(dir.resolve("Empty.app"))), "an app without its program");
    }
}
