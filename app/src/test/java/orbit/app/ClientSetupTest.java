package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ClientSetupTest {

    /** The release's macOS feed as it is (2.8.0): two files, each with its checksum. */
    static final String MAC_FEED = """
            version: 2.8.0
            files:
              - url: DarkOrbit-Client-2.8.0-mac.zip
                sha512: zipSum==
                size: 121328567
              - url: DarkOrbit-Client-2.8.0.dmg
                sha512: dmgSum==
                size: 125899001
            path: DarkOrbit-Client-2.8.0-mac.zip
            sha512: zipSum==
            releaseDate: '2023-06-08T13:43:56.263Z'
            """;

    @Test
    void eachFileOfTheReleaseHasItsOwnChecksum() {
        assertEquals("zipSum==", ClientSetup.checksum(MAC_FEED, ClientSetup.Os.MAC.asset));
        assertEquals("dmgSum==", ClientSetup.checksum(MAC_FEED, "DarkOrbit-Client-2.8.0.dmg"));
        assertNull(ClientSetup.checksum(MAC_FEED, ClientSetup.Os.WINDOWS.asset), "a file the feed does not list");
    }

    @Test
    void theChecksumIsTheReleasesBase64Sha512(@TempDir Path dir) throws Exception {
        Path f = Files.writeString(dir.resolve("x"), "abc");
        assertEquals("3a81oZNherrMQXNJriBBMRLm+k6JqX6iCp7u5ktV05ohkpkqJ0/BqDa6PCOj/uu9RU1EI2Q86A4qmslPpUyknw==",
                ClientSetup.sha512(f));
    }

    @Test
    void aClientPointedToIsKeptOnlyWhenItIsThere(@TempDir Path dir) throws Exception {
        assertNull(HandPlay.program(dir.resolve("none.exe")));
        Path exe = Files.writeString(dir.resolve("DarkOrbit Client.exe"), "");
        assertEquals(exe, HandPlay.program(exe));
    }

    @Test
    void theClientRunsWhereX86RunsOrIsEmulated() {
        assertEquals(true, ClientSetup.supported(ClientSetup.Os.LINUX, "amd64"));
        assertEquals(false, ClientSetup.supported(ClientSetup.Os.LINUX, "aarch64"), "the Oracle server");
        assertEquals(true, ClientSetup.supported(ClientSetup.Os.MAC, "aarch64"), "Apple silicon runs it by Rosetta");
        assertEquals(true, ClientSetup.supported(ClientSetup.Os.WINDOWS, "amd64"));
    }
}
