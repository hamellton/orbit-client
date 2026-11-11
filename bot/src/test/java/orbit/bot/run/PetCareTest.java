package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import orbit.config.BotConfig;
import orbit.protocol.gen.PetGearActivationRequest;
import orbit.protocol.gen.PetGearTypeModule;
import orbit.protocol.gen.PetRequest;
import org.junit.jupiter.api.Test;

class PetCareTest {

    private static BotConfig config(String gear, double comboBelow) {
        return BotConfig.empty().withPet(new BotConfig.Pet(true, gear, 13, false, comboBelow));
    }

    /** The account has a pet, alive with fuel, not launched (as after a login). */
    private static void owned(Sim sim) {
        orbit.protocol.gen.PetInitializationCommand init = new orbit.protocol.gen.PetInitializationCommand();
        init.hasPet = true;
        init.hasFuel = true;
        init.petIsAlive = true;
        sim.world.apply(init, sim.now);
    }

    /** What the server answered to the launch on 2026-11-11: the pet, its modes, Passive. */
    private static void launched(Sim sim) {
        orbit.protocol.gen.PetHeroActivationCommand a = new orbit.protocol.gen.PetHeroActivationCommand();
        a.ownerId = 1;
        a.petId = 500;
        a.petName = "P";
        a.x = 10_000;
        a.y = 6_000;
        sim.world.apply(a, sim.now);
        sim.world.apply(new orbit.protocol.gen.PetGearResetCommand(), sim.now);
        for (int g : new int[] {1, 2, 5, 6, 8, 10, 11}) {
            orbit.protocol.gen.PetGearAddCommand add = new orbit.protocol.gen.PetGearAddCommand();
            add.gearType = new PetGearTypeModule();
            add.gearType.typeValue = (short) g;
            add.enabled = true;
            sim.world.apply(add, sim.now);
        }
        select(sim, 1);
    }

    private static void select(Sim sim, int gear) {
        orbit.protocol.gen.PetGearSelectCommand s = new orbit.protocol.gen.PetGearSelectCommand();
        s.gearType = new PetGearTypeModule();
        s.gearType.typeValue = (short) gear;
        s.optionalParams = List.of();
        sim.world.apply(s, sim.now);
    }

    private static List<PetGearActivationRequest> gears(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof PetGearActivationRequest).map(p -> (PetGearActivationRequest) p).toList();
    }

    private static long launches(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof PetRequest r && r.petRequestType == PetRequest.PETREQUESTTYPE_LAUNCH).count();
    }

    @Test
    void launchesThenSetsTheLocatorOnceAndDoesNotAskAgainForWantOfAnAnswer() {
        Sim sim = new Sim(config("ENEMY_LOCATOR", 0), 26, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 1_000);
        assertEquals(0, launches(sim), "a moment first, like a player");
        sim.run(pet::tick, 5_000);
        assertEquals(1, launches(sim));
        launched(sim);
        sim.run(pet::tick, 40_000); // the server names the mode 12–32 s late, or not at all
        List<PetGearActivationRequest> g = gears(sim);
        assertEquals(1, g.size(), "asked once: " + g);
        assertEquals(PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR, g.getFirst().gearTypeToActivate.typeValue);
        assertEquals(13, g.getFirst().optParam, "Cubikon");
    }

    @Test
    void whenTheServerPutsGuardTheModeIsAskedForAgain() {
        Sim sim = new Sim(config("ENEMY_LOCATOR", 0), 26, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.run(pet::tick, 5_000);
        assertEquals(1, gears(sim).size());
        select(sim, 2); // e.g. after a combo repair
        sim.run(pet::tick, 6_000);
        assertEquals(2, gears(sim).size(), "the locator again");
    }

    @Test
    void comboRepairOnlyWhenNothingHitTheShipForAWhile() {
        Sim sim = new Sim(config("ENEMY_LOCATOR", 0.5), 26, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.run(pet::tick, 3_000);
        orbit.protocol.gen.AttackHitCommand hit = new orbit.protocol.gen.AttackHitCommand();
        hit.attackerId = 99;
        hit.victimId = 1;
        hit.victimHitpoints = 30_000;
        hit.victimShield = 0;
        sim.world.apply(hit, sim.now);
        sim.run(pet::tick, 8_000);
        int combo = PetGearTypeModule.PETGEARTYPE_COMBO_SHIP_REPAIR;
        assertTrue(gears(sim).stream().noneMatch(r -> r.gearTypeToActivate.typeValue == combo), "hit 8 s ago: the server would refuse");
        sim.run(pet::tick, 5_000);
        assertEquals(1, gears(sim).stream().filter(r -> r.gearTypeToActivate.typeValue == combo).count(), "quiet 13 s: repair");
    }

    @Test
    void aDestroyedPetIsRepaired() {
        Sim sim = new Sim(config("GUARD", 0), 26, 10_000, 6_000);
        owned(sim);
        orbit.protocol.gen.PetIsDestroyedCommand d = new orbit.protocol.gen.PetIsDestroyedCommand();
        sim.world.apply(d, sim.now);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 2_000);
        assertEquals(1, sim.sent.stream().filter(p -> p instanceof PetRequest r
                && r.petRequestType == PetRequest.PETREQUESTTYPE_REPAIR_DESTROYED_PET).count());
        assertEquals(0, launches(sim), "not launched while destroyed");
    }
}
