package orbit.bot.level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** The Nostromo's own kit (invented hangar views). */
class PalladiumKitTest {

    static final String AROL = "equipment_extra_cpu_arol-x";
    static final String RLLB = "equipment_extra_cpu_rllb-x";
    static final String REP2 = "equipment_extra_repbot_rep-2";
    static final String REP4 = "equipment_extra_repbot_rep-4";
    static final String G79 = "equipment_generator_speed_g3n-7900";
    static final String B02 = "equipment_generator_shield_sg3n-b02";
    static final int MAIN = 10;
    static final int NOSTROMO = 20;

    static SiteHangars.Owned own(String id, String loot, int hangar) {
        return new SiteHangars.Owned(id, loot, hangar);
    }

    @Test
    void theMainShipTakesOneOfEachKindAndTheBestRepairRobot() {
        // free extras places on the main ship: a second AROL-X is not taken, REP-4 replaces REP-2
        var h = new SiteHangars.HangarInfo(Map.of(), Map.of(), 0, "", List.of(own("a1", AROL, MAIN), own("a2", AROL, 0),
                own("r2", REP2, MAIN), own("r4", REP4, 0)), Map.of(1, Map.of("extras", List.of("a1", "r2")),
                2, Map.of("extras", List.of("a1", "r2"))));
        var moves = FitPlanner.plan(h, MAIN, Map.of("extras", 6), LevelPlan.bundled());
        assertTrue(moves.stream().noneMatch(m -> m.itemId().equals("a2")), "no second AROL-X: " + moves);
        assertTrue(moves.stream().anyMatch(m -> m.itemId().equals("r4") && m.toShip()), "REP-4 in: " + moves);
        assertTrue(moves.stream().anyMatch(m -> m.itemId().equals("r2") && !m.toShip()), "REP-2 out: " + moves);
    }

    @Test
    void theKitBidsForWhatIsShortOfTwoOnceTheNostromoIsThere() {
        var kit = PalladiumKit.bundled();
        var h = new SiteHangars.HangarInfo(Map.of(), Map.of(), 0, "", List.of(own("a1", AROL, MAIN), own("a2", AROL, 0),
                own("l1", RLLB, MAIN)), Map.of());
        assertTrue(PalladiumKit.missingExtras(kit, h, false).isEmpty(), "no Nostromo: nothing");
        var missing = PalladiumKit.missingExtras(kit, h, true);
        assertTrue(missing.contains(RLLB), "one RLLB-X: a second wanted " + missing);
        assertTrue(!missing.contains(AROL), "two AROL-X already " + missing);
        var goals = PalladiumKit.auctionGoals(missing);
        assertEquals(List.of(RLLB), goals.stream().filter(g -> g.auction().contains(RLLB)).findFirst().orElseThrow().auction());
        assertTrue(goals.stream().allMatch(LevelPlan.Goal::side));
    }

    @Test
    void theNostromoIsFittedFromTheSparesNeverFromTheMainShip() {
        var kit = PalladiumKit.bundled();
        // the main ship holds a1 and its G3N; spares: a second AROL-X, two engines, a B02
        var view = new SiteHangars.HangarInfo(Map.of(), Map.of(), 0, "", List.of(own("a1", AROL, MAIN), own("a2", AROL, 0),
                own("g1", G79, MAIN), own("g2", G79, 0), own("g3", G79, 0), own("b1", B02, 0)), Map.of());
        var drones = new SiteHangars.DroneView(List.of(new SiteHangars.Drone("d1", "drone_flax", 1)),
                Map.of(1, Map.of("d1", List.of()), 2, Map.of("d1", List.of())));
        var slots = Map.of("extras", 3, "generators", 10);
        var before = PalladiumKit.moves(kit, view, drones, NOSTROMO, slots, false);
        assertTrue(before.stream().anyMatch(m -> m.itemId().equals("a2") && m.toShip()), "its own AROL-X: " + before);
        assertTrue(before.stream().noneMatch(m -> m.itemId().equals("a1")), "the main ship's stays: " + before);
        assertTrue(before.stream().noneMatch(m -> m.loot().equals(G79)), "engines only after the P.E.T.: " + before);
        assertTrue(before.stream().anyMatch(m -> m.itemId().equals("b1") && "d1".equals(m.droneId())), "shield in its drone");
        var after = PalladiumKit.moves(kit, view, drones, NOSTROMO, slots, true);
        assertEquals(2, after.stream().filter(m -> m.loot().equals(G79)).map(FitPlanner.Move::itemId).distinct().count(),
                "the two spare engines (in both configs): " + after);
        assertEquals(8, PalladiumKit.enginesMissing(kit, view, NOSTROMO, slots), "ten places, two spare");
    }

    @Test
    void theCollectorCountsByItsLevelWhereverItStands() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"data": {"map": {"lootIds": ["equipment_petgear_g-ar1"]}, "ret": {"items": [
                  {"I": "g1", "L": 0, "LV": 0, "EQH": null},
                  {"I": "g3", "L": 0, "LV": 2, "EQH": "20", "EQT": "PET"}], "hangars": []}}}
                """);
        var h = SiteHangars.hangar(root, 10);
        assertEquals(1, h.spare().get("equipment_petgear_g-ar1@1"));
        assertEquals(1, h.spare().get("equipment_petgear_g-ar1@3"), "fitted on a P.E.T. counts too");
        var gears = SiteHangars.gears(root, "equipment_petgear_g-ar1");
        assertEquals(List.of(new SiteHangars.Gear("g1", "equipment_petgear_g-ar1", 1, 0),
                new SiteHangars.Gear("g3", "equipment_petgear_g-ar1", 3, 20)), gears);
        var kit = PalladiumKit.bundled();
        assertEquals("equipment_petgear_g-ar1", kit.collector());
        assertEquals(3, kit.collectorLevel());
    }

    @Test
    void aFavouriteShipIsReadFromTheHangarList() throws Exception {
        var root = new com.fasterxml.jackson.databind.ObjectMapper().readTree("""
                {"data": {"ret": {"shipInfo": [{"lootId": "ship_nostromo", "owned": 1, "fav": 1},
                  {"lootId": "ship_vengeance", "owned": 1, "fav": 0}]}}}
                """);
        assertTrue(SiteHangars.favourite(root, "ship_nostromo"));
        assertTrue(!SiteHangars.favourite(root, "ship_vengeance"), "not a favourite: not in the warp window");
        assertTrue(!SiteHangars.favourite(root, "ship_goliath"), "not listed");
    }

    @Test
    void theMainShipsExtrasOutForAMomentStayItsOwn() {
        // st2 2026-11-26: the main ship holds SLE-04, REP-4, NC-RRB-X; AROL-X and RLLB-X went into the inventory when
        // its SLE-03 came off and the Nostromo took them; a spare REP-2 is a second repair robot for the Nostromo
        String rrb = "equipment_extra_cpu_nc-rrb-x";
        String sle4 = "equipment_extra_cpu_sle-04";
        var view = new SiteHangars.HangarInfo(Map.of(), Map.of(), 0, "", List.of(own("s4", sle4, MAIN), own("r4", REP4, MAIN),
                own("n1", rrb, MAIN), own("a1", AROL, NOSTROMO), own("l1", RLLB, 0), own("r2", REP2, 0)),
                Map.of(1, Map.of("extras", List.of("a1")), 2, Map.of("extras", List.of("a1"))));
        var moves = PalladiumKit.moves(PalladiumKit.bundled(), view, null, NOSTROMO, Map.of("extras", 3), false,
                java.util.Set.of("sle", "repbot", rrb));
        assertTrue(moves.stream().anyMatch(m -> m.itemId().equals("a1") && !m.toShip()), "AROL-X back out: " + moves);
        assertTrue(moves.stream().noneMatch(m -> m.itemId().equals("l1") && m.toShip()), "RLLB-X left for the main: " + moves);
        assertTrue(moves.stream().anyMatch(m -> m.itemId().equals("r2") && m.toShip()), "REP-2 for the Nostromo: " + moves);
    }
}
