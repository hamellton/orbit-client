package orbit.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RecordingTest {

    @Test
    void partialLastRecordIsLeftOut(@TempDir Path dir) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.write(Recording.MAGIC.getBytes(StandardCharsets.US_ASCII));
        out.writeLong(1);
        out.writeByte(Recording.UP);
        out.writeInt(1);
        out.writeByte(0);
        out.writeLong(2);
        out.writeByte(Recording.DOWN);
        out.writeInt(4);
        out.write(new byte[] {1, 2, 3, 4});
        byte[] full = bytes.toByteArray();

        for (int cut = full.length - 1; cut > full.length - 17; cut--) {
            Path f = dir.resolve("cut" + cut + ".dorec");
            Files.write(f, Arrays.copyOf(full, cut));
            assertEquals(1, Recording.readFrames(f).size(), "cut at " + cut);
        }
        Path whole = dir.resolve("whole.dorec");
        Files.write(whole, full);
        assertEquals(2, Recording.readFrames(whole).size());
    }
}
