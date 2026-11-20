package orbit.app.log;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The log lines of this run for the window, repeats folded: a line that comes again with other numbers (a palladium
 * pickup with its session count) within {@link #MERGE_MS} of its last time grows its row's count instead of adding a
 * row (a row spans at most {@link #SPAN_MS}, so an hour shows its own rows). Journal lines fold only when worded
 * exactly the same. 40 hours of palladium (60 000 lines) become a few thousand rows.
 */
public final class LogStore {

    static final int MAX_ROWS = 100_000;
    static final int LOOK_BACK = 40;
    static final long MERGE_MS = 20 * 60_000L;
    static final long SPAN_MS = 60 * 60_000L;
    private static final Pattern NUMBER = Pattern.compile("\\d+([.,\\u00a0 ]\\d{3})*([.,]\\d+)?");
    private static final LogStore SHARED = new LogStore();

    private final ArrayDeque<LogEntry> rows = new ArrayDeque<>();
    private final Map<String, ArrayDeque<LogEntry>> recent = new HashMap<>();
    private final java.util.LinkedHashSet<String> accounts = new java.util.LinkedHashSet<>();
    private final List<Consumer<LogEntry>> listeners = new CopyOnWriteArrayList<>();
    private long seq;
    private final Friendly friendly;
    /** Each account's last death row of the journal: its repair is added to it. */
    private final Map<String, LogEntry> lastDeath = new HashMap<>();
    /** The amounts of the rows that add up (box loot, spending), by row. */
    private final Map<Long, String> sums = new HashMap<>();

    public LogStore() {
        this(new Friendly(LogStore::accountShip));
    }

    LogStore(Friendly friendly) {
        this.friendly = friendly;
    }

    private static volatile long shipsReadAt;
    private static volatile orbit.config.AccountSettings ships;

    /** A hangar's ship name from the accounts' settings (read again at most once a minute). */
    static String accountShip(String account, Integer hangar) {
        long now = System.currentTimeMillis();
        if (ships == null || now - shipsReadAt > 60_000) {
            shipsReadAt = now;
            try {
                ships = orbit.config.AccountSettings.load(orbit.config.AccountSettings.DEFAULT_FILE);
            } catch (java.io.IOException | RuntimeException e) {
                return null;
            }
        }
        if (account == null || ships == null) {
            return null;
        }
        for (orbit.config.AccountSettings.Ship sh : ships.account(account).hangars()) {
            if (sh.id() == hangar) {
                return sh.label();
            }
        }
        return null;
    }

    public static LogStore shared() {
        return SHARED;
    }

    /** Adds a line; returns its row (a new one, or the repeated one it was folded into). */
    public LogEntry add(String account, LogEntry.Level level, String logger, String message, String details, long time) {
        LogRules.Kind kind = LogRules.of(logger, level != LogEntry.Level.INFO, message);
        String simple = LogRules.simpleName(logger);
        boolean fold = kind.category() != LogCategory.SUMMARY && (details == null || details.isEmpty());
        boolean loot = message.startsWith(LOOT);
        // the loot of each kill adds up in one row (any amounts); other journal lines fold only worded the same
        String key = level + "|" + simple + "|" + (loot ? LOOT
                : kind.important() ? message : NUMBER.matcher(message).replaceAll("#"));
        LogEntry row = null;
        synchronized (rows) {
            if (account != null) {
                accounts.add(account);
            }
            ArrayDeque<LogEntry> mine = recent.computeIfAbsent(account == null ? "" : account, k -> new ArrayDeque<>());
            if (fold) {
                for (Iterator<LogEntry> it = mine.descendingIterator(); it.hasNext(); ) {
                    LogEntry e = it.next();
                    if (e.key().equals(key) && time - e.last() <= MERGE_MS && time - e.first() <= SPAN_MS) {
                        e.repeat(loot ? sumLoot(e.text(), message) : message, message, time);
                        row = e;
                        break;
                    }
                }
            }
            if (row == null) {
                row = new LogEntry(++seq, account, level, simple, kind.category(), kind.important(), key, message,
                        details == null ? "" : details, time);
                rows.addLast(row);
                while (rows.size() > MAX_ROWS) {
                    rows.removeFirst();
                }
                mine.addLast(row);
                while (mine.size() > LOOK_BACK) {
                    mine.removeFirst();
                }
            }
        }
        for (Consumer<LogEntry> l : listeners) {
            l.accept(row);
        }
        journal(account, level, simple, kind.category(), message, time);
        return row;
    }

    /** The plain-words journal's row(s) for the line ({@link Friendly}). */
    private void journal(String account, LogEntry.Level level, String simple, LogCategory category, String message,
                         long time) {
        Friendly.Out out = friendly.of(account, level != LogEntry.Level.INFO, simple, message, time);
        LogEntry changed = null;
        String who = account == null ? "" : account;
        synchronized (rows) {
            if (out.amend() != null) {
                LogEntry death = lastDeath.remove(who);
                if (death != null && time - death.last() < 10 * 60_000L) {
                    death.amend(out.amend(), time);
                    changed = death;
                }
            } else if (out.add() != null) {
                Friendly.Line l = out.add();
                LogEntry.Level lv = l.problem() ? (level == LogEntry.Level.ERROR ? level : LogEntry.Level.WARN)
                        : LogEntry.Level.INFO;
                String key = l.replace() != null ? "R|" + l.replace() : l.sum() != null ? "S|" + l.sum() : "J|" + l.text();
                ArrayDeque<LogEntry> mine = recent.computeIfAbsent("J|" + who, k -> new ArrayDeque<>());
                if (l.replace() != null) {
                    for (Iterator<LogEntry> it = mine.descendingIterator(); it.hasNext(); ) {
                        LogEntry e = it.next();
                        if (e.key().equals(key) && time - e.last() <= MERGE_MS) {
                            e.replace(l.text(), time, l.live());
                            changed = e;
                            break;
                        }
                    }
                } else if (l.sum() != null) {
                    for (Iterator<LogEntry> it = mine.descendingIterator(); it.hasNext(); ) {
                        LogEntry e = it.next();
                        if (e.key().equals(key) && time - e.last() <= MERGE_MS && time - e.first() <= SPAN_MS) {
                            String body = Friendly.add(sums.getOrDefault(e.seq(), ""), l.body());
                            sums.put(e.seq(), body);
                            String shown = l.head() + Friendly.loot(body);
                            e.repeat(l.sum().equals("spent") ? shown.replace("+", "") : shown, l.text(), time);
                            changed = e;
                            break;
                        }
                    }
                } else if (l.folds()) {
                    for (Iterator<LogEntry> it = mine.descendingIterator(); it.hasNext(); ) {
                        LogEntry e = it.next();
                        if (e.key().equals(key) && time - e.last() <= MERGE_MS && time - e.first() <= SPAN_MS) {
                            e.repeat(l.text(), time);
                            changed = e;
                            break;
                        }
                    }
                }
                if (changed == null) {
                    changed = new LogEntry(++seq, account, lv, simple, category, true, true, key, l.text(), "", time);
                    rows.addLast(changed);
                    while (rows.size() > MAX_ROWS) {
                        rows.removeFirst();
                    }
                    mine.addLast(changed);
                    while (mine.size() > LOOK_BACK) {
                        mine.removeFirst();
                    }
                    if (l.sum() != null) {
                        sums.put(changed.seq(), l.body());
                    }
                    if (l.live()) {
                        changed.live(time);
                    }
                }
                if (l.text().startsWith("✖")) {
                    lastDeath.put(who, changed);
                }
            }
        }
        if (changed != null) {
            for (Consumer<LogEntry> l : listeners) {
                l.accept(changed);
            }
        }
    }

    static final String LOOT = "Добыча:";
    static final String LOOT_SUM = "Добыча всего:";
    private static final Pattern LOOT_PART = Pattern.compile("^\\+([\\d\\s\\u00a0.,]*\\d)\\s+(.+)$");

    /**
     * A loot line (+N name parts) added to the row's loot total so far, the amounts summed
     * (amounts by name, in the order first seen; a part that is not "+N name" is kept once as it is).
     */
    static String sumLoot(String sofar, String next) {
        java.util.LinkedHashMap<String, Double> sum = new java.util.LinkedHashMap<>();
        java.util.LinkedHashSet<String> other = new java.util.LinkedHashSet<>();
        for (String text : List.of(sofar, next)) {
            String body = text.startsWith(LOOT_SUM) ? text.substring(LOOT_SUM.length())
                    : text.startsWith(LOOT) ? text.substring(LOOT.length()) : text;
            for (String part : body.split("·")) {
                String t = part.strip();
                if (t.isEmpty()) {
                    continue;
                }
                java.util.regex.Matcher m = LOOT_PART.matcher(t);
                if (m.matches()) {
                    String digits = m.group(1).replaceAll("[\\s\\u00a0]", "").replace(',', '.');
                    try {
                        sum.merge(m.group(2).strip(), Double.parseDouble(digits), Double::sum);
                        continue;
                    } catch (NumberFormatException ignored) {
                        // kept as words below
                    }
                }
                other.add(t);
            }
        }
        List<String> parts = new ArrayList<>();
        sum.forEach((name, v) -> parts.add("+" + String.format("%,.0f", v).replace(',', ' ').replace('\u00a0', ' ')
                + " " + name));
        parts.addAll(other);
        return LOOT_SUM + " " + String.join("  ·  ", parts);
    }

    /** All rows, oldest first. */
    public List<LogEntry> rows() {
        synchronized (rows) {
            return new ArrayList<>(rows);
        }
    }

    /**
     * Every row added or repeated from now on; returns how to stop. The rows so far: {@link #rows()} (taken after
     * subscribing, a row may come both ways — by its {@link LogEntry#seq()} it is the same).
     */
    public Runnable listen(Consumer<LogEntry> listener) {
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** The accounts that logged anything, in order of their first line. */
    public List<String> accounts() {
        synchronized (rows) {
            return new ArrayList<>(accounts);
        }
    }
}
