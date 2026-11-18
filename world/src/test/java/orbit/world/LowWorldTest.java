package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import orbit.protocol.gen.AssetCreateCommand;
import orbit.protocol.gen.AssetRemoveCommand;
import orbit.protocol.gen.AssetTypeModule;
import orbit.protocol.gen.GroupInitializationCommand;
import orbit.protocol.gen.GroupMemberInitializationCommand;
import orbit.protocol.gen.GroupMemberUpdateCommand;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.PlayerQueueRegistrationCommand;
import orbit.protocol.gen.RaidGateGameStageModule;
import orbit.protocol.gen.ShipInitializationCommand;
import org.junit.jupiter.api.Test;

/** The LoW gate in the world model (docs/LOW.md): the relays, the queue, the texts, the members' raid stage. */
class LowWorldTest {

    static World world() {
        World w = new World();
        ShipInitializationCommand si = new ShipInitializationCommand();
        si.userId = 1;
        si.mapId = 200;
        si.x = 1_000;
        si.y = 12_000;
        w.apply(si, 1_000);
        return w;
    }

    @Test
    void aRelayStationStandsAmongTheShipsAsATarget() {
        World w = world();
        AssetCreateCommand a = new AssetCreateCommand();
        a.assetId = 100_000_104;
        a.type = new AssetTypeModule();
        a.type.typeValue = World.RELAY_STATION;
        a.posX = 14_000;
        a.posY = 6_000;
        w.apply(a, 2_000);
        WorldSnapshot s = w.snapshot(2_000);
        assertEquals(World.RELAY_STATION, s.assets().get(100_000_104).type());
        WorldSnapshot.Ship relay = s.ships().get(100_000_104);
        assertTrue(relay != null && relay.npc(), "the relay is a target");
        assertEquals(World.RELAY_NAME, relay.name());
        assertEquals(14_000, relay.x());
        assertFalse(relay.health().known());
        AssetRemoveCommand r = new AssetRemoveCommand();
        r.uid = 100_000_104;
        w.apply(r, 3_000);
        assertNull(w.snapshot(3_000).ships().get(100_000_104));
        // other assets stay out of the ships
        AssetCreateCommand pod = new AssetCreateCommand();
        pod.assetId = 77;
        pod.type = new AssetTypeModule();
        pod.type.typeValue = 33;
        w.apply(pod, 4_000);
        assertNull(w.snapshot(4_000).ships().get(77));
    }

    @Test
    void theQueueAndTheGatesTextsAreKept() {
        World w = world();
        PlayerQueueRegistrationCommand q = new PlayerQueueRegistrationCommand();
        q.gateId = 200;
        q.positionInQueue = 2;
        w.apply(q, 2_000);
        WorldSnapshot.Low low = w.snapshot(2_000).gates().low();
        assertEquals(200, low.queueGate());
        assertEquals(2, low.queuePosition());
        assertEquals(2_000, low.queueNanos());
        text(w, "The LoW Gate has been brought online!", 3_000);
        text(w, "Relay station activated", 4_000);
        text(w, "Relay station activated", 5_000);
        text(w, "You’ve completed the LoW Gate. Great job!", 6_000);
        low = w.snapshot(6_000).gates().low();
        assertEquals(3_000, low.onlineNanos());
        assertEquals(2, low.relays());
        assertEquals(5_000, low.relayNanos());
        assertEquals(6_000, low.completedNanos());
    }

    static void text(World w, String t, long nanos) {
        LegacyModule l = new LegacyModule();
        l.message = "0|A|STD|" + t;
        w.apply(l, nanos);
    }

    @Test
    void aMembersRaidStageIsRead() {
        World w = world();
        GroupInitializationCommand g = new GroupInitializationCommand();
        g.id = 9;
        g.leaderId = 1;
        GroupMemberInitializationCommand me = new GroupMemberInitializationCommand();
        me.id = 1;
        me.name = "me";
        GroupMemberInitializationCommand other = new GroupMemberInitializationCommand();
        other.id = 2;
        other.name = "p2";
        other.raidGateGameStage = new RaidGateGameStageModule();
        other.raidGateGameStage.isInGate = true;
        other.raidGateGameStage.gameStage = 2;
        g.members.add(me);
        g.members.add(other);
        w.apply(g, 2_000);
        WorldSnapshot.Member m = w.snapshot(2_000).group().members().get(1);
        assertTrue(m.inGate());
        assertEquals(2, m.gateStage());
        GroupMemberUpdateCommand u = new GroupMemberUpdateCommand();
        u.playerId = 2;
        RaidGateGameStageModule r = new RaidGateGameStageModule();
        r.isInGate = false;
        r.gameStage = 0;
        u.updates.add(r);
        w.apply(u, 3_000);
        m = w.snapshot(3_000).group().members().get(1);
        assertFalse(m.inGate());
        assertEquals("p2", m.name());
    }
}
