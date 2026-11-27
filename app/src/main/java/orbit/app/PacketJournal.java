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
    /** The account whose packets these are ({@code null}: not known yet), for the file record. */
    private volatile String account;
    private java.io.Writer file;
    private long fileCheckedAt;
    private boolean recording;
    /** Looked for the flag this often. */
    static final long FLAG_CHECK_MS = 5_000;

    void account(String a) {
        account = a;
    }

    /**
     * The file record (docs/PLAN.md «Окно пакетов»): while {@code local/record-<account>} exists every packet is also
     * written to {@code logs/packets-<account>.log} as the window shows it (secrets hidden) — to study a fight after it
     * (2026-11-27: why one Boss Kristallon fight dealt three times the damage of another).
     */
    private void record(Entry e) {
        String a = account;
        if (a == null) {
            return;
        }
        long now = e.millis();
        if (now - fileCheckedAt > FLAG_CHECK_MS) {
            fileCheckedAt = now;
            boolean on = java.nio.file.Files.exists(java.nio.file.Path.of("local", "record-" + a));
            if (on && file == null) {
                try {
                    java.nio.file.Path f = java.nio.file.Path.of("logs", "packets-" + a + ".log");
                    java.nio.file.Files.createDirectories(f.getParent());
                    file = java.nio.file.Files.newBufferedWriter(f, java.nio.charset.StandardCharsets.UTF_8,
                            java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                } catch (java.io.IOException ex) {
                    file = null;
                }
            } else if (!on && file != null) {
                try {
                    file.close();
                } catch (java.io.IOException ignored) {
                    // closing a record: nothing to do
                }
                file = null;
            }
            recording = on && file != null;
            if (recording) {
                try {
                    file.flush();
                } catch (java.io.IOException ignored) {
                    recording = false;
                }
            }
        }
        if (!recording) {
            return;
        }
        try {
            file.write(java.time.LocalTime.now().withNano((java.time.LocalTime.now().getNano() / 1_000_000) * 1_000_000)
                    + (e.out() ? " > " : " < ") + e.text() + "\n");
        } catch (java.io.IOException ex) {
            recording = false;
        }
    }

    public synchronized void add(boolean out, Packet p) {
        if (p == null) {
            return;
        }
        if (ring.size() >= CAPACITY) {
            ring.pollFirst();
        }
        Entry e = new Entry(++seq, System.currentTimeMillis(), out, p);
        ring.addLast(e);
        record(e);
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
