package orbit.secrets;

import ch.qos.logback.classic.pattern.ThrowableProxyConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback {@code %ex} replacement that hides registered secrets in exception messages (an
 * {@code IOException} can quote a URL with a token). Use in {@code logback.xml}:
 *
 * <pre>
 *   &lt;conversionRule conversionWord="ex" class="orbit.secrets.MaskingThrowableConverter"/&gt;
 * </pre>
 * and end the pattern with {@code %ex} so logback does not append its own unmasked trace.
 */
public class MaskingThrowableConverter extends ThrowableProxyConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return SecretMask.mask(super.convert(event));
    }
}
