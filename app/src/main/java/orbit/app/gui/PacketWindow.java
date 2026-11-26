package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.table.AbstractTableModel;
import orbit.app.PacketJournal;
import orbit.app.WorldFeed;

/**
 * The account's game packets live, both ways (docs/PLAN.md «Окно пакетов», a user's wish 2026-11-26): time, way, name and
 * fields; a filter, the frequent ones hidden, a pause, the selected packet in full below, the shown rows saved as text to
 * {@code recordings/}. Only reads: nothing is sent.
 */
final class PacketWindow {

    private static final Map<String, JFrame> OPEN = new ConcurrentHashMap<>();
    /** Rows kept in the window (the journal keeps {@link PacketJournal#CAPACITY}). */
    static final int MAX_ROWS = 5_000;
    /**
     * Packets that come many times a second and bury the rest: hidden by «без частых» (moves, keep-alives, the hero's
     * position and health ticks).
     */
    static final Set<String> FREQUENT = Set.of("MoveCommand", "MoveRequest", "HeroMoveCommand", "KeepAlive",
            "AttributeHitpointUpdateCommand", "AttributeShieldUpdateCommand", "AttackHitCommand", "AttackLaserRunCommand",
            "AttackMissedCommand", "HitpointInfoCommand", "PetHitpointsUpdateCommand", "PetShieldUpdateCommand");
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");
    private static final DateTimeFormatter FILE = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private PacketWindow() {
    }

    static void open(WorldFeed feed) {
        String key = feed.account() == null ? feed.title() : feed.account();
        JFrame shown = OPEN.get(key);
        if (shown != null) {
            shown.toFront();
            return;
        }
        JFrame f = new JFrame("Пакеты — " + feed.title());
        Model model = new Model();
        JTable table = new JTable(model);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setFillsViewportHeight(true);
        table.setFont(new Font(Font.MONOSPACED, Font.PLAIN, table.getFont().getSize()));
        table.getColumnModel().getColumn(0).setPreferredWidth(95);
        table.getColumnModel().getColumn(0).setMaxWidth(110);
        table.getColumnModel().getColumn(1).setPreferredWidth(30);
        table.getColumnModel().getColumn(1).setMaxWidth(40);
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        table.getColumnModel().getColumn(3).setPreferredWidth(700);
        JScrollPane tableScroll = new JScrollPane(table);
        JTextArea detail = new JTextArea(6, 80);
        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, detail.getFont().getSize()));
        table.getSelectionModel().addListSelectionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                PacketJournal.Entry en = model.rows.get(table.convertRowIndexToModel(r));
                detail.setText(line(en));
                detail.setCaretPosition(0);
            }
        });

        JTextField filter = new JTextField(22);
        filter.putClientProperty("JTextField.placeholderText", "фильтр: имя или текст");
        JCheckBox in = new JCheckBox("от сервера", true);
        JCheckBox out = new JCheckBox("от бота", true);
        JCheckBox frequent = new JCheckBox("без частых", true);
        frequent.setToolTipText("Скрыть то, что идёт много раз в секунду: движение, keep-alive, HP, выстрелы");
        JToggleButton pause = new JToggleButton("Пауза");
        JButton clear = new JButton("Очистить");
        JButton save = new JButton("Сохранить…");
        save.setToolTipText("Показанные строки — в текстовый файл в recordings/ (сессия и ключи скрыты)");
        JLabel note = new JLabel(" ");
        JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 6));
        for (var c : List.of(filter, in, out, frequent, pause, clear, save, note)) {
            top.add(c);
        }

        long[] last = {0};
        List<PacketJournal.Entry> all = new ArrayList<>();
        Runnable rebuild = () -> {
            String q = filter.getText().strip().toLowerCase(Locale.ROOT);
            List<PacketJournal.Entry> keep = new ArrayList<>();
            for (PacketJournal.Entry e : all) {
                if (shows(e, q, in.isSelected(), out.isSelected(), frequent.isSelected())) {
                    keep.add(e);
                }
            }
            model.set(keep.size() > MAX_ROWS ? keep.subList(keep.size() - MAX_ROWS, keep.size()) : keep);
            note.setText(model.rows.size() + " из " + all.size());
        };
        Timer timer = new Timer(400, e -> {
            if (pause.isSelected()) {
                return;
            }
            List<PacketJournal.Entry> fresh = feed.journal().since(last[0]);
            if (fresh.isEmpty()) {
                return;
            }
            last[0] = fresh.getLast().seq();
            all.addAll(fresh);
            if (all.size() > PacketJournal.CAPACITY) {
                all.subList(0, all.size() - PacketJournal.CAPACITY).clear();
            }
            var bar = tableScroll.getVerticalScrollBar();
            boolean atEnd = bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 4;
            rebuild.run();
            if (atEnd && table.getSelectedRow() < 0) {
                table.scrollRectToVisible(table.getCellRect(model.getRowCount() - 1, 0, true));
            }
        });
        filter.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            @Override
            public void insertUpdate(javax.swing.event.DocumentEvent e) {
                rebuild.run();
            }

            @Override
            public void removeUpdate(javax.swing.event.DocumentEvent e) {
                rebuild.run();
            }

            @Override
            public void changedUpdate(javax.swing.event.DocumentEvent e) {
                rebuild.run();
            }
        });
        for (JCheckBox c : List.of(in, out, frequent)) {
            c.addActionListener(e -> rebuild.run());
        }
        clear.addActionListener(e -> {
            all.clear();
            table.clearSelection();
            detail.setText("");
            rebuild.run();
        });
        save.addActionListener(e -> {
            try {
                Path file = save(key, model.rows);
                note.setText("сохранено: " + file);
            } catch (IOException ex) {
                note.setText("не сохранено: " + ex.getMessage());
            }
        });
        timer.start();

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tableScroll, new JScrollPane(detail));
        split.setResizeWeight(0.8);
        JPanel root = new JPanel(new BorderLayout());
        root.add(top, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        f.setContentPane(root);
        f.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        f.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                timer.stop();
                OPEN.remove(key);
            }
        });
        f.setSize(new Dimension(1100, 700));
        f.setLocationByPlatform(true);
        OPEN.put(key, f);
        f.setVisible(true);
    }

    static boolean shows(PacketJournal.Entry e, String q, boolean in, boolean out, boolean noFrequent) {
        if (e.out() ? !out : !in) {
            return false;
        }
        if (noFrequent && FREQUENT.contains(e.name())) {
            return false;
        }
        return q.isEmpty() || e.name().toLowerCase(Locale.ROOT).contains(q) || e.text().toLowerCase(Locale.ROOT).contains(q);
    }

    static String line(PacketJournal.Entry e) {
        return time(e.millis()) + (e.out() ? " → " : " ← ") + e.text();
    }

    private static String time(long millis) {
        return TIME.format(LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()));
    }

    /** The rows as text lines (← from the server, → from the bot) in {@code recordings/packets-<account>-<time>.txt}. */
    static Path save(String account, List<PacketJournal.Entry> rows) throws IOException {
        Path dir = Path.of("recordings");
        Files.createDirectories(dir);
        Path file = dir.resolve("packets-" + account.replaceAll("[^\\w.-]", "_") + "-" + FILE.format(LocalDateTime.now())
                + ".txt");
        List<String> lines = new ArrayList<>(rows.size());
        rows.forEach(r -> lines.add(line(r)));
        Files.write(file, lines, StandardCharsets.UTF_8);
        return file;
    }

    private static final class Model extends AbstractTableModel {
        private static final String[] COLUMNS = {"Время", "", "Пакет", "Поля"};
        List<PacketJournal.Entry> rows = List.of();

        void set(List<PacketJournal.Entry> next) {
            rows = List.copyOf(next);
            fireTableDataChanged();
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Object getValueAt(int r, int c) {
            PacketJournal.Entry e = rows.get(r);
            return switch (c) {
                case 0 -> time(e.millis());
                case 1 -> e.out() ? "→" : "←";
                case 2 -> e.name();
                default -> {
                    String t = e.text();
                    int brace = t.indexOf('{');
                    yield brace >= 0 ? t.substring(brace) : t;
                }
            };
        }
    }
}
