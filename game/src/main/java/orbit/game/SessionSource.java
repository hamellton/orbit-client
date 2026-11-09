package orbit.game;

import orbit.auth.AuthException;
import orbit.auth.GameSession;
import orbit.auth.MapServers;

/** Supplies website sessions and map servers to a {@link GameConnection} (usually backed by {@code AuthClient}). */
public interface SessionSource {

    /** Logs in again and returns a fresh session (called on start and when the game rejects the old one). */
    GameSession fresh() throws AuthException;

    /** Game server of the given map, e.g. after the server asked us to relog to another map. */
    MapServers.Address serverForMap(GameSession session, int mapId) throws AuthException;
}
