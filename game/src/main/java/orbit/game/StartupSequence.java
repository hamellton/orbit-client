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
            List<List<Step>> variants = ClientLike.variants(profile, ctx);
            for (Step s : variants.get(c.random().nextInt(variants.size()))) {
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

        /** The first variant; see {@link #variants}. */
        static List<Step> steps(ClientProfile profile, Context ctx) {
            return variants(profile, ctx).getFirst();
        }

        /**
         * Orders the client was seen to use. The cold start is not fixed: the settings requests are
         * sent by independent parts of the UI as they finish loading (recordings of 2026-11-09:
         * 18:26 with audio settings, 21:13 without them and with the key table first).
         */
        static List<List<Step>> variants(ClientProfile profile, Context ctx) {
            String view = profile.readyTypeStr();
            return ctx.firstConnect() ? cold(profile, ctx, view) : warm(profile, ctx, view);
        }

        /** Cold start (2 recordings): loading 2–9 s, then two orders of the settings requests. */
        private static List<List<Step>> cold(ClientProfile profile, Context ctx, String view) {
            List<Step> prefix = new ArrayList<>();
            prefix.add(new Step("HangarListRequest", 1_900, 9_500, HangarListRequest::new));
            prefix.add(new Step("SpaceSearchRequest", 25, 70, () -> spaceSearch(0)));
            prefix.add(new Step("SpaceSearchRequest", 0, 45, () -> spaceSearch(4)));
            prefix.add(new Step("SpaceSearchRequest", 0, 85, () -> spaceSearch(2)));
            prefix.add(new Step("LabUpdateRequest", 0, 12, LabUpdateRequest::new));
            prefix.add(new Step("ReadyRequest", 1, 55, () -> ready(view, ReadyType.MAP_LOADED)));
            prefix.add(new Step("ReadyRequest", 45, 80, () -> ready(view, ReadyType.UI_READY)));
            prefix.add(new Step("LegacyModule", 115, 220, () -> legacy("LF|w")));

            List<Step> withAudio = new ArrayList<>(prefix);
            withAudio.add(new Step("AudioSettingsRequest", 35, 60, () -> audio(ctx.settings())));
            withAudio.add(new Step("CommandCenterGetDontShowRequest", 8, 20, CommandCenterGetDontShowRequest::new));
            withAudio.add(new Step("UserKeyBindingsUpdate", 35, 60, profile::keyBindingsPacket));
            withAudio.add(new Step("GalaxyGateBuilderInfoRequest", 20, 35, GalaxyGateBuilderInfoRequest::new));
            withAudio.add(new Step("KeepAlive", 80, 800, StartupSequence.ClientLike::keepAlive));

            List<Step> keysFirst = new ArrayList<>(prefix);
            keysFirst.add(new Step("UserKeyBindingsUpdate", 0, 10, profile::keyBindingsPacket));
            keysFirst.add(new Step("CommandCenterGetDontShowRequest", 100, 200, CommandCenterGetDontShowRequest::new));
            keysFirst.add(new Step("GalaxyGateBuilderInfoRequest", 10, 35, GalaxyGateBuilderInfoRequest::new));
            keysFirst.add(new Step("KeepAlive", 80, 800, StartupSequence.ClientLike::keepAlive));
            return List.of(List.copyOf(withAudio), List.copyOf(keysFirst));
        }

        /**
         * Map change, reconnect or portal jump (11 map changes, 2 jumps): the UI already exists and
         * reports its layout. The tail comes in two orders: key table first, or keep-alive first.
         */
        private static List<List<Step>> warm(ClientProfile profile, Context ctx, String view) {
            List<Step> prefix = new ArrayList<>();
            // after a jump on the same server the client answers the new ShipInitializationCommand at once
            // (2–30 ms); otherwise up to 2.2 s after LoginResponse (which itself may come late)
            prefix.add(ctx.portalJump()
                    ? new Step("SpaceSearchRequest", 0, 60, () -> spaceSearch(0))
                    : new Step("SpaceSearchRequest", 70, 1_500, 2_200, () -> spaceSearch(0)));
            prefix.add(new Step("SpaceSearchRequest", 5, 45, 230, () -> spaceSearch(4)));
            prefix.add(new Step("SpaceSearchRequest", 0, 45, 85, () -> spaceSearch(2)));
            prefix.add(new Step("WindowSettingsRequest", 0, 65, 110, profile::windowSettingsPacket));
            prefix.add(new Step("ReadyRequest", 0, 30, 280, () -> ready(view, ReadyType.MAP_LOADED)));
            prefix.add(new Step("ReadyRequest", 3, 70, 80, () -> ready(view, ReadyType.UI_READY)));
            prefix.add(new Step("LegacyModule", 2, 40, 280, () -> legacy("LF|w")));
            prefix.add(new Step("AudioSettingsRequest", 0, 40, 240, () -> audio(ctx.settings())));
            if (ctx.portalJump()) {
                // two recorded jumps: the key table ends it, the keep-alive cycle simply goes on;
                // once the client sent a single space search and reported the layout later
                List<Step> jump = new ArrayList<>(prefix);
                jump.add(new Step("UserKeyBindingsUpdate", 10, 60, 265, profile::keyBindingsPacket));
                List<Step> single = new ArrayList<>();
                single.add(new Step("SpaceSearchRequest", 0, 30, () -> spaceSearch(0)));
                single.add(new Step("WindowSettingsRequest", 100, 200, profile::windowSettingsPacket));
                single.add(new Step("ReadyRequest", 0, 60, () -> ready(view, ReadyType.MAP_LOADED)));
                single.add(new Step("ReadyRequest", 15, 130, () -> ready(view, ReadyType.UI_READY)));
                single.add(new Step("LegacyModule", 5, 40, () -> legacy("LF|w")));
                single.add(new Step("AudioSettingsRequest", 0, 40, () -> audio(ctx.settings())));
                single.add(new Step("UserKeyBindingsUpdate", 10, 60, 265, profile::keyBindingsPacket));
                return List.of(List.copyOf(jump), List.copyOf(single));
            }
            List<Step> keysFirst = new ArrayList<>(prefix);
            keysFirst.add(new Step("UserKeyBindingsUpdate", 0, 60, 265, profile::keyBindingsPacket));
            keysFirst.add(new Step("KeepAlive", 0, 45, 480, StartupSequence.ClientLike::keepAlive));
            keysFirst.add(new Step("CommandCenterGetDontShowRequest", 0, 60, 480, CommandCenterGetDontShowRequest::new));

            List<Step> keysLast = new ArrayList<>(prefix);
            keysLast.add(new Step("KeepAlive", 0, 15, 50, StartupSequence.ClientLike::keepAlive));
            keysLast.add(new Step("CommandCenterGetDontShowRequest", 0, 20, 50, CommandCenterGetDontShowRequest::new));
            keysLast.add(new Step("UserKeyBindingsUpdate", 0, 60, 400, profile::keyBindingsPacket));
            return List.of(List.copyOf(keysFirst), List.copyOf(keysLast));
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
