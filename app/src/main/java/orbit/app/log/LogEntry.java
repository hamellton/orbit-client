package orbit.app.log;

/**
 * One row of the log: a line, or the same line repeated ({@link #count()} times from {@link #first()} to
 * {@link #last()}, the latest wording kept: a palladium pickup line with its session count ×37). Changed only by
 * {@link LogStore} under its lock; read from the window's thread.
 */
public final class LogEntry {

    public enum Level { ERROR, WARN, INFO }

    private final long seq;
    private final String account;
    private final Level level;
    private final String logger;
    private final LogCategory category;
    private final boolean important;
    /** A row of the plain-words journal ({@link Friendly}), not a line of the full log. */
    private final boolean journal;
    private final String key;
    private final String firstText;
    private final String details;
    private final long first;
    private volatile long last;
    private volatile int count = 1;
    private volatile String text;
    /** Each line folded in, with its time (the last {@link #KEPT} of them): when which loot came. */
    private final java.util.ArrayDeque<Occurrence> occurrences = new java.util.ArrayDeque<>();
    static final int KEPT = 100;

    /** One line folded into the row. */
    public record Occurrence(long time, String text) {
    }

    LogEntry(long seq, String account, Level level, String logger, LogCategory category, boolean important,
             String key, String text, String details, long time) {
        this(seq, account, level, logger, category, important, false, key, text, details, time);
    }

    LogEntry(long seq, String account, Level level, String logger, LogCategory category, boolean important,
             boolean journal, String key, String text, String details, long time) {
        this.journal = journal;
        this.seq = seq;
        this.account = account;
        this.level = level;
        this.logger = logger;
        this.category = category;
        this.important = important;
        this.key = key;
        this.firstText = text;
        this.text = text;
        this.details = details;
        this.first = time;
        this.last = time;
        occurrences.add(new Occurrence(time, text));
    }

    void repeat(String text, long time) {
        repeat(text, text, time);
    }

    /** Folds a line in: {@code shown} is the row's text now (the loot summed), {@code line} the line itself. */
    void repeat(String shown, String line, long time) {
        this.text = shown;
        this.last = time;
        this.count++;
        synchronized (occurrences) {
            occurrences.addLast(new Occurrence(time, line));
            while (occurrences.size() > KEPT) {
                occurrences.removeFirst();
            }
        }
    }

    /** Since when the row's words count seconds up (a logout under way), {@code 0} when it does not. */
    private volatile long liveSince;

    /** New words in the same place (a countdown, the wave), without counting a repeat. */
    void replace(String text, long time, boolean live) {
        this.text = text;
        this.last = time;
        this.liveSince = live ? (liveSince == 0 ? time : liveSince) : 0;
    }

    void live(long since) {
        this.liveSince = since;
    }

    /** {@link #liveSince} — the window adds the seconds gone to the row's words while it is not 0. */
    public long liveSince() {
        return liveSince;
    }

    /** Adds words at the end of the row (a death's repair), without counting a repeat. */
    void amend(String more, long time) {
        this.text = this.text + more;
        this.last = time;
    }

    /** A row of the plain-words journal (shown on the Journal tab); the full log's rows are on All and Problems. */
    public boolean journal() {
        return journal;
    }

    /** The lines folded into the row, oldest first (the last {@link #KEPT}). */
    public java.util.List<Occurrence> occurrences() {
        synchronized (occurrences) {
            return java.util.List.copyOf(occurrences);
        }
    }

    /** Order of arrival: rows are kept and shown by it. */
    public long seq() {
        return seq;
    }

    /** The account the line was logged for, {@code null} for none. */
    public String account() {
        return account;
    }

    public Level level() {
        return level;
    }

    /** The class that logged it (simple name). */
    public String logger() {
        return logger;
    }

    public LogCategory category() {
        return category;
    }

    /** In the journal. */
    public boolean important() {
        return important;
    }

    String key() {
        return key;
    }

    /** The latest wording. */
    public String text() {
        return text;
    }

    /** The wording of the first of the repeats. */
    public String firstText() {
        return firstText;
    }

    /** A stack trace, or "". */
    public String details() {
        return details;
    }

    /** Epoch milliseconds. */
    public long first() {
        return first;
    }

    public long last() {
        return last;
    }

    public int count() {
        return count;
    }

    public boolean problem() {
        return level != Level.INFO;
    }
}
