package orbit.app.log;

/**
 * One row of the log: a line, or the same line repeated ({@link #count()} times from {@link #first()} to
 * {@link #last()}, the latest wording kept: «Подобрал палладий (за сессию: 812)» ×37). Changed only by
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
    private final String key;
    private final String firstText;
    private final String details;
    private final long first;
    private volatile long last;
    private volatile int count = 1;
    private volatile String text;

    LogEntry(long seq, String account, Level level, String logger, LogCategory category, boolean important,
             String key, String text, String details, long time) {
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
    }

    void repeat(String text, long time) {
        this.text = text;
        this.last = time;
        this.count++;
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
