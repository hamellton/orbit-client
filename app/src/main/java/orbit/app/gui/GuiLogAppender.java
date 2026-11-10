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
    /** A log line and the account it was logged for ({@code null} = none). */
    record Line(String account, String text) {
    }

    private static final ArrayDeque<Line> LINES = new ArrayDeque<>();
    private static final List<Consumer<Line>> LISTENERS = new CopyOnWriteArrayList<>();

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
        Line line = new Line(event.getMDCPropertyMap().get("account"), new String(encoder.encode(event), StandardCharsets.UTF_8));
        synchronized (LINES) {
            LINES.addLast(line);
            while (LINES.size() > MAX_LINES) {
                LINES.removeFirst();
            }
        }
        for (Consumer<Line> l : LISTENERS) {
            l.accept(line);
        }
    }

    /**
     * Lines logged so far and every new one; returns the backlog.
     *
     * @param account only this account's lines and those of no account; {@code null} = all
     */
    static List<String> subscribe(String account, Consumer<String> listener) {
        java.util.function.Predicate<Line> mine = l -> account == null || l.account() == null || account.equals(l.account());
        synchronized (LINES) {
            LISTENERS.add(l -> {
                if (mine.test(l)) {
                    listener.accept(l.text());
                }
            });
            return LINES.stream().filter(mine).map(Line::text).toList();
        }
    }
}
