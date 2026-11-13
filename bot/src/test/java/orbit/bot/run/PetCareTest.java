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

    private static long fuelBuys(Sim sim) {
        return sim.sent.stream().filter(p -> p instanceof PetRequest r
                && r.petRequestType == PetRequest.PETREQUESTTYPE_HOTKEY_BUY_FUEL).count();
    }

    @Test
    void outOfFuelBuysItWithTheGamesKeyWhenTheConfigSaysSoAndGivesUpAfterSomeTries() {
        // AstraBot's buyFuel (the user, 2026-11-13)
        BotConfig c = BotConfig.empty().withPet(new BotConfig.Pet(true, "GUARD", 13, false, 0, null, true));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        owned(sim);
        sim.world.apply(new orbit.protocol.gen.PetOutOfFuelCommand(), sim.now);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 2_000);
        assertEquals(1, fuelBuys(sim), "bought at once");
        sim.run(pet::tick, 30_000);
        assertEquals(1, fuelBuys(sim), "the game is given a minute to send the fuel");
        sim.run(pet::tick, 10 * 60_000);
        assertEquals(PetCare.FUEL_TRIES, fuelBuys(sim), "no fuel came: no more tries");
        assertEquals(0, launches(sim), "not launched without fuel");
    }

    @Test
    void aModuleKeepingThePetInRecallsItAndDoesNotLaunchIt() {
        // AstraBot's petOnlyOnBehemoth (the user, 2026-11-13)
        Sim sim = new Sim(config("GUARD", 0), 26, 10_000, 6_000);
        owned(sim);
        launched(sim);
        PetCare pet = new PetCare(sim.ctx);
        java.util.concurrent.atomic.AtomicReference<Boolean> out = new java.util.concurrent.atomic.AtomicReference<>(false);
        pet.setModuleOut(out::get);
        sim.run(pet::tick, 2_000);
        assertEquals(1, sim.sent.stream().filter(p -> p instanceof PetRequest r
                && r.petRequestType == PetRequest.PETREQUESTTYPE_DEACTIVATE).count(), "recalled");
        out.set(null);
        orbit.protocol.gen.PetDeactivationCommand off = new orbit.protocol.gen.PetDeactivationCommand();
        off.petId = 500;
        sim.world.apply(off, sim.now);
        sim.run(pet::tick, 8_000);
        assertEquals(1, launches(sim), "the config's again: out");
    }

    @Test
    void outOfFuelIsNotBoughtWhenTheConfigDoesNotSaySo() {
        Sim sim = new Sim(config("GUARD", 0), 26, 10_000, 6_000);
        owned(sim);
        sim.world.apply(new orbit.protocol.gen.PetOutOfFuelCommand(), sim.now);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 5 * 60_000);
        assertEquals(0, fuelBuys(sim));
    }

    @Test
    void kamikazeWhileNpcsCrowdTheMainModeAfterAndNotAgainSoonAfterABlast() {
        BotConfig c = BotConfig.empty().withPet(new BotConfig.Pet(true, "ENEMY_LOCATOR", 13, false, 0,
                new BotConfig.Kamikaze(true, 3, false, 600, 5)));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.run(pet::tick, 5_000);
        int k = PetGearTypeModule.PETGEARTYPE_KAMIKAZE;
        assertTrue(gears(sim).stream().noneMatch(r -> r.gearTypeToActivate.typeValue == k), "two NPCs are not a crowd");
        for (int i = 0; i < 3; i++) {
            sim.npc(70 + i, "-=[ Protegit ]=-", 10_100 + i * 50, 6_000);
        }
        sim.run(pet::tick, 5_000);
        assertEquals(k, gears(sim).getLast().gearTypeToActivate.typeValue, "three round the pet: kamikaze");
        orbit.protocol.gen.PetIsDestroyedCommand boom = new orbit.protocol.gen.PetIsDestroyedCommand();
        sim.world.apply(boom, sim.now);
        sim.run(pet::tick, 2_000);
        orbit.protocol.gen.PetRepairCompleteCommand fixed = new orbit.protocol.gen.PetRepairCompleteCommand();
        sim.world.apply(fixed, sim.now);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.run(pet::tick, 10_000);
        assertEquals(PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR, gears(sim).getLast().gearTypeToActivate.typeValue,
                "repaired, launched, the locator again — no kamikaze within 5 minutes though the NPCs are still there");
    }

    @Test
    void kamikazeOnAFlaggedNpc() {
        BotConfig c = BotConfig.empty()
                .withNpcs(java.util.Map.of("-=[ Protegit ]=-", new BotConfig.NpcRule(500, 0, true,
                        java.util.Set.of("KAMIKAZE"), java.util.Set.of(), null)))
                .withPet(new BotConfig.Pet(true, "GUARD", 13, false, 0, new BotConfig.Kamikaze(true, 0, true, 600, 5)));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.npc(70, "-=[ Protegit ]=-", 10_200, 6_000);
        sim.run(pet::tick, 5_000);
        assertEquals(PetGearTypeModule.PETGEARTYPE_KAMIKAZE, gears(sim).getLast().gearTypeToActivate.typeValue);
    }

    @Test
    void withNoLimitOfItsOwnKamikazeWaitsForTheGamesCooldownOnly() {
        BotConfig c = BotConfig.empty()
                .withNpcs(java.util.Map.of("-=[ Cubikon ]=-", new BotConfig.NpcRule(550, 0, true,
                        java.util.Set.of("KAMIKAZE"), java.util.Set.of(), null)))
                .withPet(new BotConfig.Pet(true, "ENEMY_LOCATOR", 13, false, 0, new BotConfig.Kamikaze(true, 0, true, 600, 0)));
        Sim sim = new Sim(c, 26, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.npc(70, "-=[ Cubikon ]=-", 10_300, 6_000);
        // the pet's gears on the item bar, as after the login
        orbit.protocol.gen.MenuInitializationCommand menu = new orbit.protocol.gen.MenuInitializationCommand();
        orbit.protocol.gen.MenuCategoryInitializationCommand cat = new orbit.protocol.gen.MenuCategoryInitializationCommand();
        cat.categoryId = "pet";
        orbit.protocol.gen.MenuItemInitializationCommand it = new orbit.protocol.gen.MenuItemInitializationCommand();
        it.status = new orbit.protocol.gen.MenuItemStatusCommand();
        it.status.menuItemId = "equipment_petgear_g-kk1";
        it.status.available = true;
        it.status.activatable = true;
        cat.menuItems = List.of(it);
        menu.categories = List.of(cat);
        sim.world.apply(menu, sim.now);
        // the gear's button cooling down (as the game would show it after a blast)
        orbit.protocol.gen.MenuItemTimerCommand cd = new orbit.protocol.gen.MenuItemTimerCommand();
        cd.menuItemId = "equipment_petgear_g-kk1";
        cd.activatable = false;
        cd.time = 20_000;
        cd.maxTime = 20_000;
        cd.timerState = new orbit.protocol.gen.TimerStateModule();
        cd.timerState.timerStateValue = 2;
        sim.world.apply(cd, sim.now);
        sim.run(pet::tick, 5_000);
        int k = PetGearTypeModule.PETGEARTYPE_KAMIKAZE;
        assertTrue(gears(sim).stream().noneMatch(r -> r.gearTypeToActivate.typeValue == k), "the game's cooldown runs");
        orbit.protocol.gen.MenuItemTimerCommand ready = new orbit.protocol.gen.MenuItemTimerCommand();
        ready.menuItemId = "equipment_petgear_g-kk1";
        ready.activatable = true;
        ready.timerState = new orbit.protocol.gen.TimerStateModule();
        sim.world.apply(ready, sim.now);
        sim.run(pet::tick, 5_000);
        assertEquals(k, gears(sim).getLast().gearTypeToActivate.typeValue, "ready: at once, no minutes of its own");
    }

    @Test
    void aQuestsNpcTakesTheLocatorWhileItLastsThenTheConfigsModeAgain() {
        // the «Квесты» tab's checkbox: the locator on the quest's NPC (31 = UberSaimon), the config's mode after
        Sim sim = new Sim(config("GUARD", 0), 29, 10_000, 6_000);
        owned(sim);
        PetCare pet = new PetCare(sim.ctx);
        int[] quest = {31};
        pet.setModuleLocator(() -> quest[0]);
        sim.run(pet::tick, 6_000);
        launched(sim);
        sim.run(pet::tick, 10_000);
        List<PetGearActivationRequest> g = gears(sim);
        assertEquals(PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR, g.getLast().gearTypeToActivate.typeValue, g.toString());
        assertEquals(31, g.getLast().optParam);
        quest[0] = 13; // another quest NPC: the same mode, another target — asked again
        sim.run(pet::tick, 10_000);
        assertEquals(13, gears(sim).getLast().optParam, gears(sim).toString());
        quest[0] = 0; // the quest's fight is over
        sim.run(pet::tick, 10_000);
        assertEquals(PetGearTypeModule.PETGEARTYPE_GUARD, gears(sim).getLast().gearTypeToActivate.typeValue);
    }
}
