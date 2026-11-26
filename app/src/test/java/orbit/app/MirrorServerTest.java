package orbit.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import orbit.net.Frames;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.LoginRequest;
import orbit.protocol.gen.MoveCommand;
import orbit.protocol.gen.MoveRequest;
import orbit.protocol.gen.ShipInitializationCommand;
import orbit.protocol.gen.VersionCommand;
import orbit.protocol.gen.VersionRequest;
import org.junit.jupiter.api.Test;

class MirrorServerTest {

    @Test
    void theClientGetsTheBotsLoginThenTheLiveStreamAndItsOwnPacketsGoNowhere() throws Exception {
        MirrorCapture cap = new MirrorCapture();
        VersionCommand v = new VersionCommand();
        cap.incoming(v);
        cap.incoming(new orbit.protocol.gen.UserSettingsCommand());
        ShipInitializationCommand init = new ShipInitializationCommand();
        init.userId = 7;
        cap.incoming(init);
        MoveCommand old = new MoveCommand();
        old.userId = 99;
        old.x = 1;
        cap.incoming(old);
        MoveCommand last = new MoveCommand();
        last.userId = 99;
        last.x = 2;
        cap.incoming(last);
        cap.incoming(new orbit.protocol.gen.AttackLaserRunCommand()); // passing: live only
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        try (MirrorServer m = new MirrorServer(cap, () -> null, List.of(port));
             Socket s = new Socket(InetAddress.getLoopbackAddress(), port)) {
            OutputStream out = s.getOutputStream();
            InputStream in = new BufferedInputStream(s.getInputStream());
            out.write(0);
            Frames.write(out, PacketCodec.encode(new VersionRequest()));
            out.flush();
            assertTrue(read(in) instanceof VersionCommand);
            Frames.write(out, PacketCodec.encode(new LoginRequest()));
            out.flush();
            List<String> got = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                got.add(read(in).getClass().getSimpleName());
            }
            // the head, the map, the ship's last move only, then the hero's place (no world here: an empty move)
            assertEquals(List.of("UserSettingsCommand", "ShipInitializationCommand", "MoveCommand", "MoveCommand"), got);
            // the client's clicks are dropped; the bot's live packets reach it
            MoveRequest click = new MoveRequest();
            Frames.write(out, PacketCodec.encode(click));
            out.flush();
            Thread.sleep(200);
            MoveCommand live = new MoveCommand();
            live.userId = 5;
            live.x = 333;
            cap.incoming(live);
            Packet p = read(in);
            assertTrue(p instanceof MoveCommand mc && mc.userId == 5 && mc.x == 333, String.valueOf(p));
        }
    }

    @Test
    void theReplayIsTheMapAsItIsNowNotItsHistory() {
        MirrorCapture cap = new MirrorCapture();
        cap.incoming(new VersionCommand());
        cap.incoming(new ShipInitializationCommand());
        orbit.protocol.gen.ShipCreateCommand gone = new orbit.protocol.gen.ShipCreateCommand();
        gone.userId = 1;
        cap.incoming(gone);
        orbit.protocol.gen.ShipCreateCommand here = new orbit.protocol.gen.ShipCreateCommand();
        here.userId = 2;
        cap.incoming(here);
        orbit.protocol.gen.ShipRemoveCommand remove = new orbit.protocol.gen.ShipRemoveCommand();
        remove.userId = 1;
        cap.incoming(remove);
        orbit.protocol.gen.AddBoxCommand box = new orbit.protocol.gen.AddBoxCommand();
        box.hash = "b1";
        cap.incoming(box);
        orbit.protocol.gen.RemoveCollectableCommand picked = new orbit.protocol.gen.RemoveCollectableCommand();
        picked.hash = "b1";
        cap.incoming(picked);
        for (int i = 0; i < 50; i++) {
            orbit.protocol.gen.BeaconCommand b = new orbit.protocol.gen.BeaconCommand();
            b.positionX = i;
            cap.incoming(b);
            orbit.protocol.gen.MenuItemStatusCommand st = new orbit.protocol.gen.MenuItemStatusCommand();
            st.menuItemId = "ammunition_laser_lcb-10";
            st.counterValue = i;
            cap.incoming(st);
        }
        orbit.protocol.gen.LegacyModule drones = new orbit.protocol.gen.LegacyModule();
        drones.message = "0|n|d|2|19|1|3";
        cap.incoming(drones);
        orbit.protocol.gen.LegacyModule loot = new orbit.protocol.gen.LegacyModule();
        loot.message = "0|LM|ST|BAT|ammunition_laser_lcb-10|5";
        cap.incoming(loot);
        List<Packet> r = cap.replay();
        assertEquals(List.of("ShipInitializationCommand", "ShipCreateCommand", "MenuItemStatusCommand", "LegacyModule"),
                r.stream().map(p -> p.getClass().getSimpleName()).toList());
        assertEquals(2, ((orbit.protocol.gen.ShipCreateCommand) r.get(1)).userId, "the ship still there");
        assertEquals(49, ((orbit.protocol.gen.MenuItemStatusCommand) r.get(2)).counterValue, "the item's last state");
        assertEquals(49, cap.beacon().positionX, "the last beacon, kept apart for the hero's fresh one");
        assertEquals("0|n|d|2|19|1|3", ((orbit.protocol.gen.LegacyModule) r.get(3)).message, "the ship's drones, no loot line");
    }

    private static Packet read(InputStream in) throws Exception {
        byte[] f = Frames.read(in);
        return PacketCodec.decode(f);
    }
}
