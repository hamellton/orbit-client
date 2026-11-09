package orbit.protocolgen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

/**
 * Generates Java protocol classes from the parser JSON.
 *
 * <pre>
 *   GeneratorMain &lt;protocol dir with packets-*.json and meta-*.json&gt; &lt;output source dir&gt;
 * </pre>
 * The newest {@code meta-*.json} selects the packets file via its {@code "packets"} field.
 */
public final class GeneratorMain {

    private GeneratorMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 2) {
            System.err.println("usage: GeneratorMain <protocol dir> <output dir>");
            System.exit(2);
        }
        Path protocolDir = Path.of(args[0]);
        Path outDir = Path.of(args[1]);

        Path metaFile = newest(protocolDir, "meta-");
        ObjectMapper om = new ObjectMapper();
        JsonNode meta = om.readTree(metaFile.toFile());
        Path packetsFile = protocolDir.resolve(meta.get("packets").asText());
        byte[] packetsBytes = Files.readAllBytes(packetsFile);
        JsonNode packets = om.readTree(packetsBytes);

        ProtocolModel model = ProtocolModel.from(packets);
        String jsonSha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(packetsBytes));

        Path pkgDir = outDir.resolve(JavaEmitter.PACKAGE.replace('.', '/'));
        clean(outDir);
        Files.createDirectories(pkgDir);
        JavaEmitter emitter = new JavaEmitter(model);
        for (ProtocolModel.Module m : model.modules()) {
            write(pkgDir.resolve(m.name() + ".java"), emitter.module(m));
        }
        write(pkgDir.resolve("Packets.java"), emitter.registry());
        write(pkgDir.resolve("ProtocolVersion.java"), emitter.version(meta, packetsFile.getFileName().toString(), jsonSha));
        System.out.printf("Сгенерировано %d классов пакетов из %s (сборка %s)%n",
                model.modules().size(), packetsFile.getFileName(), meta.path("build").asText());
    }

    private static Path newest(Path dir, String prefix) throws IOException {
        try (Stream<Path> s = Files.list(dir)) {
            return s.filter(p -> p.getFileName().toString().startsWith(prefix) && p.toString().endsWith(".json"))
                    .max(Comparator.comparing(p -> p.getFileName().toString()))
                    .orElseThrow(() -> new IOException("no " + prefix + "*.json in " + dir));
        }
    }

    private static void clean(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            return;
        }
        try (Stream<Path> s = Files.walk(dir)) {
            for (Path p : s.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(p);
            }
        }
    }

    private static void write(Path file, String content) throws IOException {
        Files.writeString(file, content, StandardCharsets.UTF_8);
    }
}
