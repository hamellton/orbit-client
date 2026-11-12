package orbit.app.gui;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.UnsynchronizedAppenderBase;
import ch.qos.logback.core.encoder.Encoder;
import java.nio.charset.StandardCharsets;
import orbit.app.log.LogEntry;
import orbit.app.log.LogStore;

/**
 * Hands every log line to {@link LogStore} for the window's log (the message and the stack trace formatted by the
 * encoder, so secrets are masked by its converters). Works without a window too: the rows just stay in the store.
 * The encoder's pattern: {@code %msg%n%ex} — the first line is the message, the rest the details.
 */
public class GuiLogAppender extends UnsynchronizedAppenderBase<ILoggingEvent> {

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
        String all = new String(encoder.encode(event), StandardCharsets.UTF_8).stripTrailing();
        String message = all;
        String details = "";
        int nl = all.indexOf('\n');
        if (nl >= 0) {
            message = all.substring(0, nl).stripTrailing();
            details = all.substring(nl + 1);
        }
        Level l = event.getLevel();
        LogEntry.Level level = l.isGreaterOrEqual(Level.ERROR) ? LogEntry.Level.ERROR
                : l.isGreaterOrEqual(Level.WARN) ? LogEntry.Level.WARN : LogEntry.Level.INFO;
        String account = event.getMDCPropertyMap().get("account");
        LogStore.shared().add(account == null || account.isEmpty() ? null : account, level, event.getLoggerName(),
                message, details, event.getTimeStamp());
    }
}
