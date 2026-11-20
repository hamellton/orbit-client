package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import orbit.config.BotConfig;
import org.junit.jupiter.api.Test;

/** The account's start ship («Запускать на»): switched to at the session's start, the two-ship palladium aside. */
class StartShipTest {

    private static BotConfig collect() {
        return BotConfig.empty().withModule(BotConfig.Module.COLLECT)
                .withGeneral(new BotConfig.General(93, null, null, null, false, false, 300, false, false));
    }

    @Test
    void anotherShipActiveOnTheSiteIsSwitchedToTheStartShip() {
        Sim sim = new Sim(collect(), 93, 10_000, 6_000);
        sim.ctx.ships().setStartShip(700002);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(700001);
        sim.run(r::tick, 3_000);
        assertEquals(700002, r.hangarRequest());
    }

    @Test
    void onTheStartShipAlreadyNothingChanges() {
        Sim sim = new Sim(collect(), 93, 10_000, 6_000);
        sim.ctx.ships().setStartShip(700002);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(700002);
        sim.run(r::tick, 3_000);
        assertEquals(0, r.hangarRequest());
    }

    @Test
    void noStartShipChosenTheActiveOneStays() {
        Sim sim = new Sim(collect(), 93, 10_000, 6_000);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(700001);
        sim.run(r::tick, 3_000);
        assertEquals(0, r.hangarRequest());
    }

    @Test
    void inTheTwoShipPalladiumTheCollectShipRules() throws Exception {
        Sim sim = new Sim(PalladiumModuleTest.twoShips(), 93, 10_000, 6_000);
        sim.ctx.ships().setPalladium(500101, 500102);
        sim.ctx.ships().setStartShip(700002);
        BotRunner r = new BotRunner(sim.ctx, () -> false, s -> { });
        r.setActiveHangar(500101);
        sim.run(r::tick, 3_000);
        assertEquals(0, r.hangarRequest(), "on the collect ship, an empty hold: it stays");
    }
}
