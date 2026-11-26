package orbit.bot.mod;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

class TargetClaimsTest {

    @AfterEach
    void clean() {
        TargetClaims.clearAll();
        MDC.remove("account");
    }

    @Test
    void anotherAccountsTargetIsLeftAloneTillItLetsGo() {
        MDC.put("account", "st1");
        TargetClaims.claim(19, 77);
        assertFalse(TargetClaims.claimedByOther(19, 77), "its own target");
        MDC.put("account", "st2");
        assertTrue(TargetClaims.claimedByOther(19, 77), "st1 fights it");
        assertFalse(TargetClaims.claimedByOther(20, 77), "the same id on another map is another NPC");
        TargetClaims.release(19, 77);
        assertTrue(TargetClaims.claimedByOther(19, 77), "only st1 lets its own claim go");
        MDC.put("account", "st1");
        TargetClaims.release(19, 77);
        MDC.put("account", "st2");
        assertFalse(TargetClaims.claimedByOther(19, 77), "free again");
    }
}
