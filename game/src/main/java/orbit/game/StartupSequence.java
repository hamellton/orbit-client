package orbit.game;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import orbit.protocol.Packet;
import orbit.protocol.gen.AudioSettingsModule;
import orbit.protocol.gen.AudioSettingsRequest;
import orbit.protocol.gen.CommandCenterGetDontShowRequest;
import orbit.protocol.gen.GalaxyGateBuilderInfoRequest;
import orbit.protocol.gen.HangarListRequest;
import orbit.protocol.gen.KeepAlive;
import orbit.protocol.gen.LabUpdateRequest;
import orbit.protocol.gen.LegacyModule;
import orbit.protocol.gen.ReadyRequest;
import orbit.protocol.gen.SpaceSearchRequest;
import orbit.protocol.gen.UserSettingsCommand;

/**
 * Packets sent after the server confirmed the login ({@code LoginResponse}), mirroring what the
 * official client does once the ship and the map are loaded. Runs on its own thread; sleeps
 * between packets.
 */
public interface StartupSequence {

    /**
     * What the sequence may depend on.
     *
     * @param firstConnect the first game connection of this run (the client is starting up, its
     *                     UI does not exist yet); {@code false} after a map change or reconnect
     * @param settings     the {@code UserSettingsCommand} of this connection, or {@code null}
     * @param portalJump   a jump to a map on the same game server: the server re-initialises the
     *                     ship on the open connection ({@code JumpToServerCommand}, no {@code LoginResponse})
     */
    record Context(boolean firstConnect, UserSettingsCommand settings, boolean portalJump) {

        public Context(boolean firstConnect, UserSettingsCommand settings) {
            this(firstConnect, settings, false);
        }
    }

    void afterLogin(GameConnection connection, Context context) throws InterruptedException;

    /**
     * Bare minimum: {@code ReadyRequest} 0 and 2 about a second after the login, then {@code KeepAlive}.
     * For tests and as a fallback; {@link #clientLike} is what the bot uses.
     */
    static StartupSequence minimal(String readyTypeStr) {
        return (c, ctx) -> {
            Thread.sleep(900 + c.random().nextInt(150));
            c.send(ready(readyTypeStr, ReadyType.MAP_LOADED));
            Thread.sleep(50 + c.random().nextInt(40));
            c.send(ready(readyTypeStr, ReadyType.UI_READY));
            Thread.sleep(80 + c.random().nextInt(60));
            c.sendKeepAlive();
        };
    }

    /** Order and pauses of the official client (recordings of 2026-11-09, see docs/recon/STAGE3.md). */
    static StartupSequence clientLike(ClientProfile profile) {
        return (c, ctx) -> {
            for (Step s : ClientLike.steps(profile, ctx)) {
                Thread.sleep(s.delayMs(c.random()));
                Packet p = s.packet().get();
                if (p != null) {
                    c.send(p);
                }
            }
        };
    }

    /**
     * One packet and the pause before it: uniform in {@code [min, max]} milliseconds, except that
     * with probability {@link #STALL_CHANCE} the client "stalls" (frame render, GC) and the pause
     * is uniform in {@code (max, stallMax]}. A {@code null} packet is skipped.
     */
    record Step(String name, int minDelayMs, int maxDelayMs, int stallMaxMs, Supplier<Packet> packet) {

        static final double STALL_CHANCE = 0.15;

        Step(String name, int minDelayMs, int maxDelayMs, Supplier<Packet> packet) {
            this(name, minDelayMs, maxDelayMs, maxDelayMs, packet);
        }

        int delayMs(java.util.Random random) {
            if (stallMaxMs > maxDelayMs && random.nextDouble() < STALL_CHANCE) {
                return maxDelayMs + 1 + random.nextInt(stallMaxMs - maxDelayMs);
            }
            return minDelayMs + random.nextInt(maxDelayMs - minDelayMs + 1);
        }
    }

    /** {@code ReadyRequest.readyType} values the client sends after login. */
    final class ReadyType {
        /** {@code READYMESSAGE_MAP_LOADED_2D}: the 2D renderer finished loading the map. */
        public static final short MAP_LOADED = 0;
        /** {@code READYMESSAGE_UI_READY}. */
        public static final short UI_READY = 2;

        private ReadyType() {
        }
    }

    private static ReadyRequest ready(String str, short type) {
        ReadyRequest r = new ReadyRequest();
        r.readyTypeStr = str;
        r.readyType = type;
        return r;
    }

    /** Builds the step lists (separate from the timing loop so tests can check them). */
    final class ClientLike {

        private ClientLike() {
        }

        static List<Step> steps(ClientProfile profile, Context ctx) {
            List<Step> s = new ArrayList<>();
            String view = profile.readyTypeStr();
            if (ctx.firstConnect()) {
                // the client loads the hangar, the map and the UI; ~9 s on a desktop with a warm cache
                s.add(new Step("HangarListRequest", 5_000, 9_500, HangarListRequest::new));
                s.add(new Step("SpaceSearchRequest", 25, 45, () -> spaceSearch(0)));
                s.add(new Step("SpaceSearchRequest", 30, 45, () -> spaceSearch(4)));
                s.add(new Step("SpaceSearchRequest", 0, 10, () -> spaceSearch(2)));
                s.add(new Step("LabUpdateRequest", 5, 12, LabUpdateRequest::new));
                s.add(new Step("ReadyRequest", 1, 5, () -> ready(view, ReadyType.MAP_LOADED)));
                s.add(new Step("ReadyRequest", 45, 65, () -> ready(view, ReadyType.UI_READY)));
                s.add(new Step("LegacyModule", 150, 220, () -> legacy("LF|w")));
                s.add(new Step("AudioSettingsRequest", 35, 60, () -> audio(ctx.settings())));
                s.add(new Step("CommandCenterGetDontShowRequest", 8, 20, CommandCenterGetDontShowRequest::new));
                s.add(new Step("UserKeyBindingsUpdate", 35, 60, profile::keyBindingsPacket));
                s.add(new Step("GalaxyGateBuilderInfoRequest", 20, 35, GalaxyGateBuilderInfoRequest::new));
                s.add(new Step("KeepAlive", 550, 800, StartupSequence.ClientLike::keepAlive));
            } else {
                // the UI already exists: it reports its layout, no hangar/lab reload. After a jump on the
                // same server the client answers the new ShipInitializationCommand right away (30 ms in
                // the one recorded jump), otherwise ~1 s after LoginResponse
                s.add(ctx.portalJump()
                        ? new Step("SpaceSearchRequest", 20, 60, () -> spaceSearch(0))
                        : new Step("SpaceSearchRequest", 700, 1_800, () -> spaceSearch(0)));
                s.add(new Step("SpaceSearchRequest", 12, 45, () -> spaceSearch(4)));
                s.add(new Step("SpaceSearchRequest", 1, 45, () -> spaceSearch(2)));
                s.add(new Step("WindowSettingsRequest", 15, 65, profile::windowSettingsPacket));
                s.add(new Step("ReadyRequest", 0, 30, 280, () -> ready(view, ReadyType.MAP_LOADED)));
                s.add(new Step("ReadyRequest", 15, 70, () -> ready(view, ReadyType.UI_READY)));
                s.add(new Step("LegacyModule", 5, 40, 280, () -> legacy("LF|w")));
                s.add(new Step("AudioSettingsRequest", 0, 40, () -> audio(ctx.settings())));
                s.add(new Step("UserKeyBindingsUpdate", 10, 60, 265, profile::keyBindingsPacket));
                if (!ctx.portalJump()) {
                    // not sent after the recorded jump: the connection and its keep-alive cycle go on
                    s.add(new Step("KeepAlive", 10, 45, 475, StartupSequence.ClientLike::keepAlive));
                    s.add(new Step("CommandCenterGetDontShowRequest", 0, 60, 480, CommandCenterGetDontShowRequest::new));
                }
            }
            return s;
        }

        private static SpaceSearchRequest spaceSearch(int action) {
            SpaceSearchRequest r = new SpaceSearchRequest();
            r.action = (short) action;
            return r;
        }

        private static LegacyModule legacy(String message) {
            LegacyModule m = new LegacyModule();
            m.message = message;
            return m;
        }

        private static AudioSettingsRequest audio(UserSettingsCommand settings) {
            if (settings == null || settings.audioSettingsModule == null) {
                return null;
            }
            AudioSettingsModule a = settings.audioSettingsModule;
            AudioSettingsRequest r = new AudioSettingsRequest();
            r.sound = a.sound;
            r.music = a.music;
            r.voice = a.voice;
            r.playCombatMusic = a.playCombatMusic;
            return r;
        }

        private static KeepAlive keepAlive() {
            KeepAlive k = new KeepAlive();
            k.MouseClick = false;
            return k;
        }
    }
}
