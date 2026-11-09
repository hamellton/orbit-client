package orbit.sniffer;

import java.io.BufferedOutputStream;
import java.io.Closeable;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Appends raw frame payloads of one game connection to a file.
 *
 * <p>Format: magic {@code "DOREC1"}, then records of
 * {@code [nanosSinceStart:i64][direction:u8 (0=client→server, 1=server→client)][len:i32][payload]}.
 * The first client→server record is the single handshake byte.
 */
final class SessionRecorder implements Closeable {

    static final byte UP = 0;
    static final byte DOWN = 1;

    private final DataOutputStream out;
    private final long start = System.nanoTime();

    SessionRecorder(Path file) throws IOException {
        Files.createDirectories(file.getParent());
        out = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)));
        out.writeBytes("DOREC1");
    }

    synchronized void record(byte direction, byte[] payload) throws IOException {
        out.writeLong(System.nanoTime() - start);
        out.writeByte(direction);
        out.writeInt(payload.length);
        out.write(payload);
    }

    synchronized void flush() throws IOException {
        out.flush();
    }

    @Override
    public synchronized void close() throws IOException {
        out.close();
    }
}
