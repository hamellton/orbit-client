package orbit.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class SkylabTest {

    /** Invented numbers in the page's shape (the site's own markup, 2026-11-15). */
    static final String PAGE = """
            <div id="view_prometium" class="view_generally"> <div id="view_prometium_stock">
             <span class="ore_prometium">Prometium:</span><br />
             1,234,567 </div> <div id="view_prometium_maximal"> <div class="view_generally_maximal_bg" style="width: 6%;"></div>
             <div class="view_generally_maximal_number view_promerium_maximal_number">20,000,000</div> </div> </div>
            <div id="view_seprom" class="view_generally"> <div id="view_seprom_stock">
             <span class="ore_seprom">Seprom:</span><br /> 42 </div> <div id="view_seprom_maximal">
             <div class="view_generally_maximal_bg" style="width: 1%;"></div>
             <div class="view_generally_maximal_number view_seprom_maximal_number">9,000</div> </div> </div>
            <div id="module_baseModule_small" class="module module_small" onclick="Skylab.showModule('baseModule');"">
             <table><tr><td><div class="level skylab_font_level">17</div></td></tr></table></div>
            <div id="module_sepromRefinery_small" class="module module_small" onclick="Skylab.showModule('sepromRefinery');"">
             <table><tr><td><div class="level skylab_font_level_inactive">5</div></td></tr></table></div>
            <span id="prometiumCollector_skylabPendingRobots">2</span>
            """;

    @Test
    void modulesOresAndRobots() {
        Skylab.Info i = Skylab.parse(PAGE, 1);
        assertEquals(new Skylab.Module("baseModule", 17, true), i.module("baseModule"));
        assertEquals(new Skylab.Module("sepromRefinery", 5, false), i.module("sepromRefinery"));
        assertEquals(new Skylab.Ore("prometium", 1_234_567, 20_000_000), i.ore("prometium"));
        assertEquals(new Skylab.Ore("seprom", 42, 9_000), i.ore("seprom"));
        assertEquals(2, i.robots().get("prometiumCollector"));
        assertFalse(i.transporting());
        assertTrue(Skylab.parse(PAGE + "<div id=\"progress_timer_transportModule\"></div>", 1).transporting());
    }

    @Test
    void aSavedPageOfTheOwnAccountParses() throws Exception {
        // local only (local/ is not in git): the page saved by SkylabDump
        Path saved = Path.of("../local/skylab-3.html");
        Assumptions.assumeTrue(Files.exists(saved));
        Skylab.Info i = Skylab.parse(Files.readString(saved), 1);
        assertEquals(12, i.modules().size(), i.modules().toString());
        assertEquals(8, i.ores().size(), i.ores().toString());
        assertTrue(i.ores().stream().allMatch(o -> o.amount() >= 0 && o.capacity() > 0), i.ores().toString());
    }
}
