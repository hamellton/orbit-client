package orbit.auth;

/**
 * Everything needed to open the game connection, taken from the game page and {@code maps.php}.
 *
 * @param host        account server origin, e.g. {@code https://int1.darkorbit.com}
 * @param dosid       website session cookie (secret)
 * @param userId      account id
 * @param sessionId   game session id for {@code LoginRequest} (secret)
 * @param instanceId  {@code pid} flashvar, sent as {@code LoginRequest.instanceId}
 * @param mapId       map the ship is currently on
 * @param gameHost    game server host for that map
 * @param gamePort    game server port for that map
 * @param platform    {@code platform} flashvar; {@code PC_CLIENT} means {@code isMiniClient=true}
 */
public record GameSession(String host, String dosid, int userId, String sessionId, int instanceId,
                          int mapId, String gameHost, int gamePort, String platform) {

    /** Mirrors the client: the downloadable client logs in with {@code isMiniClient = true}. */
    public boolean isMiniClient() {
        return "PC_CLIENT".equals(platform);
    }

    /** Never print secrets. */
    @Override
    public String toString() {
        return "GameSession[host=" + host + ", userId=" + userId + ", instanceId=" + instanceId + ", mapId=" + mapId
                + ", server=" + gameHost + ":" + gamePort + ", platform=" + platform + ", dosid=***, sessionId=***]";
    }
}
