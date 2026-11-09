package orbit.auth;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Extracts the {@code "key": "value"} pairs the game page passes to the Flash preloader. */
final class FlashVars {

    private static final Pattern PAIR = Pattern.compile("\"([A-Za-z_]\\w*)\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"");
    private static final Pattern UNICODE = Pattern.compile("\\\\u([0-9a-fA-F]{4})");

    private FlashVars() {
    }

    /** First occurrence of each key wins; JS escapes ({@code \/}, {@code А}) are undone. */
    static Map<String, String> parse(String html) {
        Map<String, String> out = new LinkedHashMap<>();
        Matcher m = PAIR.matcher(html);
        while (m.find()) {
            out.putIfAbsent(m.group(1), unescape(m.group(2)));
        }
        return out;
    }

    static String unescape(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        Matcher u = UNICODE.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (u.find()) {
            u.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(u.group(1), 16))));
        }
        u.appendTail(sb);
        return sb.toString().replace("\\/", "/").replace("\\\"", "\"").replace("\\\\", "\\");
    }
}
