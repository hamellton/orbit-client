package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import orbit.app.WorldFeed;
import orbit.secrets.Account;
import orbit.secrets.Vault;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagerWindowTest {

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    /** A runner that only records what the window asked for. */
    static final class Fake implements ManagedAccount {
        final String name;
        final WorldFeed feed;
        final List<Long> starts = new ArrayList<>();
        String refuse;
        Phase phase = Phase.STOPPED;

        Fake(String name) {
            this.name = name;
            this.feed = new WorldFeed("бот, " + name, name);
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public WorldFeed feed() {
            return feed;
        }

        @Override
        public Phase phase() {
            return phase;
        }

        @Override
        public String problem() {
            return refuse;
        }

        @Override
        public String start(long delayMs) {
            if (refuse != null) {
                return refuse;
            }
            starts.add(delayMs);
            phase = Phase.RUNNING;
            return null;
        }

        @Override
        public void stop() {
            if (phase == Phase.RUNNING) {
                phase = Phase.STOPPING;
            }
        }

        @Override
        public String config() {
            return "CUBES";
        }

        @Override
        public void switchConfig(String n) {
        }
    }

    private static Vault vault(Path dir) throws Exception {
        Vault v = Vault.open(dir.resolve("vault.bin"), "Te5t-mäster".toCharArray());
        v.put("alpha", new Account("pilot-a", "Qx7!fake", "int1", null, "socks5://u:Pw9#x@10.0.0.9:1080"));
        v.put("beta", new Account("pilot-b", "Zz3?fake", "ru1", null, null));
        return v;
    }

    @Test
    void listsAccountsWithoutSecrets(@TempDir Path dir) throws Exception {
        Map<String, Fake> fakes = new HashMap<>();
        ManagerWindow w = new ManagerWindow(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new));
        w.refresh();
        assertEquals(List.of("alpha", "beta"), w.model.names);
        assertEquals("pilot-a", w.model.getValueAt(0, 1));
        assertEquals("int1", w.model.getValueAt(0, 2));
        assertEquals("SOCKS5 10.0.0.9:1080 (логин u)", w.model.getValueAt(0, 3));
        assertEquals("напрямую", w.model.getValueAt(1, 3));
        assertEquals("ru1", w.model.getValueAt(1, 2));
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < w.model.getColumnCount(); c++) {
                String cell = String.valueOf(w.model.getValueAt(r, c));
                assertFalse(cell.contains("Pw9#x") || cell.contains("Qx7!fake") || cell.contains("Zz3?fake"), cell);
            }
        }
        assertEquals("Остановлен", w.model.getValueAt(0, 6).toString());
    }

    @Test
    void startMakesATabAndStopAsksForLogout(@TempDir Path dir) throws Exception {
        Map<String, Fake> fakes = new HashMap<>();
        ManagerWindow w = new ManagerWindow(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new));
        w.refresh();
        assertEquals(1, w.pane.getTabCount(), "only «Аккаунты» before a start");

        assertNull(w.startOne("beta", 0, false));
        assertEquals(List.of(0L), fakes.get("beta").starts);
        assertEquals(3, w.pane.getTabCount(), "«Все» and the account's tab");
        assertEquals("Все", w.pane.getTitleAt(1));
        assertNotNull(w.startOne("beta", 0, false), "a running account is not started twice");

        fakes.get("alpha").refuse = "не выбран конфиг";
        assertEquals("не выбран конфиг", w.startOne("alpha", 0, false));
        assertEquals(3, w.pane.getTabCount(), "a refused start makes no tab");

        fakes.get("beta").stop();
        fakes.get("beta").phase = ManagedAccount.Phase.STOPPED;
        assertNull(w.startOne("beta", 5_000, false));
        assertEquals(List.of(0L, 5_000L), fakes.get("beta").starts);
        assertEquals(3, w.pane.getTabCount(), "a restart keeps the same tab");
    }

    @Test
    void snapshotDraws(@TempDir Path dir) throws Exception {
        Map<String, Fake> fakes = new HashMap<>();
        BufferedImage img = ManagerWindow.snapshot(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new), List.of("alpha"),
                0, 1200, 500);
        int first = img.getRGB(0, 0);
        boolean varied = false;
        for (int y = 0; y < img.getHeight() && !varied; y += 7) {
            for (int x = 0; x < img.getWidth(); x += 7) {
                if (img.getRGB(x, y) != first) {
                    varied = true;
                    break;
                }
            }
        }
        assertTrue(varied);
    }
}
