package orbit.world;

import static org.junit.jupiter.api.Assertions.assertEquals;

import orbit.protocol.gen.AttributeCreditsUpdateCommand;
import orbit.protocol.gen.LegacyModule;
import org.junit.jupiter.api.Test;

class SpendingTest {

    private static AttributeCreditsUpdateCommand balance(int credits, int uridium) {
        AttributeCreditsUpdateCommand c = new AttributeCreditsUpdateCommand();
        c.credits = credits;
        c.uridium = uridium;
        return c;
    }

    @Test
    void everyFallOfTheBalanceIsSpent() {
        World w = new World();
        w.apply(balance(1_000_000, 50_000), 0);
        w.apply(balance(1_000_000, 49_800), 1); // a paid repair
        w.apply(balance(1_016_000, 49_800), 2); // a kill: not spent
        w.apply(balance(966_000, 49_800), 3); // ammunition for credits
        LegacyModule lm = new LegacyModule();
        lm.message = "0|LM|ST|URI|-500|49300"; // a gate's life
        w.apply(lm, 4);
        var r = w.snapshot(5).rewards();
        assertEquals(700, r.spentUridium());
        assertEquals(50_000, r.spentCredits());
        assertEquals(500, r.minus(new WorldSnapshot.Rewards(0, 0, 0, 0, java.util.Map.of(), 0, 200)).spentUridium());
    }
}
