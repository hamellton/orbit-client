package orbit.protocol;

import orbit.protocol.gen.ProtocolVersion;
import orbit.protocol.gen.VersionCommand;

/**
 * The game server rejected our protocol hash: the game was updated and the packet classes
 * must be regenerated.
 */
public class ProtocolOutdatedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public ProtocolOutdatedException(String serverVersion) {
        super("Версия протокола устарела: бот собран для сборки клиента " + ProtocolVersion.BUILD
                + " (хэш " + ProtocolVersion.VERSION_HASH + "), сервер ответил «" + serverVersion + "». "
                + "Игра обновилась — запустите регенерацию протокола: scripts/update-protocol.sh");
    }

    /** Throws if the server's answer to {@code VersionRequest} says our protocol is outdated. */
    public static void check(VersionCommand answer) {
        if (!answer.equal) {
            throw new ProtocolOutdatedException(answer.version);
        }
    }
}
