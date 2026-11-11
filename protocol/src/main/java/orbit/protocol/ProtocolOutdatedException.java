package orbit.protocol;

import orbit.protocol.gen.ProtocolVersion;
import orbit.protocol.gen.VersionCommand;

/**
 * The game server rejected our protocol hash: the game was updated and the packet classes
 * must be regenerated.
 */
public class ProtocolOutdatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String serverVersion;

    public ProtocolOutdatedException(String serverVersion) {
        super("Версия протокола устарела: запустите update-protocol.sh");
        this.serverVersion = serverVersion;
    }

    /** For the log: which client build the bot was made for and what the server answered. */
    public String details() {
        return "бот собран для сборки клиента " + ProtocolVersion.BUILD + " (хэш " + ProtocolVersion.VERSION_HASH
                + "), сервер ответил «" + serverVersion + "»; scripts/update-protocol.sh";
    }

    /** Throws if the server's answer to {@code VersionRequest} says our protocol is outdated. */
    public static void check(VersionCommand answer) {
        if (!answer.equal) {
            throw new ProtocolOutdatedException(answer.version);
        }
    }
}
