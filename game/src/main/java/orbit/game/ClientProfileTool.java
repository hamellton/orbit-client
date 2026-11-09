package orbit.game;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import orbit.net.Recording;

/**
 * Builds {@link ClientProfile} from sniffer recordings of the official client.
 *
 * <pre>
 *   client-profile [--out local/client-profile.json] [recordings dir | *.dorec ...]
 * </pre>
 * Files are read in name order (= time order); the latest packets win.
 */
public final class ClientProfileTool {

    private ClientProfileTool() {
    }

    public static void main(String[] args) throws Exception {
        Path out = Path.of("local", "client-profile.json");
        List<Path> inputs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--out")) {
                out = Path.of(args[++i]);
            } else {
                inputs.add(Path.of(args[i]));
            }
        }
        if (inputs.isEmpty()) {
            inputs.add(Path.of("recordings"));
        }
        List<Path> files = new ArrayList<>();
        for (Path in : inputs) {
            if (Files.isDirectory(in)) {
                try (Stream<Path> s = Files.list(in)) {
                    s.filter(p -> p.toString().endsWith(".dorec")).sorted().forEach(files::add);
                }
            } else {
                files.add(in);
            }
        }
        if (files.isEmpty()) {
            System.err.println("Нет записей .dorec. Снимите сессию: scripts/sniff-session.sh");
            System.exit(1);
        }
        List<Recording.PacketRecord> packets = new ArrayList<>();
        for (Path f : files) {
            packets.addAll(Recording.readPackets(f));
        }
        ClientProfile p = ClientProfile.fromRecording(packets);
        p.save(out);
        System.out.println("Профиль клиента сохранён в " + out + " (записей: " + files.size() + ")");
        System.out.println("  ReadyRequest: " + p.readyTypeStr());
        System.out.println("  раскладка окон: " + (p.windowSettings() != null ? "есть" : "нет — в записях нет смены карты"));
        System.out.println("  клавиши: " + (p.keyBindings() != null ? "есть" : "нет"));
    }
}
