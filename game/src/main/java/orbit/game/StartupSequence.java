package orbit.game;

import orbit.protocol.gen.ReadyRequest;

/**
 * Packets sent right after the server confirmed the login, mirroring what the official client
 * does once its UI is up. Runs on its own thread; may sleep between packets.
 */
public interface StartupSequence {

    void afterLogin(GameConnection connection) throws InterruptedException;

    /**
     * Minimum the client always sends: {@code ReadyRequest} type 0 and 2 (with the client's UI path
     * string) roughly a second after the login, then the first {@code KeepAlive}.
     * Richer sequences (window settings, chat auth, …) belong to the humanize layer.
     */
    static StartupSequence minimal(String readyTypeStr) {
        return c -> {
            Thread.sleep(900 + c.random().nextInt(150));
            c.send(ready(readyTypeStr, (short) 0));
            Thread.sleep(50 + c.random().nextInt(40));
            c.send(ready(readyTypeStr, (short) 2));
            Thread.sleep(80 + c.random().nextInt(60));
            c.sendKeepAlive();
        };
    }

    private static ReadyRequest ready(String str, short type) {
        ReadyRequest r = new ReadyRequest();
        r.readyTypeStr = str;
        r.readyType = type;
        return r;
    }
}
