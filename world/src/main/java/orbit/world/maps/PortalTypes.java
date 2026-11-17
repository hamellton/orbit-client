package orbit.world.maps;

import java.util.Map;

/**
 * Where a special portal leads, by its type ({@code JumpgateCreateCommand.designId}): the galaxy gates and event maps
 * as DarkBot's {@code StarManager} knows them ({@code accessBy}/{@code accessOnlyBy}). Type 1 is the plain portal of the
 * normal maps. A gate's portal leads nowhere until the gate is built (QZ on x-7, the gates on x-1).
 */
public final class PortalTypes {

    private static final Map<Integer, String> GATES = Map.ofEntries(
            Map.entry(2, "GG α"), Map.entry(3, "GG β"), Map.entry(4, "GG γ"), Map.entry(5, "GG δ"),
            Map.entry(16, "GG Y6"), Map.entry(53, "GG ε"), Map.entry(54, "GG ζ"), Map.entry(70, "GG κ"),
            Map.entry(71, "GG λ"), Map.entry(72, "GG Kronos"), Map.entry(73, "GG Cold Wave"), Map.entry(74, "GG Hades"),
            Map.entry(34, "LoW / Devolarium Attack"), Map.entry(81, "GG VoT"), Map.entry(82, "GG ς"),
            Map.entry(84, "Quarantine Zone"), Map.entry(24, "GoP Normal"), Map.entry(235, "GoP Easy"),
            Map.entry(238, "Treacherous Domain"), Map.entry(239, "Treacherous Domain"));

    private PortalTypes() {
    }

    /** The gate or event map a portal of this type leads to, {@code null} for a plain or unknown one. */
    public static String gate(int designId) {
        return GATES.get(designId);
    }
}
