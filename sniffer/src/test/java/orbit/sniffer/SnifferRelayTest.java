package orbit.sniffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import orbit.net.Frames;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SnifferRelayTest {

    private static final String SESSION = "abcdef0123456789";

    @TempDir
    Path tmp;

    @Test
    void relaysGameTrafficUnchangedAndRecordsMaskedSession() throws Exception {
        Path json = tmp.resolve("packets.json");
        Files.writeString(json, "[{\"id\":7,\"name\":\"LoginRequest\"},{\"id\":667,\"name\":\"VersionCommand\"}]");
        PacketNames names = PacketNames.load(json);

        byte[] login = loginRequest(SESSION, "do-lz4-block-v3");
        byte[] reply = {0x02, (byte) 0x9B, 1}; // VersionCommand-like payload

        try (ServerSocket game = new ServerSocket(0, 50, InetAddress.getLoopbackAddress())) {
            CompletableFuture<byte[][]> serverSide = CompletableFuture.supplyAsync(() -> {
                try (Socket s = game.accept()) {
                    InputStream in = s.getInputStream();
                    int hello = in.read();
                    byte[] got = Frames.read(in);
                    Frames.write(s.getOutputStream(), reply);
                    return new byte[][] {{(byte) hello}, got};
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            });

            Socks5Server socks = new Socks5Server(0, names, tmp.resolve("rec"), t -> true);
            try (ServerSocket ss = socks.bind()) {
                Thread.ofVirtual().start(() -> {
                    try {
                        socks.serve(ss);
                    } catch (Exception ignored) {
                        // closed at the end of the test
                    }
                });

                try (Socket c = new Socket(InetAddress.getLoopbackAddress(), ss.getLocalPort())) {
                    c.setSoTimeout(5000);
                    DataOutputStream out = new DataOutputStream(c.getOutputStream());
                    DataInputStream in = new DataInputStream(c.getInputStream());
                    socksConnect(out, in, "127.0.0.1", game.getLocalPort());

                    out.write(0);
                    Frames.write(out, login);
                    assertArrayEquals(reply, Frames.read(in), "server -> client bytes must pass unchanged");
                }

                byte[][] seen = serverSide.get(5, TimeUnit.SECONDS);
                assertEquals(0, seen[0][0], "handshake byte");
                assertArrayEquals(login, seen[1], "client -> server bytes must pass unchanged");
            }
        }

        List<byte[]> records = waitForRecords(tmp.resolve("rec"), 3);
        assertEquals(1, records.get(0).length);
        byte[] recordedLogin = records.get(1);
        assertEquals(login.length, recordedLogin.length, "masking keeps the length");
        String asText = new String(recordedLogin, StandardCharsets.ISO_8859_1);
        assertFalse(asText.contains(SESSION), "session id must not be recorded");
        assertTrue(asText.contains("x".repeat(SESSION.length())));
        assertArrayEquals(reply, records.get(2));
    }

    @Test
    void gameTargetPatternMatchesOnlyGamePorts() {
        assertTrue(SnifferMain.GAME_TARGET.matcher("gameserver-22-01-public.prod.darkorbit.com:6003").matches());
        assertTrue(SnifferMain.GAME_TARGET.matcher("arenaserver-22-02-public.prod.darkorbit.com:6002").matches());
        assertFalse(SnifferMain.GAME_TARGET.matcher("www.darkorbit.com:443").matches());
        assertFalse(SnifferMain.GAME_TARGET.matcher("darkorbit.com.evil.net:6003").matches());
    }

    private static byte[] loginRequest(String session, String version) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeShort(7);
        d.writeInt(123 << 3);
        d.writeShort(1);
        d.writeUTF(session);
        d.writeUTF(version);
        d.writeInt(0);
        d.writeBoolean(true);
        return b.toByteArray();
    }

    private static void socksConnect(DataOutputStream out, DataInputStream in, String ip, int port) throws Exception {
        out.write(new byte[] {5, 1, 0});
        assertEquals(5, in.readUnsignedByte());
        assertEquals(0, in.readUnsignedByte());
        byte[] host = ip.getBytes(StandardCharsets.US_ASCII);
        out.write(new byte[] {5, 1, 0, 3, (byte) host.length});
        out.write(host);
        out.writeShort(port);
        byte[] rep = in.readNBytes(10);
        assertEquals(0, rep[1], "SOCKS reply status");
    }

    /** Reads DOREC1 records once the relay has flushed and closed the file. */
    private static List<byte[]> waitForRecords(Path dir, int expected) throws Exception {
        long deadline = System.currentTimeMillis() + 5000;
        while (true) {
            List<byte[]> list = new ArrayList<>();
            if (Files.isDirectory(dir)) {
                try (var files = Files.list(dir)) {
                    for (Path f : files.toList()) {
                        try (DataInputStream in = new DataInputStream(Files.newInputStream(f))) {
                            if (new String(in.readNBytes(6), StandardCharsets.US_ASCII).equals("DOREC1")) {
                                while (in.available() > 0) {
                                    in.readLong();
                                    in.readByte();
                                    list.add(in.readNBytes(in.readInt()));
                                }
                            }
                        } catch (java.io.EOFException partial) {
                            // file still being written
                        }
                    }
                }
            }
            if (list.size() >= expected || System.currentTimeMillis() > deadline) {
                return list;
            }
            Thread.sleep(50);
        }
    }
}
