package orbit.app.gui;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Keeps the last log lines (already formatted, secrets masked by the pattern's converters) for the
 * log panel. Works without a window too: the lines just stay in the buffer.
 */
public class GuiLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

    static final int MAX_LINES = 2_000;
    private static final ArrayDeque<String> LINES = new ArrayDeque<>();
    private static final List<Consumer<String>> LISTENERS = new CopyOnWriteArrayList<>();

    private Encoder<ILoggingEvent> encoder;

    public void setEncoder(Encoder<ILoggingEvent> encoder) {
        this.encoder = encoder;
    }

    @Override
    public void start() {
        if (encoder == null) {
            addError("encoder is not set");
            return;
        }
        super.start();
    }

    @Override
    protected void append(ILoggingEvent event) {
        String line = new String(encoder.encode(event), StandardCharsets.UTF_8);
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        for (Consumer<String> l : LISTENERS) {
            l.accept(line);
        }
    }

    /** Lines logged so far and every new one; returns the backlog. */
    static List<String> subscribe(Consumer<String> listener) {
        synchronized (LINES) {
            LISTENERS.add(listener);
            return List.copyOf(LINES);
        }
    }
}
