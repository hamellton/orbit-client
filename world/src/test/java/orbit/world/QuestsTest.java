package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import orbit.net.Recording;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.LootModule;
import orbit.protocol.gen.QuestAcceptabilityStatusModule;
import orbit.protocol.gen.QuestCaseModule;
import orbit.protocol.gen.QuestCompletedCommand;
import orbit.protocol.gen.QuestConditionModule;
import orbit.protocol.gen.QuestConditionStateModule;
import orbit.protocol.gen.QuestConditionUpdateCommand;
import orbit.protocol.gen.QuestDefinitionModule;
import orbit.protocol.gen.QuestElementModule;
import orbit.protocol.gen.QuestGiverModule;
import orbit.protocol.gen.QuestGiversAvailableCommand;
import orbit.protocol.gen.QuestInfoCommand;
import orbit.protocol.gen.QuestInitializationCommand;
import orbit.protocol.gen.QuestListCommand;
import orbit.protocol.gen.QuestSlimInfoModule;
import orbit.protocol.gen.QuestTypeModule;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class QuestsTest {

    static QuestDefinitionModule protegits(int id, double done) {
        QuestConditionModule c = new QuestConditionModule();
        c.id = id * 100 + 1;
        c.matches = List.of("12");
        c.type = 6;
        c.displayType = 6;
        c.targetValue = 35_000;
        c.mandatory = true;
        c.state = new QuestConditionStateModule();
        c.state.currentValue = done;
        c.state.active = true;
        QuestElementModule e = new QuestElementModule();
        e.questCase = new QuestCaseModule();
        e.condition = c;
        QuestDefinitionModule d = new QuestDefinitionModule();
        d.id = id;
        QuestTypeModule t = new QuestTypeModule();
        t.type = 2;
        d.types = List.of(t);
        d.rootCase = new QuestCaseModule();
        d.rootCase.id = id * 100;
        d.rootCase.modifier = List.of(e);
        LootModule xp = new LootModule();
        xp.lootId = "currency_experience";
        xp.amount = 425_000_000;
        d.rewards = List.of(xp);
        d.icons = List.of();
        return d;
    }

    @Test
    void aTakenQuestItsProgressAndItsEnd() {
        World w = new World();
        QuestInitializationCommand init = new QuestInitializationCommand();
        init.quest = protegits(122001, 4122);
        w.apply(init, 1);
        Quests q = w.snapshot(1).quests();
        Quests.Quest quest = q.active().get(122001);
        assertEquals(List.of(Quests.MISSION), quest.types());
        Quests.Condition c = quest.leaves().getFirst();
        assertEquals(Quests.KILL_NPC, c.type());
        assertEquals(12, c.match(0));
        assertEquals("убить Protegit 4122/35000", QuestBook.describe(c));
        assertEquals("425 000 000 опыта", QuestBook.rewards(quest));

        QuestConditionUpdateCommand u = new QuestConditionUpdateCommand();
        u.questConditionId = 12200101;
        u.state = new QuestConditionStateModule();
        u.state.currentValue = 4123;
        u.state.active = true;
        w.apply(u, 2);
        assertEquals(4123, w.snapshot(2).quests().active().get(122001).leaves().getFirst().current());

        QuestCompletedCommand done = new QuestCompletedCommand();
        done.id = 122001;
        w.apply(done, 3);
        assertTrue(w.snapshot(3).quests().active().isEmpty());
    }

    @Test
    void theGiversListAndAnOfferedQuestsDetails() {
        World w = new World();
        QuestGiversAvailableCommand g = new QuestGiversAvailableCommand();
        QuestGiverModule giver = new QuestGiverModule();
        giver.questGiverId = 3;
        giver.mapObjectId = 150_000_123;
        g.questGivers = List.of(giver);
        w.apply(g, 1);
        assertEquals(java.util.Set.of(150_000_123), w.snapshot(1).quests().givers(), "the asset id is what the client sends");

        QuestListCommand l = new QuestListCommand();
        QuestSlimInfoModule s = new QuestSlimInfoModule();
        s.questId = 122005;
        s.minLevel = 22;
        QuestTypeModule t = new QuestTypeModule();
        t.type = 3;
        s.types = List.of(t);
        s.acceptabilityStatus = new QuestAcceptabilityStatusModule();
        s.acceptabilityStatus.type = 1;
        l.list = List.of(s);
        l.maxQuests = 2;
        w.apply(l, 2);
        Quests q = w.snapshot(2).quests();
        assertEquals(new Quests.Offer(122005, List.of(Quests.DAILY), 22, Quests.ACCEPTABLE, 0), q.offers().getFirst());
        assertEquals(2, q.maxQuests());

        QuestInfoCommand info = new QuestInfoCommand();
        info.definition = protegits(122005, 0);
        w.apply(info, 3);
        assertEquals("убить Protegit 0/35000", QuestBook.describe(w.snapshot(3).quests().details().get(122005)));
        assertTrue(w.snapshot(3).quests().active().isEmpty(), "details are no taken quest");
    }

    /** ru1 2026-11-11: five missions taken (three on players, 35 000 Protegits…), all five on the giver's list. */
    @Test
    void theRecordedQuestsReadAsTheClientShowsThem() throws Exception {
        Path f = Path.of(System.getProperty("orbit.recordings", "recordings"), "session-20261111-190446-2.dorec");
        Assumptions.assumeTrue(Files.exists(f), "запись ru1 2026-11-11 не найдена");
        World w = new World();
        long last = 0;
        for (Recording.PacketRecord r : Recording.readPackets(f)) {
            if (!r.up()) {
                Packet p = PacketCodec.decode(r.bytes());
                w.apply(p, r.nanos());
                last = r.nanos();
            }
        }
        Quests q = w.snapshot(last).quests();
        java.util.Set<Integer> fromGiver = new java.util.TreeSet<>();
        q.active().values().stream().filter(x -> Quests.GIVER.equals(x.source())).forEach(x -> fromGiver.add(x.id()));
        assertEquals(java.util.Set.of(118015, 120024, 120032, 122001, 122005), fromGiver, "the giver's ones; the passes' too");
        assertTrue(QuestBook.describe(q.active().get(122001)).startsWith("убить Protegit "), QuestBook.describe(q.active().get(122001)));
        assertEquals("убить игроков", QuestTexts.condition(q.active().get(118015).leaves().getFirst()));
        assertEquals(5, q.offers().size());
    }

    @SuppressWarnings("unused")
    private static List<Path> all() throws Exception {
        try (Stream<Path> s = Files.list(Path.of("recordings"))) {
            return s.toList();
        }
    }

    /** ru1 2026-11-11 19:06: the season's missions, its rewards and the Battle Pass objectives come at the login. */
    @Test
    void thePassesOfTheRecordingAreRead() throws Exception {
        Path f = Path.of(System.getProperty("orbit.recordings", "recordings"), "session-20261111-190628-3.dorec");
        Assumptions.assumeTrue(Files.exists(f), "запись ru1 2026-11-11 19:06 не найдена");
        World w = new World();
        long last = 0;
        for (Recording.PacketRecord r : Recording.readPackets(f)) {
            if (!r.up()) {
                w.apply(PacketCodec.decode(r.bytes()), r.nanos());
                last = r.nanos();
            }
        }
        Quests q = w.snapshot(last).quests();
        assertTrue(q.active().values().stream().anyMatch(x -> Quests.SEASON.equals(x.source())), "season missions");
        assertTrue(q.active().values().stream().anyMatch(x -> Quests.BATTLEPASS_SOURCE.equals(x.source())), "battle pass");
        assertTrue(!q.passes().seasonRewards().isEmpty(), "season rewards");
        assertTrue(!q.passes().pointsCost().isEmpty());
        assertTrue(q.passes().seasonLevel() >= 1);
    }
}
