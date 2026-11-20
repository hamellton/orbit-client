package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.DispatchCollectRetrieverRequest;
import orbit.protocol.gen.DispatchGetRetrieverListCommand;
import orbit.protocol.gen.DispatchModule;
import orbit.protocol.gen.DispatchSendRetrieverRequest;
import orbit.protocol.gen.DispatchSlotInfoCommand;
import org.junit.jupiter.api.Test;

/** The dispatch as AstraBot does it by packets, PikaPika's limits; R-01 by default. */
class DispatchCareTest {

    static BotConfig config(String json) throws Exception {
        return BotConfig.empty().withModuleSettings(DispatchCare.KEY, (ObjectNode) Json.MAPPER.readTree(json));
    }

    static Sim sim(String json, double credits, double uridium) throws Exception {
        Sim sim = new Sim(config(json), 26, 10_000, 6_000);
        sim.now = 1_000 * Sim.MS; // the world's clock is never 0 (0 is «never told»)
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 26;
        si.x = 10_000;
        si.y = 6_000;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.credits = credits;
        si.uridium = uridium;
        sim.world.apply(si, sim.now);
        return sim;
    }

    /** The server's lists: {id, slot, seconds} in progress; ids available; free slots of 5. */
    static void lists(Sim sim, int[][] running, int[] available, int free) {
        DispatchGetRetrieverListCommand a = new DispatchGetRetrieverListCommand();
        a.statusType = (short) DispatchGetRetrieverListCommand.STATUSTYPE_AVAILABLE;
        for (int id : available) {
            DispatchModule m = new DispatchModule();
            m.id = (short) id;
            a.retrieverList.add(m);
        }
        sim.world.apply(a, sim.now);
        DispatchGetRetrieverListCommand p = new DispatchGetRetrieverListCommand();
        p.statusType = (short) DispatchGetRetrieverListCommand.STATUSTYPE_IN_PROGRESS;
        for (int[] r : running) {
            DispatchModule m = new DispatchModule();
            m.id = (short) r[0];
            m.slotId = (short) r[1];
            m.duration = r[2];
            p.retrieverList.add(m);
        }
        sim.world.apply(p, sim.now);
        DispatchSlotInfoCommand s = new DispatchSlotInfoCommand();
        s.availableSlotCount = (short) free;
        s.currentMaxSlotCount = 5;
        sim.world.apply(s, sim.now);
    }

    /** Ticks, answering each list request with the same lists. */
    static void run(Sim sim, DispatchCare d, long ms, int[][] running, int[] available, int free) {
        for (long t = 0; t < ms; t += 100) {
            int before = sim.sent.size();
            d.tick();
            if (sim.sent.subList(before, sim.sent.size()).stream()
                    .anyMatch(p -> p instanceof orbit.protocol.gen.DispatchGetRetrieverListRequest)) {
                lists(sim, running, available, free);
            }
            sim.now += 100 * Sim.MS;
        }
    }

    static List<Integer> sent(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof DispatchSendRetrieverRequest)
                .map(p -> ((DispatchSendRetrieverRequest) p).dispatchId).toList();
    }

    @Test
    void theNumbersAreTheClients() {
        assertEquals(13, DispatchCare.byName("R-01").id());
        assertEquals(16, DispatchCare.byName("r-04").id());
        assertEquals(200_000, DispatchCare.byName("R-01").credits());
        assertEquals(1, DispatchCare.byName("R-04").permits());
    }

    @Test
    void aFinishedOneIsCollectedAndTheChosenOneSentIntoTheFreeSlot() throws Exception {
        Sim sim = sim("{\"enabled\":true}", 1_000_000, 0);
        DispatchCare d = new DispatchCare(sim.ctx);
        run(sim, d, 10_000, new int[][] {{13, 2, 0}}, new int[] {13, 14}, 1);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof DispatchCollectRetrieverRequest c && c.slotId == 2),
                "slot 2 collected");
        assertTrue(sent(sim).isEmpty(), "the slot not free before the server says so");
        run(sim, d, 10_000, new int[][] {}, new int[] {13, 14}, 1); // collected: the slot free
        assertEquals(13, sent(sim).getFirst(), "R-01 by default");
    }

    @Test
    void notMoreThanTheCountNorWithoutTheMoneyNorInTheEscort() throws Exception {
        Sim full = sim("{\"enabled\":true,\"retriever\":\"R-04\",\"count\":2}", 0, 100_000);
        run(full, new DispatchCare(full.ctx), 15_000, new int[][] {{16, 1, 9_000}, {16, 2, 9_000}}, new int[] {16}, 3);
        assertTrue(sent(full).isEmpty(), "two R-04 at work already");
        Sim poor = sim("{\"enabled\":true,\"retriever\":\"R-04\"}", 0, 4_000);
        run(poor, new DispatchCare(poor.ctx), 15_000, new int[][] {}, new int[] {16}, 5);
        assertTrue(sent(poor).isEmpty(), "R-04 costs 5 000 uridium");
        Sim escort = sim("{\"enabled\":true}", 1_000_000, 0);
        orbit.protocol.gen.ShipInitializationCommand si = new orbit.protocol.gen.ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 430;
        si.hitPoints = 100;
        si.hitMax = 100;
        si.credits = 1_000_000;
        escort.world.apply(si, escort.now);
        run(escort, new DispatchCare(escort.ctx), 15_000, new int[][] {}, new int[] {13}, 5);
        assertTrue(escort.sent.isEmpty(), "nothing on an escort map");
    }

    @Test
    void theServersRefusalStopsTheSendingAWhile() throws Exception {
        Sim sim = sim("{\"enabled\":true}", 1_000_000, 0);
        DispatchCare d = new DispatchCare(sim.ctx);
        sim.now = 1_000 * Sim.MS;
        orbit.protocol.gen.DispatchMessageCommand m = new orbit.protocol.gen.DispatchMessageCommand();
        m.messageType = (short) orbit.protocol.gen.DispatchMessageCommand.MESSAGETYPE_DISPATCH_ERROR_NO_RESOURCE;
        sim.world.apply(m, sim.now);
        run(sim, d, 20_000, new int[][] {}, new int[] {13}, 5);
        assertTrue(sent(sim).isEmpty(), "refused: no send for 30 min");
    }

    @Test
    void onlyTheListInProgressIsAskedAndNotMoreOftenThanAMinute() throws Exception {
        // the official client asks statusType 1 only; no answer — no new ask for a minute (live 2026-11-19 on gb2: every 2 s)
        Sim sim = sim("{\"enabled\":true}", 1_000_000, 0);
        DispatchCare d = new DispatchCare(sim.ctx);
        for (long t = 0; t < 30_000; t += 100) {
            d.tick();
            sim.now += 100 * Sim.MS;
        }
        List<orbit.protocol.gen.DispatchGetRetrieverListRequest> asks = sim.sent.stream()
                .filter(p -> p instanceof orbit.protocol.gen.DispatchGetRetrieverListRequest)
                .map(p -> (orbit.protocol.gen.DispatchGetRetrieverListRequest) p).toList();
        assertEquals(1, asks.size(), "once in 30 s with no answer");
        assertEquals(1, asks.getFirst().statusType);
    }

    @Test
    void offByDefault() throws Exception {
        Sim sim = sim("{}", 1_000_000, 0);
        run(sim, new DispatchCare(sim.ctx), 10_000, new int[][] {}, new int[] {13}, 5);
        assertTrue(sim.sent.isEmpty());
    }
}
