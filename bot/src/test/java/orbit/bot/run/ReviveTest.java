package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import orbit.protocol.gen.KillScreenOptionTypeModule;
import orbit.protocol.gen.PriceModule;
import orbit.world.WorldSnapshot;
import org.junit.jupiter.api.Test;

class ReviveTest {

    static final int BASE = KillScreenOptionTypeModule.OPTIONTYPE_BASIC_REPAIR;
    static final int PORTAL = KillScreenOptionTypeModule.OPTIONTYPE_AT_JUMPGATE_REPAIR;
    static final int SPOT = KillScreenOptionTypeModule.OPTIONTYPE_AT_DEATHLOCATION_REPAIR;

    static WorldSnapshot.RepairOption opt(int type, int price, boolean affordable) {
        return new WorldSnapshot.RepairOption(type, PriceModule.CURRENCYTYPE_URIDIUM, price, affordable, 0, "b");
    }

    static WorldSnapshot.Death death(WorldSnapshot.RepairOption... o) {
        return new WorldSnapshot.Death("x", 0, List.of(o));
    }

    @Test
    void takesTheChosenPlaceWhateverItCosts() {
        var d = death(opt(BASE, 0, true), opt(PORTAL, 200, true), opt(SPOT, 300, true));
        assertEquals(PORTAL, Revive.choose(d, "PORTAL").type());
        assertEquals(SPOT, Revive.choose(d, "SPOT").type());
        assertEquals(BASE, Revive.choose(d, "BASE").type());
    }

    @Test
    void theBaseWhenTheChoiceIsNotAffordable() {
        var d = death(opt(BASE, 0, true), opt(PORTAL, 200, false), opt(SPOT, 300, false));
        assertEquals(BASE, Revive.choose(d, "PORTAL").type());
    }

    @Test
    void aSingleOptionIsTaken() {
        assertEquals(SPOT, Revive.choose(death(opt(SPOT, 300, true)), "BASE").type());
    }

    @Test
    void describesOptions() {
        assertEquals("у портала — 200 уридия", Revive.describe(opt(PORTAL, 200, true)));
        assertEquals("на базе — бесплатно", Revive.describe(opt(BASE, 0, true)));
    }
}
