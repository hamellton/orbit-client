package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.protocol.Packet;
import orbit.protocol.gen.QuestAcceptRequest;
import orbit.protocol.gen.QuestGiverApproachedRequest;
import orbit.protocol.gen.QuestGiverWindowClosedRequest;
import orbit.protocol.gen.QuestInfoRequest;
import orbit.protocol.gen.QuestSettingsRequest;
import orbit.world.Quests;
import orbit.world.maps.MapCatalog;
import org.junit.jupiter.api.Test;

class QuestGiverVisitTest {

    static final int GIVER = 150_000_097;

    /** A fake quest giver as the server of the user's recording (ru1 2026-11-12): on within 400, list, info, accept. */
    static final class Giver {
        final Sim sim;
        final int gx;
        final int gy;
        boolean on;
        final List<Integer> active = new ArrayList<>();
        int seen;
        int maxQuests = 1;

        Giver(Sim sim, int gx, int gy) {
            this.sim = sim;
            this.gx = gx;
            this.gy = gy;
            orbit.protocol.gen.AssetCreateCommand a = new orbit.protocol.gen.AssetCreateCommand();
            a.assetId = GIVER;
            a.type = new orbit.protocol.gen.AssetTypeModule();
            a.type.typeValue = 34;
            a.posX = gx;
            a.posY = gy;
            a.userName = "Reginald Crowley";
            a.showBubble = true;
            sim.world.apply(a, sim.now);
        }

        void tick() {
            var h = sim.world.snapshot(sim.now).hero();
            boolean near = Math.hypot(h.x() - gx, h.y() - gy) < 400;
            if (near != on) {
                on = near;
                orbit.protocol.gen.QuestGiversAvailableCommand g = new orbit.protocol.gen.QuestGiversAvailableCommand();
                if (near) {
                    orbit.protocol.gen.QuestGiverModule m = new orbit.protocol.gen.QuestGiverModule();
                    m.questGiverId = 2;
                    m.mapObjectId = GIVER;
                    g.questGivers = List.of(m);
                } else {
                    g.questGivers = List.of();
                }
                sim.world.apply(g, sim.now);
                if (near) {
                    list();
                }
            }
            for (; seen < sim.sent.size(); seen++) {
                Packet p = sim.sent.get(seen);
                if (p instanceof QuestInfoRequest r) {
                    orbit.protocol.gen.QuestInfoCommand c = new orbit.protocol.gen.QuestInfoCommand();
                    c.definition = definition(r.questId);
                    sim.world.apply(c, sim.now);
                } else if (p instanceof QuestAcceptRequest r) {
                    active.add(r.questId);
                    if (r.questId / 100_000 == 1) {
                        maxQuests--;
                    }
                    orbit.protocol.gen.QuestInitializationCommand c = new orbit.protocol.gen.QuestInitializationCommand();
                    c.quest = definition(r.questId);
                    sim.world.apply(c, sim.now);
                    list();
                }
            }
        }

        void list() {
            orbit.protocol.gen.QuestListCommand l = new orbit.protocol.gen.QuestListCommand();
            l.list = List.of(offer(122004, 2), offer(122003, 2), offer(317029, 3), offer(606009, 6), offer(118015, 2));
            l.maxQuests = maxQuests;
            l.maxEventQuests = 1;
            sim.world.apply(l, sim.now);
        }

        orbit.protocol.gen.QuestSlimInfoModule offer(int id, int type) {
            orbit.protocol.gen.QuestSlimInfoModule s = new orbit.protocol.gen.QuestSlimInfoModule();
            s.questId = id;
            s.minLevel = 10;
            orbit.protocol.gen.QuestTypeModule t = new orbit.protocol.gen.QuestTypeModule();
            t.type = (short) type;
            s.types = List.of(t);
            s.acceptabilityStatus = new orbit.protocol.gen.QuestAcceptabilityStatusModule();
            s.acceptabilityStatus.type = (short) (active.contains(id) ? 2 : id == 118015 ? 2 : 1);
            return s;
        }

        /** 122xxx kill NPCs, 317029 kills, 606009 a galaxy gate (the bot cannot). */
        static orbit.protocol.gen.QuestDefinitionModule definition(int id) {
            orbit.protocol.gen.QuestConditionModule c = new orbit.protocol.gen.QuestConditionModule();
            c.id = id * 100 + 1;
            boolean gate = id == 606009;
            c.displayType = (short) (gate ? 79 : 6);
            c.type = c.displayType;
            c.matches = gate ? List.of("0", "51") : List.of("12");
            c.targetValue = 100;
            c.mandatory = true;
            c.state = new orbit.protocol.gen.QuestConditionStateModule();
            orbit.protocol.gen.QuestElementModule e = new orbit.protocol.gen.QuestElementModule();
            e.questCase = new orbit.protocol.gen.QuestCaseModule();
            e.condition = c;
            orbit.protocol.gen.QuestDefinitionModule d = new orbit.protocol.gen.QuestDefinitionModule();
            d.id = id;
            orbit.protocol.gen.QuestTypeModule t = new orbit.protocol.gen.QuestTypeModule();
            t.type = (short) (id / 100_000 == 1 ? 2 : id / 100_000 == 3 ? 3 : 6);
            d.types = List.of(t);
            d.rootCase = new orbit.protocol.gen.QuestCaseModule();
            d.rootCase.id = id * 100;
            d.rootCase.modifier = List.of(e);
            d.rewards = List.of();
            d.icons = List.of();
            return d;
        }
    }

    @Test
    void atTheHomeBaseTheShipTakesWhatItCanDoAsTheClientDoes() {
        int base = MapCatalog.bundled().byName("3-8").id();
        Sim sim = new Sim(BotConfig.empty(), base, 5_000, 5_000);
        Giver giver = new Giver(sim, 9_736, 3_400);
        QuestGiverVisit v = new QuestGiverVisit(sim.ctx, Set.of(Quests.MISSION, Quests.DAILY, Quests.WEEKLY),
                q -> q.leaves().stream().allMatch(c -> c.type() == Quests.KILL_NPC));
        for (int t = 0; t < 3_000 && !v.done(); t++) {
            giver.tick();
            v.tick();
            sim.now += 100 * Sim.MS;
        }
        assertEquals(QuestGiverVisit.Step.DONE, v.step(), v.status());
        // one mission slot: 122004 only; the daily needs no slot; the gate quest is read and left
        assertEquals(List.of(122004, 317029), v.taken());
        List<String> order = sim.sent.stream().map(p -> p.getClass().getSimpleName())
                .filter(n -> n.startsWith("Quest") || n.equals("MapAssetActivationRequest")).toList();
        assertEquals("MapAssetActivationRequest", order.getFirst(), order.toString());
        assertEquals(List.of("QuestGiverApproachedRequest", "QuestSettingsRequest"), order.subList(1, 3));
        assertEquals("QuestGiverWindowClosedRequest", order.getLast());
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof QuestAcceptRequest r && r.questId == 606009), "the gate quest");
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof QuestInfoRequest r && r.questId == 118015), "running already");
        assertTrue(sim.sent.stream().filter(p -> p instanceof QuestGiverApproachedRequest r && r.questGiverId == GIVER).count() == 1);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof QuestSettingsRequest s && s.availableFilter && s.unattemptedFilter));
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof QuestGiverWindowClosedRequest r && r.questGiverId == GIVER));
        var h = sim.world.snapshot(sim.now).hero();
        assertTrue(Math.hypot(h.x() - 9_736, h.y() - 3_400) < 400, "by the giver");
        assertEquals(base, h.mapId(), "the giver of the base it is on");
    }

    @Test
    void theNearestOwnBaseIsVisited() {
        // live 2026-11-12 on ru1: EIC on 1-3 flew 6 jumps to 2-8 though 2-1 is three away (givers also on x-4, x-5:
        // 2-4 is as near from 1-3 as 2-1 — the order breaks the tie, x-1 before x-4)
        Sim sim = new Sim(BotConfig.empty(), MapCatalog.bundled().byName("1-3").id(), 5_000, 5_000);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = MapCatalog.bundled().byName("1-3").id();
        si.factionId = 2;
        si.x = 5_000;
        si.y = 5_000;
        si.speed = 400;
        si.hitPoints = 100_000;
        si.hitMax = 100_000;
        sim.world.apply(si, sim.now);
        QuestGiverVisit v = new QuestGiverVisit(sim.ctx, Set.of(Quests.DAILY), q -> true);
        v.tick();
        assertTrue(v.status().endsWith("на 2-1") || v.status().endsWith("на 2-4"), v.status());
    }

    @Test
    void aGiverInSightIsVisitedWithoutAFlight() {
        int map = MapCatalog.bundled().byName("3-5").id(); // x-5: a giver there too (the user)
        Sim sim = new Sim(BotConfig.empty(), map, 5_000, 5_000);
        new Giver(sim, 6_000, 5_000);
        QuestGiverVisit v = new QuestGiverVisit(sim.ctx, Set.of(Quests.DAILY), q -> true);
        v.tick();
        assertTrue(v.status().endsWith("на 3-5") || v.step() != QuestGiverVisit.Step.TO_BASE, v.status());
    }

    @Test
    void aMapWhereTheBotSawAGiverIsTriedToo() {
        // the user 2026-11-12: if 5-2 has a quest giver, no need to fly elsewhere
        int map52 = MapCatalog.bundled().byName("5-2").id();
        Sim sim = new Sim(BotConfig.empty(), map52, 5_000, 5_000);
        QuestGiverVisit v = new QuestGiverVisit(sim.ctx, Set.of(Quests.DAILY), q -> true).withGiverMaps(Set.of(map52));
        v.tick();
        assertTrue(v.step() != QuestGiverVisit.Step.TO_BASE || v.status().endsWith("на 5-2"), v.status());
        // another company's base is never one of them
        QuestGiverVisit e = new QuestGiverVisit(sim.ctx, Set.of(Quests.DAILY), q -> true)
                .withGiverMaps(Set.of(MapCatalog.bundled().byName("1-8").id()));
        e.tick();
        assertTrue(!e.status().endsWith("на 1-8"), e.status());
    }
}
