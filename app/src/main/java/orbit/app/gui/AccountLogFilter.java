package orbit.app.gui;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.filter.Filter;
import ch.qos.logback.core.spi.FilterReply;

/** Passes only lines logged for an account (MDC {@code account}), for the per-account log files. */
public class AccountLogFilter extends Filter<ILoggingEvent> {

    @Override
    public FilterReply decide(ILoggingEvent event) {
        String a = event.getMDCPropertyMap().get("account");
        return a == null || a.isEmpty() ? FilterReply.DENY : FilterReply.NEUTRAL;
    }
}
