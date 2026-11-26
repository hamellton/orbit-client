package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import orbit.protocol.Packet;
import orbit.protocol.gen.MiniClientRewardClaimRequest;
import orbit.protocol.gen.MiniClientRewardDayInfoCommand;
import orbit.protocol.gen.MiniClientRewardInfoCommand;
import org.junit.jupiter.api.Test;

class MiniClientRewardTest {

    @Test
    void claimsOnceWhenClaimableAndStopsWhenTheServerSaysDone() {
        MiniClientReward r = new MiniClientReward();
        List<Packet> sent = new ArrayList<>();
        r.tick(0, true, sent::add);
        assertTrue(sent.isEmpty(), "nothing from the server yet");
        MiniClientRewardInfoCommand info = new MiniClientRewardInfoCommand();
        info.isClaimable = true;
        r.onPacket(info);
        r.tick(1_000, false, sent::add);
        assertTrue(sent.isEmpty(), "calendars off for the account");
        r.tick(1_000, true, sent::add);
        r.tick(2_000, true, sent::add);
        assertEquals(1, sent.size(), "one claim, the next only after the retry time");
        assertTrue(sent.getFirst() instanceof MiniClientRewardClaimRequest);
        MiniClientRewardDayInfoCommand done = new MiniClientRewardDayInfoCommand();
        done.daysClaimed = 1;
        done.isClaimable = false;
        r.onPacket(done);
        r.tick(1_000_000, true, sent::add);
        assertEquals(1, sent.size(), "claimed: nothing more today");
    }
}
