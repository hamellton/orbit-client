package orbit.net;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Random;
import java.util.zip.CRC32;
import org.junit.jupiter.api.Test;

class DownstreamTest {

    @Test
    void lz4DecodesRepetitiveAndRandomData() {
        Random rnd = new Random(42);
        for (int size : new int[] {0, 1, 5, 13, 100, 4096, 70_000}) {
            byte[] data = new byte[size];
            for (int i = 0; i < size; i++) {
                // mix of runs (overlapping matches) and noise (long literals)
                data[i] = (byte) (i % 97 < 60 ? i % 7 : rnd.nextInt());
            }
            byte[] packed = TestLz4Encoder.compress(data);
            assertArrayEquals(data, Lz4Block.decompress(packed, 0, packed.length, size), "size " + size);
        }
    }

    @Test
    void lz4HandlesOverlappingRunLongerThanOffset() {
        // token 0x2F: 2 literals "ab", match nibble 15 + ext byte 1 + 4 = 20 bytes at offset 2,
        // then a final sequence of 5 literals

        byte[] block = {(byte) 0x2F, 'a', 'b', 2, 0, 1, (byte) 0x50, 'x', 'y', 'z', 'w', 'v'};
        byte[] out = Lz4Block.decompress(block, 0, block.length, 2 + 20 + 5);
        assertEquals("ababababababababababab" + "xyzwv", new String(out));
    }

    @Test
    void lz4RejectsBadOffset() {
        byte[] block = {(byte) 0x10, 'a', 5, 0};
        assertThrows(ProtocolException.class, () -> Lz4Block.decompress(block, 0, block.length, 10));
    }

    @Test
    void plainPayloadIsSinglePacket() {
        byte[] payload = {0x02, (byte) 0x9A, 1};
        List<byte[]> packets = Downstream.split(payload, false);
        assertEquals(1, packets.size());
        assertEquals((short) 0x029A, Downstream.packetId(packets.get(0)));
    }

    @Test
    void blockEnvelopeSplitsIntoPackets() {
        byte[] p1 = {0x00, 0x07, 1, 2, 3};
        byte[] p2 = {(byte) 0x9C, 0x0F};
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        for (byte[] p : new byte[][] {p1, p2}) {
            raw.write(0);
            raw.write(0);
            raw.write(p.length);
            raw.writeBytes(p);
        }
        byte[] payload = envelope(Downstream.MAGIC_BLOCK, raw.toByteArray());

        List<byte[]> packets = Downstream.split(payload, true);
        assertEquals(2, packets.size());
        assertArrayEquals(p1, packets.get(0));
        assertArrayEquals(p2, packets.get(1));
        assertEquals((short) 0x9C0F, Downstream.packetId(packets.get(1)));
    }

    @Test
    void legacyEnvelopeIsOnePacket() {
        byte[] packet = "\u0000\u0001hello hello hello hello".getBytes();
        List<byte[]> packets = Downstream.split(envelope(Downstream.MAGIC_LEGACY, packet), true);
        assertEquals(1, packets.size());
        assertArrayEquals(packet, packets.get(0));
    }

    @Test
    void envelopeRejectedWhenCompressionNotOffered() {
        byte[] payload = envelope(Downstream.MAGIC_LEGACY, new byte[] {0, 1, 2});
        assertThrows(ProtocolException.class, () -> Downstream.split(payload, false));
    }

    @Test
    void envelopeRejectsCrcMismatch() {
        byte[] payload = envelope(Downstream.MAGIC_LEGACY, new byte[] {0, 1, 2, 3});
        payload[13] ^= 1;
        assertThrows(ProtocolException.class, () -> Downstream.split(payload, true));
    }

    @Test
    void framesRoundTrip() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] big = new byte[70_000];
        Frames.write(out, new byte[] {1, 2});
        Frames.write(out, big);
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        assertArrayEquals(new byte[] {1, 2}, Frames.read(in));
        assertArrayEquals(big, Frames.read(in));
        assertEquals(null, Frames.read(in));
    }

    @Test
    void framesDetectTruncation() {
        ByteArrayInputStream in = new ByteArrayInputStream(new byte[] {0, 0, 5, 1, 2});
        assertThrows(EOFException.class, () -> Frames.read(in));
    }

    private static byte[] envelope(int magic, byte[] raw) {
        byte[] packed = TestLz4Encoder.compress(raw);
        CRC32 crc = new CRC32();
        crc.update(raw);
        return ByteBuffer.allocate(Downstream.ENVELOPE_HEADER + packed.length)
                .put((byte) 0xFF).put((byte) 0xFF)
                .putInt(magic).putInt(raw.length).putInt((int) crc.getValue())
                .put(packed).array();
    }
}
