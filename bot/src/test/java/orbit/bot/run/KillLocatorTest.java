package orbit.bot.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Set;
import orbit.bot.mod.KillModule;
import orbit.config.BotConfig;
import orbit.config.Json;
import orbit.protocol.gen.PetGearLocatorUpdate;
import orbit.protocol.gen.PetGearSelectCommand;
import orbit.protocol.gen.PetGearTypeModule;
import orbit.protocol.gen.PetHeroActivationCommand;
import org.junit.jupiter.api.Test;

/** The invokes' locator (live 2026-11-17 on the VPS, account 7). */
class KillLocatorTest {

    static final String INVOKE = "\\\\ Invoke XVI //";
    static final String IMPULSE = "\\\\ Impulse II //";

    static BotConfig config(boolean dropAll) throws Exception {
        return BotConfig.empty()
                .withLoot(new BotConfig.Loot("ammunition_laser_lcb-10", null, 0, 0, null, true, 0, null, false, false, null))
                .withNpcs(Map.of(
                        INVOKE, new BotConfig.NpcRule(500, -10000, true, Set.of("PET_LOCATOR"), Set.of(), null),
                        IMPULSE, new BotConfig.NpcRule(560, 0, true, Set.of(), Set.of(), null)))
                .withPet(new BotConfig.Pet(true, "ENEMY_LOCATOR"))
                .withModuleSettings("kill", Json.MAPPER.readTree("{\"dropForHigher\": " + dropAll + "}"));
    }

    static void petLocating(Sim sim) {
        PetHeroActivationCommand pet = new PetHeroActivationCommand();
        pet.petId = 55;
        pet.ownerId = 1;
        pet.x = 10_000;
        pet.y = 6_000;
        sim.world.apply(pet, sim.now);
        PetGearSelectCommand gear = new PetGearSelectCommand();
        gear.gearType = new PetGearTypeModule();
        gear.gearType.typeValue = (short) PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR;
        sim.world.apply(gear, sim.now);
    }

    @Test
    void theLocatorKeepsLookingForTheInvokeWhileOneIsFought() throws Exception {
        // dropped while one was in sight: the pet took the config's mode, «ищет Cubikon», blind for the fight and ~5 s
        Sim sim = new Sim(config(false), 307, 10_000, 6_000);
        KillModule m = new KillModule(sim.ctx, true, false);
        sim.npc(7, INVOKE, 10_400, 6_000);
        assertEquals(177, m.petLocator(), "the invoke's number, one in sight or not");
    }

    @Test
    void withDropForHigherTheLocatorsInvokeTakesTheShipOffAnImpulse() throws Exception {
        Sim sim = new Sim(config(true), 307, 10_000, 6_000);
        petLocating(sim);
        KillModule m = new KillModule(sim.ctx, true, false);
        sim.npc(8, IMPULSE, 10_400, 6_000);
        sim.run(m::tick, 2_000);
        PetGearLocatorUpdate point = new PetGearLocatorUpdate();
        point.x = 16_000;
        point.y = 9_000;
        sim.world.apply(point, sim.now);
        sim.run(m::tick, 500);
        var d = sim.ctx.nav().destination();
        assertTrue(Math.hypot(d.x() - 16_000, d.y() - 9_000) < 300, "off to the locator's invoke: " + d);
    }
}
