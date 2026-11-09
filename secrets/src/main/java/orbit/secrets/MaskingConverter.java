package orbit.secrets;

import ch.qos.logback.classic.pattern.MessageConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback {@code %msg} replacement that hides registered secrets. Use in {@code logback.xml}:
 *
 * <pre>
 *   &lt;conversionRule conversionWord="msg" converterClass="orbit.secrets.MaskingConverter"/&gt;
 * </pre>
 */
public class MaskingConverter extends MessageConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretMask.mask(super.convert(event));
    }
}
