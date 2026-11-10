package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import orbit.config.BotConfig;
import orbit.protocol.gen.GroupInitializationCommand;
import orbit.protocol.gen.GroupMemberInitializationCommand;
import orbit.protocol.gen.InvitationAcceptRequest;
import orbit.protocol.gen.InvitationCreationRequest;
import orbit.protocol.gen.InvitationInitializationCommand;
import orbit.protocol.gen.InvitationRejectRequest;
import orbit.protocol.gen.KickMemberRequest;
import orbit.protocol.gen.LeaveGroupRequest;
import org.junit.jupiter.api.Test;

class GroupWatchTest {

    static Sim sim(BotConfig.GroupRules g) {
        return new Sim(BotConfig.empty().withGroup(g), 1, 5_000, 5_000);
    }

    static void invited(Sim sim, int inviter, String name) {
        InvitationInitializationCommand c = new InvitationInitializationCommand();
        c.inviterId = inviter;
        c.inviterName = name;
        c.candidateId = 1;
        c.candidateName = "me";
        sim.world.apply(c, sim.now);
    }

    static void group(Sim sim, int leader, int... ids) {
        GroupInitializationCommand g = new GroupInitializationCommand();
        g.id = 9;
        g.leaderId = leader;
        for (int id : ids) {
            GroupMemberInitializationCommand m = new GroupMemberInitializationCommand();
            m.id = id;
            m.name = id == 1 ? "me" : "p" + id;
            g.members.add(m);
        }
        sim.world.apply(g, sim.now);
    }

    static void run(Sim sim, GroupWatch w, long ms) {
        for (long t = 0; t < ms; t += 100) {
            w.tick();
            sim.now += 100 * Sim.MS;
        }
    }

    static long count(Sim sim, Class<?> type) {
        return sim.sent.stream().filter(type::isInstance).count();
    }

    @Test
    void acceptsOnlyListedAndRejectsTheRest() {
        Sim sim = sim(new BotConfig.GroupRules(BotConfig.Accept.LIST, true, false, Set.of("Friend"), false, false, null, null));
        invited(sim, 50, "Stranger");
        invited(sim, 51, "FRIEND");
        GroupWatch w = new GroupWatch(sim.ctx);
        run(sim, w, 20_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof InvitationAcceptRequest a && a.inviterId == 51), "the friend");
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof InvitationRejectRequest r && r.inviterId == 50), "the stranger");
        assertEquals(1, count(sim, InvitationAcceptRequest.class));
    }

    @Test
    void offWithoutRejectIgnoresInvitations() {
        Sim sim = sim(BotConfig.GroupRules.OFF);
        invited(sim, 50, "Anyone");
        run(sim, new GroupWatch(sim.ctx), 20_000);
        assertTrue(sim.sent.isEmpty());
    }

    @Test
    void invitesTheListedOnceInAWhile() {
        Sim sim = sim(new BotConfig.GroupRules(BotConfig.Accept.OFF, false, true, Set.of("Buddy", "[CLAN]"), false, false, null, null));
        run(sim, new GroupWatch(sim.ctx), 60_000);
        assertEquals(1, count(sim, InvitationCreationRequest.class), "Buddy once; a clan is not a name");
    }

    @Test
    void asLeaderKicksTheUnlistedAndLeavesWithoutTheListed() {
        Sim sim = sim(new BotConfig.GroupRules(BotConfig.Accept.OFF, false, false, Set.of("p2"), false, true, null, null));
        group(sim, 1, 1, 2, 3);
        run(sim, new GroupWatch(sim.ctx), 20_000);
        assertTrue(sim.sent.stream().anyMatch(p -> p instanceof KickMemberRequest k && k.memberId == 3));
        assertTrue(sim.sent.stream().noneMatch(p -> p instanceof KickMemberRequest k && k.memberId == 2));

        Sim sim2 = sim(new BotConfig.GroupRules(BotConfig.Accept.OFF, false, false, Set.of("p7"), true, false, null, null));
        group(sim2, 3, 1, 3);
        run(sim2, new GroupWatch(sim2.ctx), 20_000);
        assertEquals(1, count(sim2, LeaveGroupRequest.class));
    }

    @Test
    void theWorldForgetsAnsweredInvitations() {
        Sim sim = sim(BotConfig.GroupRules.OFF);
        invited(sim, 50, "X");
        assertEquals(1, sim.world.snapshot(sim.now).invitations().size());
        orbit.protocol.gen.InvitationRemovalCommand r = new orbit.protocol.gen.InvitationRemovalCommand();
        r.candidateId = 1;
        sim.world.apply(r, sim.now);
        assertEquals(List.of(), sim.world.snapshot(sim.now).invitations());
    }
}
