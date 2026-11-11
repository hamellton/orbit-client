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
import orbit.protocol.gen.JumpRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.MenuActionRequest;
import orbit.protocol.gen.PetGearActivationRequest;
import orbit.protocol.gen.PetGearTypeModule;
import orbit.protocol.gen.PetRequest;
import orbit.protocol.gen.ShipSelectRequest;
import orbit.protocol.gen.UIButtonTriggerRequest;
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

    /**
     * Clicks a box of the game's box check: the client flies straight to it ({@code MoveRequest} to the box itself,
     * kept inside the check's circle) and sends {@code CaptchaCollectRequest} with its index once there.
     */
    public void flyToCheckBox(int x, int y) {
        WorldSnapshot.Hero h = world.get().hero();
        orbit.protocol.gen.MoveRequest m = new orbit.protocol.gen.MoveRequest();
        m.positionX = h.x();
        m.positionY = h.y();
        m.targetX = x;
        m.targetY = y;
        sender.accept(m);
    }

    /** Picks a box of the game's box check (the ship is on it, as after the client's move). */
    public void pickCheckBox(int index) {
        orbit.protocol.gen.CaptchaCollectRequest r = new orbit.protocol.gen.CaptchaCollectRequest();
        r.index = index;
        sender.accept(r);
    }

    /** Clicks the repair bubble of a station's repair asset. */
    public void instantRepair(WorldSnapshot.Asset repairStation) {
        InstantRepairRequest r = new InstantRepairRequest();
        r.repairAssetId = repairStation.id();
        sender.accept(r);
    }

    /** Group window buttons, as the client's {@code GroupSystemMediator} sends them. */
    public void acceptInvitation(int inviterId) {
        orbit.protocol.gen.InvitationAcceptRequest r = new orbit.protocol.gen.InvitationAcceptRequest();
        r.inviterId = inviterId;
        sender.accept(r);
    }

    public void rejectInvitation(int inviterId) {
        orbit.protocol.gen.InvitationRejectRequest r = new orbit.protocol.gen.InvitationRejectRequest();
        r.inviterId = inviterId;
        sender.accept(r);
    }

    public void invite(String name) {
        orbit.protocol.gen.InvitationCreationRequest r = new orbit.protocol.gen.InvitationCreationRequest();
        r.name = name;
        sender.accept(r);
    }

    public void kick(int memberId) {
        orbit.protocol.gen.KickMemberRequest r = new orbit.protocol.gen.KickMemberRequest();
        r.memberId = memberId;
        sender.accept(r);
    }

    public void changeLeader(int memberId) {
        orbit.protocol.gen.ChangeLeaderRequest r = new orbit.protocol.gen.ChangeLeaderRequest();
        r.memberId = memberId;
        sender.accept(r);
    }

    public void leaveGroup() {
        sender.accept(new orbit.protocol.gen.LeaveGroupRequest());
    }

    /** {@code everybody}: all members may invite; else only the leader. */
    public void invitationMode(boolean everybody) {
        orbit.protocol.gen.InvitationModeRequest r = new orbit.protocol.gen.InvitationModeRequest();
        r.mode = new orbit.protocol.gen.InvitationModeModule();
        r.mode.mode = (short) (everybody ? orbit.protocol.gen.InvitationModeModule.INVITATIONMODE_FREE_FOR_ALL
                : orbit.protocol.gen.InvitationModeModule.INVITATIONMODE_BOSS_ONLY);
        sender.accept(r);
    }

    /** Presses jump at the portal the ship is at (the server offers it about 500 units away). */
    public void jump() {
        sender.accept(new JumpRequest());
    }

    /** Switches configuration 1 ↔ 2 ({@code S|CFG|<n>|<userID>|<sessionID>}, {@code ChangeConfingCommand}). */
    public void switchConfig(int config) {
        Login l = login.get();
        LegacyModule m = new LegacyModule();
        m.message = "S|CFG|" + config + "|" + l.userId() + "|" + l.sessionId();
        sender.accept(m);
    }

    /** Jump CPU: asks for the price table ({@code JCPU|GET}, as the client does after a login, a jump or opening the map). */
    public void jumpCpuPrices() {
        legacy("JCPU|GET");
    }

    /** Jump CPU: chooses the target map ({@code JCPU|S|<map>}); the server confirms with its price. */
    public void jumpCpuSelect(int mapId) {
        legacy("JCPU|S|" + mapId);
    }

    /** Jump CPU: jumps to the chosen map ({@code JCPU|J}); a voucher is taken while there are any, uridium after. */
    public void jumpCpuJump() {
        legacy("JCPU|J");
    }

    private void legacy(String message) {
        LegacyModule m = new LegacyModule();
        m.message = message;
        sender.accept(m);
    }

    /**
     * Presses the logout button of the features menu ({@code UIButtonTriggerRequest("logout")}); the
     * server counts down and ends the session with {@code 0|l}. Moving cancels it.
     */
    public void logout() {
        UIButtonTriggerRequest r = new UIButtonTriggerRequest();
        r.itemId = "logout";
        sender.accept(r);
    }

    /** {@code PetRequest}: launch, deactivate, toggle, repair the destroyed pet… ({@code PETREQUESTTYPE_*}). */
    public void pet(int requestType) {
        PetRequest r = new PetRequest();
        r.petRequestType = (short) requestType;
        sender.accept(r);
    }

    /** Selects a pet mode ({@code PetGearTypeModule}, e.g. {@code PETGEARTYPE_GUARD}). */
    public void petGear(int gear) {
        petGear(gear, 0);
    }

    /** …with its parameter: the enemy locator's NPC number (13 = Cubikon, as the client sent it). */
    public void petGear(int gear, int optParam) {
        PetGearActivationRequest r = new PetGearActivationRequest();
        r.gearTypeToActivate = new PetGearTypeModule();
        r.gearTypeToActivate.typeValue = (short) gear;
        r.optParam = (short) optParam;
        sender.accept(r);
    }

    /** Opens the trade window ({@code TradeRequest}, as a click on the refinery or the window's maximise); the
     *  server answers with the ore prices when trading is possible here (at the refinery, or with the pet's trade pod). */
    public void tradeOpen() {
        sender.accept(new orbit.protocol.gen.TradeRequest());
    }

    /** Sells {@code count} of an ore ({@code OreTypeModule} number), as the trade window's «sell» button. */
    public void tradeSell(int oreType, long count) {
        orbit.protocol.gen.TradeSellOreRequest r = new orbit.protocol.gen.TradeSellOreRequest();
        r.toSell = new orbit.protocol.gen.OreCountModule();
        r.toSell.oreType = new orbit.protocol.gen.OreTypeModule();
        r.toSell.oreType.typeValue = (short) oreType;
        r.toSell.count = count;
        sender.accept(r);
    }

    /**
     * Exchanges palladium at 5-2's station: the trade window's palladium button sends the legacy command
     * {@code XCP|<count>} (client {@code OreTradeModule.handleTradeButtonClick}), not {@code TradeSellOreRequest}.
     */
    public void exchangePalladium(long count) {
        legacy("XCP|" + count);
    }

    /** Closes the trade window ({@code TWC}), as the client does after selling. */
    public void tradeClose() {
        legacy("TWC");
    }

    /** Clicks a map asset whose action the server offers ({@code MapAssetActivationRequest}): the refinery before trading. */
    public void activateAsset(int assetId) {
        orbit.protocol.gen.MapAssetActivationRequest r = new orbit.protocol.gen.MapAssetActivationRequest();
        r.mapAssetId = assetId;
        sender.accept(r);
    }
}
