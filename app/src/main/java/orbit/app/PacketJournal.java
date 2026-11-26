package orbit.app;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import orbit.protocol.Packet;

/**
 * The account's last game packets, both ways, for the «Пакеты» window (docs/PLAN.md «Окно пакетов»): a ring of
 * {@link #CAPACITY} entries kept always, so what just happened can be looked at after the fact. Packets are kept as they are
 * and printed only when shown; the printing hides the session id and the like ({@link #text}).
 */
public final class PacketJournal {

    /** Entries kept per account. */
    public static final int CAPACITY = 20_000;

    /**
     * One packet: its number in the journal, when (wall clock), which way ({@code out} = the bot sent it); the text is made
     * once, when first asked (a filter reads thousands of them every refresh).
     */
    public static final class Entry {
        private final long seq;
        private final long millis;
        private final boolean out;
        private final Packet packet;
        private volatile String text;

        Entry(long seq, long millis, boolean out, Packet packet) {
            this.seq = seq;
            this.millis = millis;
            this.out = out;
            this.packet = packet;
        }

        public long seq() {
            return seq;
        }

        public long millis() {
            return millis;
        }

        public boolean out() {
            return out;
        }

        public Packet packet() {
            return packet;
        }

        public String name() {
            return packet.getClass().getSimpleName();
        }

        public String text() {
            String t = text;
            if (t == null) {
                t = PacketJournal.text(packet);
                text = t;
            }
            return t;
        }
    }

    private final ArrayDeque<Entry> ring = new ArrayDeque<>();
    private long seq;

    public synchronized void add(boolean out, Packet p) {
        if (p == null) {
            return;
        }
        if (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        ring.addLast(new Entry(++seq, System.currentTimeMillis(), out, p));
    }

    /** The entries after number {@code after}, oldest first. */
    public synchronized List<Entry> since(long after) {
        List<Entry> out = new ArrayList<>();
        if (ring.isEmpty() || ring.peekLast().seq() <= after) {
            return out;
        }
        for (Entry e : ring) {
            if (e.seq() > after) {
                out.add(e);
            }
        }
        return out;
    }

    /** Field names whose values are never shown or saved: the session, passwords, keys. */
    static final Pattern SECRET = Pattern.compile(
            "(?i)\\b(\\w*(?:session|password|dosid|token|secret|hash|key)\\w*)=(\"[^\"]*\"|[^,}\\]]*)");

    /** The packet's fields as text, the secret-looking ones replaced by {@code ***}. */
    public static String text(Packet p) {
        return SECRET.matcher(String.valueOf(p)).replaceAll("$1=***");
    }
}
