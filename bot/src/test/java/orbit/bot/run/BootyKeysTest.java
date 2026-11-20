package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import orbit.config.BotConfig;
import orbit.protocol.gen.AddBoxCommand;
import orbit.protocol.gen.CollectBoxRequest;
import orbit.protocol.gen.LegacyModule;
import org.junit.jupiter.api.Test;

/** DmPlugin's {@code BootyKeyCollectorManager}: a watched key turns its chest's collecting on and off. */
class BootyKeysTest {

    private static BotConfig config(boolean rowCollects, List<String> keys) {
        return BotConfig.empty().withModule(BotConfig.Module.PALLADIUM)
                .withGeneral(new BotConfig.General(93, null, null, null, false, false, 300, false, false))
                .withCollect(new BotConfig.Collect(Map.of("PIRATE_BOOTY_GOLD", new BotConfig.BoxRule(rowCollects, 5_500, 0)),
                        400, false, true, BotConfig.Collect.CARGO_NONE, 0, null, keys));
    }

    private static boolean collected(BotConfig c, int greenKeys) {
        Sim sim = new Sim(c, 93, 20_000, 20_000);
        LegacyModule k = new LegacyModule();
        k.message = "0|A|BK|" + greenKeys;
        sim.world.apply(k, sim.now);
        AddBoxCommand box = new AddBoxCommand();
        box.hash = "gold1";
        box.boxType = "PIRATE_BOOTY_GOLD";
        box.x = 20_200;
        box.y = 20_000;
        sim.world.apply(box, sim.now);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        sim.run(r::tick, 4_000);
        return sim.sent.stream().anyMatch(p -> p instanceof CollectBoxRequest q && "gold1".equals(q.itemHash));
    }

    @Test
    void greenKeysOpenTheYellowChestThoughItsRowIsOff() {
        assertTrue(collected(config(false, List.of("GREEN")), 3));
    }

    @Test
    void noGreenKeysLeftTheYellowChestIsLeftThoughItsRowIsOn() {
        assertFalse(collected(config(true, List.of("GREEN")), 0));
    }

    @Test
    void anUnwatchedKeyLeavesItToTheRow() {
        // DarkBot itself never looks at keys: the row alone decides
        assertTrue(collected(config(true, List.of()), 0));
        assertFalse(collected(config(false, List.of()), 3));
    }

    @Test
    void keysAreCountedSpentWhenTheirNumberDrops() {
        Stats s = new Stats();
        s.keysSpent(Map.of("BK", 10));
        s.keysSpent(Map.of("BK", 9));
        s.keysSpent(Map.of("BK", 20));
        org.junit.jupiter.api.Assertions.assertEquals(Map.of("BK", 1), s.keysSpent(Map.of("BK", 20)),
                "one opened chest; keys bought later spend nothing");
    }
}
