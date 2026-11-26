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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ManagerWindowTest {

    @BeforeEach
    void ownTicksFile(@TempDir Path dir) {
        System.setProperty("orbit.manager.file", dir.resolve("manager.properties").toString()); // never local/
    }

    @Test
    void onlyTickedAccountsAreStartedAndTheTicksAreKept(@TempDir Path dir) throws Exception {
        Map<String, Fake> fakes = new HashMap<>();
        Vault v = vault(dir);
        ManagerWindow w = new ManagerWindow(v, n -> fakes.computeIfAbsent(n, Fake::new));
        w.refresh();
        assertEquals(Boolean.TRUE, w.model.getValueAt(1, 0), "a new account is ticked");
        w.model.setValueAt(false, 1, 0);
        w.startAll();
        assertEquals(List.of(0L), fakes.get("alpha").starts);
        assertTrue(fakes.get("beta") == null || fakes.get("beta").starts.isEmpty(), "the unticked one is not touched");

        ManagerWindow again = new ManagerWindow(v, n -> new Fake(n));
        again.refresh();
        assertEquals(Boolean.TRUE, again.model.getValueAt(0, 0));
        assertEquals(Boolean.FALSE, again.model.getValueAt(1, 0), "the ticks are kept");
        again.model.pickAll(true);
        assertTrue(again.model.allPicked());
    }

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
        boolean inGame;
        int killed;

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
        public void stopNow() {
            if (phase != Phase.STOPPED) {
                killed++;
                phase = Phase.STOPPING;
            }
        }

        @Override
        public boolean inGame() {
            return inGame && phase == Phase.RUNNING;
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
        assertEquals("pilot-a", w.model.getValueAt(0, ManagerWindow.C_LOGIN));
        assertEquals("int1", w.model.getValueAt(0, ManagerWindow.C_SERVER));
        assertEquals("SOCKS5 10.0.0.9:1080 (логин u)", w.model.getValueAt(0, ManagerWindow.C_PROXY));
        assertEquals("напрямую — IP этого компьютера", w.model.getValueAt(1, ManagerWindow.C_PROXY));
        assertEquals("ru1", w.model.getValueAt(1, ManagerWindow.C_SERVER));
        assertEquals("", w.model.getValueAt(0, ManagerWindow.C_GROUP), "no group yet");
        for (int r = 0; r < 2; r++) {
            for (int c = 0; c < w.model.getColumnCount(); c++) {
                String cell = String.valueOf(w.model.getValueAt(r, c));
                assertFalse(cell.contains("Pw9#x") || cell.contains("Qx7!fake") || cell.contains("Zz3?fake"), cell);
            }
        }
        assertEquals("Остановлен", w.model.getValueAt(0, ManagerWindow.C_STATE).toString());
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
    void closingAStoppedAccountsTab(@TempDir Path dir) throws Exception {
        Map<String, Fake> fakes = new HashMap<>();
        ManagerWindow w = new ManagerWindow(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new));
        w.refresh();
        assertNull(w.startOne("alpha", 0, false));
        assertNull(w.startOne("beta", 0, false));
        assertEquals(4, w.pane.getTabCount());
        assertEquals(Boolean.TRUE, ((javax.swing.JComponent) w.pane.getComponentAt(2)).getClientProperty("JTabbedPane.tabClosable"));
        assertNull(((javax.swing.JComponent) w.pane.getComponentAt(1)).getClientProperty("JTabbedPane.tabClosable"),
                "«Все» has no ×");

        fakes.get("alpha").phase = ManagedAccount.Phase.STOPPED;
        w.closeTab(2);
        assertEquals(3, w.pane.getTabCount());
        assertEquals(List.of("alpha", "beta"), w.model.names, "the account stays in the list");
        w.closeTab(0);
        w.closeTab(1);
        assertEquals(3, w.pane.getTabCount(), "«Аккаунты» and «Все» are not closed");

        fakes.get("beta").phase = ManagedAccount.Phase.STOPPED;
        w.closeTab(2);
        assertEquals(1, w.pane.getTabCount(), "no account tab left: «Все» goes too");

        assertNull(w.startOne("alpha", 0, false));
        assertEquals(3, w.pane.getTabCount(), "a new start brings the tab back");
    }

    @Test
    void accountsNotInGameStopAtOnceWithoutAQuestion(@TempDir Path dir) throws Exception {
        Map<String, Fake> fakes = new HashMap<>();
        ManagerWindow w = new ManagerWindow(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new));
        w.refresh();
        assertNull(w.startOne("alpha", 0, false));
        assertNull(w.startOne("beta", 0, false));
        // both still logging in (or waiting out a maintenance): no portal to fly to, no dialog (none headless)
        assertTrue(StopDialog.stop(null, "test", List.of(fakes.get("alpha"), fakes.get("beta")), ""));
        assertEquals(1, fakes.get("alpha").killed);
        assertEquals(1, fakes.get("beta").killed);

        fakes.get("alpha").phase = ManagedAccount.Phase.STOPPED;
        fakes.get("beta").phase = ManagedAccount.Phase.STOPPED;
        assertNull(w.startOne("alpha", 0, false));
        w.closeTab(2); // the × of an account not in game: stopped at once, the tab goes
        assertEquals(2, fakes.get("alpha").killed);
        assertEquals(3, w.pane.getTabCount(), "«Аккаунты», «Все» and beta's tab are left");
    }

    @Test
    void aGroupedAccountIsSeenOnItsGroupsTabAndOpenedFromItsCard(@TempDir Path dir) throws Exception {
        String before = System.getProperty("orbit.manager.groups");
        System.setProperty("orbit.manager.groups", dir.resolve("groups.json").toString());
        try {
            Map<String, Fake> fakes = new HashMap<>();
            ManagerWindow w = new ManagerWindow(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new));
            w.groups.setGroup("alpha", "Стартеры");
            w.refresh();
            assertEquals("Стартеры", w.model.getValueAt(0, ManagerWindow.C_GROUP));
            assertNull(w.startOne("alpha", 0, false));
            assertNull(w.startOne("beta", 0, false));
            List<String> titles = new ArrayList<>();
            for (int i = 0; i < w.pane.getTabCount(); i++) {
                titles.add(w.pane.getTitleAt(i));
            }
            // «Аккаунты», «Все», the group's tab, beta's own tab (no group); alpha has none of its own
            assertEquals(4, w.pane.getTabCount(), titles.toString());
            assertEquals("Стартеры · 1", w.pane.getTitleAt(2));
            w.openAccount("alpha");
            assertEquals(5, w.pane.getTabCount(), "opened from its card");
            int alphaTab = w.pane.getSelectedIndex();
            w.closeTab(alphaTab);
            assertEquals(4, w.pane.getTabCount(), "the × of a grouped account hides its tab");
            assertEquals(0, fakes.get("alpha").killed, "and does not stop it");
            assertTrue(w.shows(0) && w.shows(1));
            // a fresh window reads the groups back
            ManagerWindow again = new ManagerWindow(vault(dir), n -> fakes.computeIfAbsent(n, Fake::new));
            assertEquals("Стартеры", again.groups.group("alpha"));
        } finally {
            if (before == null) {
                System.clearProperty("orbit.manager.groups");
            } else {
                System.setProperty("orbit.manager.groups", before);
            }
        }
    }

    @Test
    void aStartSetIsKeptWithItsAccounts(@TempDir Path dir) {
        ManagerGroups g = new ManagerGroups(dir.resolve("groups.json"));
        g.putPreset(new ManagerGroups.Preset("Ночь", List.of("alpha", "beta"), "Leveling MMO Quests"));
        g.setPinned("beta", true);
        ManagerGroups back = new ManagerGroups(dir.resolve("groups.json"));
        assertEquals(List.of(new ManagerGroups.Preset("Ночь", List.of("alpha", "beta"), "Leveling MMO Quests")), back.presets);
        assertTrue(back.pinned.contains("beta"));
        back.forget("alpha");
        assertEquals(List.of("beta"), new ManagerGroups(dir.resolve("groups.json")).presets.getFirst().accounts(),
                "a removed account leaves the sets");
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
