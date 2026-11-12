package orbit.app.log;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/** Passes only journal lines ({@link LogRules}): the {@code logs/journal-<account>.log} files. */
public class JournalFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        boolean warn = event.getLevel().isGreaterOrEqual(Level.WARN);
        String m = event.getFormattedMessage();
        // the loot of every kill stays out of the file: the hourly summary has its sums
        return LogRules.of(event.getLoggerName(), warn, m).important() && (m == null || !m.startsWith("Добыча:"))
                ? FilterReply.NEUTRAL : FilterReply.DENY;
    }
}
