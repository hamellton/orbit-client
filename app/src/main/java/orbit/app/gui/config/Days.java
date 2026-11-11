package orbit.app.gui.config;

import java.time.DayOfWeek;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Days of a timetable window as text: «все», «Пн-Пт», «Сб, Вс». */
final class Days {

    static final List<String> SHORT = List.of("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс");

    private Days() {
    }

    static String text(Set<DayOfWeek> days) {
        if (days.isEmpty() || days.size() == 7) {
            return "все";
        }
        EnumSet<DayOfWeek> d = EnumSet.copyOf(days);
        if (d.equals(EnumSet.range(DayOfWeek.MONDAY, DayOfWeek.FRIDAY))) {
            return "Пн-Пт";
        }
        return d.stream().map(x -> SHORT.get(x.ordinal())).collect(Collectors.joining(", "));
    }

    static Set<DayOfWeek> parse(String text) {
        String t = text.strip();
        if (t.isEmpty() || t.equalsIgnoreCase("все")) {
            return Set.of();
        }
        EnumSet<DayOfWeek> out = EnumSet.noneOf(DayOfWeek.class);
        for (String part : t.split("[,; ]+")) {
            if (part.isBlank()) {
                continue;
            }
            String[] range = part.split("-");
            int from = index(range[0]);
            int to = range.length > 1 ? index(range[1]) : from;
            for (int i = from; ; i = (i + 1) % 7) {
                out.add(DayOfWeek.of(i + 1));
                if (i == to) {
                    break;
                }
            }
        }
        return out;
    }

    private static int index(String s) {
        for (int i = 0; i < 7; i++) {
            if (SHORT.get(i).equalsIgnoreCase(s.strip())) {
                return i;
            }
        }
        throw new IllegalArgumentException("день «" + s + "»: нужно Пн…Вс, «Пн-Пт» или «все»");
    }
}
