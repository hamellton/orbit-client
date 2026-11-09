package orbit.bot.act;

import java.util.Random;
import java.util.function.Consumer;
import java.util.function.Supplier;
import orbit.protocol.Packet;
import orbit.protocol.gen.AttackAbortLaserRequest;
import orbit.protocol.gen.AttackLaserRequest;
import orbit.protocol.gen.AttackRocketRequest;
import orbit.protocol.gen.CollectBoxRequest;
import orbit.protocol.gen.HarvestRequest;
import orbit.protocol.gen.InstantRepairRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.PetGearActivationRequest;
import orbit.protocol.gen.PetGearTypeModule;
import orbit.protocol.gen.PetRequest;
import orbit.protocol.gen.ShipSelectRequest;
import orbit.world.WorldSnapshot;

/**
 * Single game actions, built exactly as the client builds them (decompiled client 2026-11-04,
 * checked against the recordings). Several requests carry small arithmetic tweaks the server can
 * check; all recorded requests follow them (attacks 17/17, selections 58/58, box pickups 15/15):
 * <ul>
 *   <li>laser attack: {@code targetX += 1} if {@code (targetX + targetY) % 3 == 0} ({@code MapAssetAttackCommand});</li>
 *   <li>rocket attack: {@code targetY += 1} if {@code (targetX + targetY) % 3 == 0} ({@code ActivateRocketCommand});</li>
 *   <li>selection: {@code targetY -= (targetX + targetY + posY + posX) % 8} ({@code TryToSelectMapAssetCommand});</li>
 *   <li>box pickup: {@code posY += 1} if {@code (boxY + posX + posY) % 3 == 0} ({@code EventManager}).</li>
 * </ul>
 * Timing (reaction pauses) is the caller's job; this class only sends.
 */
public final class Actions {

    /** The client's 2D camera follows the hero; the window size comes from {@code ReadyRequest}. */
    public record Screen(int width, int height) {

        public static final Screen DEFAULT = new Screen(1920, 1022);

        /** {@code "2D 1920x1022 .root1…"} → 1920×1022. */
        public static Screen fromReadyString(String ready) {
            try {
                String[] wh = ready.split(" ")[1].split("x");
                return new Screen(Integer.parseInt(wh[0]), Integer.parseInt(wh[1]));
            } catch (RuntimeException e) {
                return DEFAULT;
            }
        }
    }

    /** User id and game session id, repeated by the configuration switch. */
    public record Login(int userId, String sessionId) {
    }

    /** Click radius of ships in the client ({@code MouseHitAreaTrait.clickRadius}, 50 in all recordings). */
    static final int CLICK_RADIUS = 50;
    /** The mouse lands this far from the ship's screen position at most (recorded: median 29). */
    static final int MOUSE_SPREAD = 40;

    private final Supplier<WorldSnapshot> world;
    private final Consumer<Packet> sender;
    private final Supplier<Login> login;
    private final Screen screen;
    private final Random random;

    public Actions(Supplier<WorldSnapshot> world, Consumer<Packet> sender, Supplier<Login> login, Screen screen, Random random) {
        this.world = world;
        this.sender = sender;
        this.login = login;
        this.screen = screen;
        this.random = random;
    }

    /** Clicks a ship: {@code ShipSelectRequest} with the ship's screen position and the mouse near it. */
    public void select(WorldSnapshot.Ship target) {
        WorldSnapshot.Hero h = world.get().hero();
        // the camera centres on the hero at scale 1 (recordings: median 25 px off, camera lag)
        int screenX = screen.width() / 2 + (target.x() - h.x());
        int screenY = screen.height() / 2 + (target.y() - h.y());
        double a = random.nextDouble() * 2 * Math.PI;
        double r = Math.sqrt(random.nextDouble()) * MOUSE_SPREAD;
        ShipSelectRequest s = new ShipSelectRequest();
        s.targetId = target.id();
        s.targetX = target.x();
        s.targetY = target.y();
        s.posX = (int) Math.round(screenX + r * Math.cos(a));
        s.posY = (int) Math.round(screenY + r * Math.sin(a));
        s.clickX = screenX;
        s.clickY = screenY;
        s.radius = CLICK_RADIUS;
        s.targetY -= (s.targetX + s.targetY + s.posY + s.posX) % 8;
        sender.accept(s);
    }

    /** Starts shooting lasers at the selected ship (double click / attack key). */
    public void attackLaser(WorldSnapshot.Ship target) {
        AttackLaserRequest r = new AttackLaserRequest();
        r.targetId = target.id();
        r.targetX = target.x();
        r.targetY = target.y();
        if ((r.targetX + r.targetY) % 3 == 0) {
            r.targetX++;
        }
        sender.accept(r);
    }

    /** Fires the selected rocket at a ship. */
    public void attackRocket(WorldSnapshot.Ship target) {
        AttackRocketRequest r = new AttackRocketRequest();
        r.targetId = target.id();
        r.targetX = target.x();
        r.targetY = target.y();
        if ((r.targetX + r.targetY) % 3 == 0) {
            r.targetY++;
        }
        sender.accept(r);
    }

    public void stopAttack() {
        sender.accept(new AttackAbortLaserRequest());
    }

    /**
     * Uses an item: ammunition (with a target selected this also starts the attack), rockets,
     * CPUs, abilities, drone formations, pet gears. From the slot bar if it is there (a key), else
     * from the item bar (a click).
     */
    public void useItem(String menuItemId) {
        MenuActionRequest m = new MenuActionRequest();
        m.actionType = (short) MenuActionRequest.ACTIONTYPE_ACTIVATE;
        m.sourceType = (short) (world.get().slotItems().contains(menuItemId)
                ? MenuActionRequest.SOURCETYPE_SLOT_BAR : MenuActionRequest.SOURCETYPE_ITEM_BAR);
        m.menuItemId = menuItemId;
        sender.accept(m);
    }

    /** Picks up a box (the ship must be on it, as after the client's move) or harvests ore. */
    public void collect(WorldSnapshot.Collectable c) {
        if (c.kind() == WorldSnapshot.CollectableKind.ORE) {
            HarvestRequest h = new HarvestRequest();
            h.itemHash = c.hash();
            sender.accept(h);
            return;
        }
        WorldSnapshot.Hero hero = world.get().hero();
        CollectBoxRequest r = new CollectBoxRequest();
        r.itemHash = c.hash();
        r.boxX = c.x();
        r.boxY = c.y();
        r.posX = hero.x();
        r.posY = hero.y();
        if ((r.boxY + r.posX + r.posY) % 3 == 0) {
            r.posY++;
        }
        sender.accept(r);
    }

    /** Clicks the repair bubble of a station's repair asset. */
    public void instantRepair(WorldSnapshot.Asset repairStation) {
        InstantRepairRequest r = new InstantRepairRequest();
        r.repairAssetId = repairStation.id();
        sender.accept(r);
    }

    /** Switches configuration 1 ↔ 2 ({@code S|CFG|<n>|<userID>|<sessionID>}, {@code ChangeConfingCommand}). */
    public void switchConfig(int config) {
        Login l = login.get();
        LegacyModule m = new LegacyModule();
        m.message = "S|CFG|" + config + "|" + l.userId() + "|" + l.sessionId();
        sender.accept(m);
    }

    /** {@code PetRequest}: launch, deactivate, toggle, repair the destroyed pet… ({@code PETREQUESTTYPE_*}). */
    public void pet(int requestType) {
        PetRequest r = new PetRequest();
        r.petRequestType = (short) requestType;
        sender.accept(r);
    }

    /** Selects a pet mode ({@code PetGearTypeModule}, e.g. {@code PETGEARTYPE_GUARD}). */
    public void petGear(int gear) {
        PetGearActivationRequest r = new PetGearActivationRequest();
        r.gearTypeToActivate = new PetGearTypeModule();
        r.gearTypeToActivate.typeValue = (short) gear;
        r.optParam = 0;
        sender.accept(r);
    }
}
