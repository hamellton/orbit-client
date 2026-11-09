package orbit.game;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import orbit.net.Recording.PacketRecord;
import orbit.protocol.Packet;
import orbit.protocol.PacketCodec;
import orbit.protocol.gen.ReadyRequest;
import orbit.protocol.gen.UserKeyBindingsUpdate;
import orbit.protocol.gen.WindowSettingsRequest;

/**
 * What the official client of one account sends about its own UI: the {@code ReadyRequest}
 * view string, the panel layout ({@code WindowSettingsRequest}) and the key bindings it reports
 * at startup ({@code UserKeyBindingsUpdate}). These come from the client's local state, not from
 * the server, so they are taken from a recording of that account and replayed as is.
 *
 * <p>Stored as JSON with the packets base64-encoded, so the exact bytes survive protocol
 * regeneration as long as the packet layout does not change.
 *
 * @param readyTypeStr   view description sent in {@code ReadyRequest}
 * @param windowSettings encoded {@code WindowSettingsRequest}, or {@code null}
 * @param keyBindings    encoded {@code UserKeyBindingsUpdate}, or {@code null}
 */
public record ClientProfile(String readyTypeStr, String windowSettings, String keyBindings) {

    /** Observed with the official client on macOS (2D renderer, 1920×1022 window). */
    public static final String DEFAULT_READY_TYPE_STR =
            "2D 1920x1022 .root1.instance469.MainClientApplication0.ApplicationSkin2.Group3.Group4.Main5.instance65124";

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Profile without UI state: only the packets the bot can build itself are sent. */
    public static ClientProfile minimal() {
        return new ClientProfile(DEFAULT_READY_TYPE_STR, null, null);
    }

    /** Takes the last {@code ReadyRequest}, {@code WindowSettingsRequest} and {@code UserKeyBindingsUpdate} sent by the client. */
    public static ClientProfile fromRecording(List<PacketRecord> packets) {
        String ready = null;
        String window = null;
        String keys = null;
        for (PacketRecord r : packets) {
            if (!r.up()) {
                continue;
            }
            Packet p;
            try {
                p = PacketCodec.decode(r.bytes());
            } catch (RuntimeException e) {
                continue;
            }
            if (p instanceof ReadyRequest rr && rr.readyTypeStr != null && !rr.readyTypeStr.isEmpty()) {
                ready = rr.readyTypeStr;
            } else if (p instanceof WindowSettingsRequest) {
                window = Base64.getEncoder().encodeToString(r.bytes());
            } else if (p instanceof UserKeyBindingsUpdate u && u.changedKeyBindings.size() > 10) {
                // small updates are single key changes from the settings window, not the full table
                keys = Base64.getEncoder().encodeToString(r.bytes());
            }
        }
        return new ClientProfile(ready != null ? ready : DEFAULT_READY_TYPE_STR, window, keys);
    }

    public static ClientProfile load(Path file) throws IOException {
        ClientProfile p = JSON.readValue(file.toFile(), ClientProfile.class);
        p.windowSettingsPacket();
        p.keyBindingsPacket();
        return p;
    }

    public void save(Path file) throws IOException {
        Path dir = file.toAbsolutePath().getParent();
        Files.createDirectories(dir);
        JSON.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), this);
    }

    /** Fresh copy of the recorded layout packet, or {@code null}. */
    public WindowSettingsRequest windowSettingsPacket() {
        return decode(windowSettings, WindowSettingsRequest.class);
    }

    /** Fresh copy of the recorded key bindings packet, or {@code null}. */
    public UserKeyBindingsUpdate keyBindingsPacket() {
        return decode(keyBindings, UserKeyBindingsUpdate.class);
    }

    private static <T extends Packet> T decode(String base64, Class<T> type) {
        if (base64 == null) {
            return null;
        }
        Packet p = PacketCodec.decode(Base64.getDecoder().decode(base64));
        if (!type.isInstance(p)) {
            throw new IllegalArgumentException("в профиле клиента ожидался " + type.getSimpleName()
                    + ", а записан " + p.getClass().getSimpleName());
        }
        return type.cast(p);
    }
}
