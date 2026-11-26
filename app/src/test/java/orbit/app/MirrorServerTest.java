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

    private static Packet read(InputStream in) throws Exception {
        byte[] f = Frames.read(in);
        return PacketCodec.decode(f);
    }
}
