package orbit.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import orbit.bot.run.Stats;
import orbit.world.WorldSnapshot;

/** The session's results as text: rewards (total and per hour), what the bot did, loot by item. */
public final class SessionSummary {

    private SessionSummary() {
    }

    /** Lines for the window's card; {@code hours} = session length. */
    public static List<String> lines(WorldSnapshot.Rewards r, Stats s, double hours) {
        List<String> out = new ArrayList<>();
        out.add(row("Уридий", r.uridium(), hours));
        out.add(row("Кредиты", r.credits(), hours));
        out.add(row("Опыт", r.experience(), hours));
        out.add(row("Честь", r.honor(), hours));
        if (s != null) {
            out.add("Кубы: бои " + s.cubesFought() + ", добито " + s.cubesKilled.get());
            out.add("Убито " + s.kills.get() + "   ящиков " + s.boxes.get());
            out.add("Ремонтов " + s.repairs.get() + "   смертей " + s.deaths.get());
        }
        for (Map.Entry<String, Double> e : r.items().entrySet()) {
            out.add(itemName(e.getKey()) + ": " + num(e.getValue()));
        }
        return out;
    }

    /** One line for the log at the end of a session. */
    public static String text(WorldSnapshot.Rewards r, Stats s, double hours) {
        return String.join("; ", lines(r, s, hours));
    }

    private static String row(String name, double v, double hours) {
        String perHour = hours >= 0.05 ? "  (" + num(v / hours) + "/ч)" : "";
        return name + ": " + num(v) + perHour;
    }

    static String num(double v) {
        return String.format("%,.0f", v).replace(' ', ' ');
    }

    /** {@code resource_collectable_luminium} → Luminium, {@code ammunition_laser_ucb-100} → UCB-100. */
    static String itemName(String id) {
        return switch (id) {
            case "xenomit" -> "Ксеномит";
            case "log_disk" -> "Лог-диски";
            default -> {
                for (String p : new String[] {"resource_collectable_", "resource_"}) {
                    if (id.startsWith(p)) {
                        String n = id.substring(p.length()).replace('_', ' ');
                        yield Character.toUpperCase(n.charAt(0)) + n.substring(1);
                    }
                }
                for (String p : new String[] {"ammunition_laser_", "ammunition_rocket_", "ammunition_mine_",
                        "ammunition_specialammo_", "ammunition_rocketlauncher_"}) {
                    if (id.startsWith(p)) {
                        yield id.substring(p.length()).toUpperCase();
                    }
                }
                yield id;
            }
        };
    }
}
