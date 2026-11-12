package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.prefs.Preferences;
import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.JButton;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JToggleButton;
import javax.swing.KeyStroke;
import javax.swing.ListCellRenderer;
import javax.swing.ListSelectionModel;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import orbit.app.log.LogCategory;
import orbit.app.log.LogEntry;
import orbit.app.log.LogStore;

/**
 * The log of the window: «Журнал» (what matters: starts, stops, deaths, quests, sales, hourly summaries, problems),
 * «Всё» (every line, repeats folded into one row with ×N) or «Проблемы» (warnings, errors); the subjects as chips
 * with their counts (a click — on/off, a double click — only this one); the account (in «Все»), the period, a
 * search; rows coloured by subject and weight; the row picked — its full text, repeats and times below.
 */
public final class LogPanel extends JPanel {

    enum Mode { JOURNAL, ALL, PROBLEMS }

    static final int ROW_H = 22;
    /** How many rows the view keeps at most. */
    static final int MAX_ROWS = 100_000;
    private static final Color ROW_ALT = new Color(0x111720);
    private static final Color WARN_FG = new Color(0xfbbf24);
    private static final Color ERROR_FG = new Color(0xf87171);
    private static final Color WARN_BG = new Color(0x2a2414);
    private static final Color ERROR_BG = new Color(0x2e1719);
    private static final Color SUMMARY_BG = new Color(0x1d1830);
    private static final Color ROUTINE = new Color(0xa3adbb);
    private static final Color[] ACCOUNT_COLORS = {new Color(0x38bdf8), new Color(0xf472b6), new Color(0xa3e635),
            new Color(0xfbbf24), new Color(0xc084fc), new Color(0x2dd4bf), new Color(0xfb923c), new Color(0x60a5fa)};
    /** The ready periods; any other can be typed in («2ч 10м», «45м», «1.5ч», «90» = minutes). */
    static final String[] PERIODS = {"За всё время", "5 мин", "10 мин", "15 мин", "20 мин", "30 мин", "1 ч", "2 ч",
            "3 ч", "6 ч", "12 ч", "24 ч"};
    private static final java.util.regex.Pattern PERIOD_PART =
            java.util.regex.Pattern.compile("(\\d+(?:[.,]\\d+)?)\\s*(сут|дн|д|час|ч|мин|м|сек|с|d|h|m|s)?[а-яa-z]*");
    private static final Preferences PREFS = Preferences.userRoot().node("orbit/log");

    private final LogStore store;
    /** The tab's account, or {@code null} for every account (the «Все» tab). */
    private final String fixedAccount;
    /** Only the account's own lines, not those of no account. */
    private final boolean strict;

    private Mode mode;
    private final Set<LogCategory> hidden = EnumSet.noneOf(LogCategory.class);
    private String account;
    private long periodMs;
    private String query = "";

    private final Rows model = new Rows();
    private final JList<LogEntry> list = new JList<>(model);
    private final JScrollPane scroll;
    private final Map<LogCategory, JToggleButton> chips = new EnumMap<>(LogCategory.class);
    private final Map<LogCategory, Integer> counts = new EnumMap<>(LogCategory.class);
    private final JComboBox<String> accounts = new JComboBox<>();
    private final JLabel status = new JLabel();
    private final JTextArea detail = new JTextArea();
    private final JScrollPane detailScroll;
    private final JPanel detailBox;
    private final JToggleButton follow = new JToggleButton("⇣ Следить", true);
    private final ConcurrentLinkedQueue<LogEntry> incoming = new ConcurrentLinkedQueue<>();
    private final List<String> knownAccounts = new ArrayList<>();
    private final Runnable unsubscribe;
    private final Timer drain;
    private final Timer ageing;
    private JLabel chipsState;
    private JButton showAll;
    private final Map<Mode, JToggleButton> modeButtons = new EnumMap<>(Mode.class);
    private long seenSeq;
    private boolean adjusting;

    /**
     * @param fixedAccount the tab's account, {@code null} for all accounts with an account choice
     * @param strict       only the account's own lines (the manager's tabs)
     */
    public LogPanel(LogStore store, String fixedAccount, boolean strict) {
        super(new BorderLayout());
        this.store = store;
        this.fixedAccount = fixedAccount;
        this.strict = strict;
        this.mode = Mode.valueOf(PREFS.get("mode", Mode.JOURNAL.name()));
        setBackground(Theme.BACKGROUND);

        list.setBackground(Theme.BACKGROUND);
        list.setFixedCellHeight(ROW_H);
        list.setCellRenderer(new RowRenderer());
        list.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        list.addListSelectionListener(e -> showDetail());
        list.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuKey()),
                "orbit.copy");
        list.getActionMap().put("orbit.copy", action(this::copySelected));
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                // a row picked to read: new lines must not scroll it away
                if (list.locationToIndex(e.getPoint()) < model.getSize() - 1) {
                    follow.setSelected(false);
                }
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(Theme.BACKGROUND);
        scroll.getVerticalScrollBar().setUnitIncrement(ROW_H);
        // scrolled up by hand: stop following; back at the bottom: follow again
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!adjusting && e.getValueIsAdjusting()) {
                follow.setSelected(atBottom());
            }
        });
        scroll.addMouseWheelListener(e -> javax.swing.SwingUtilities.invokeLater(() -> follow.setSelected(atBottom())));

        detail.setEditable(false);
        detail.setLineWrap(true);
        detail.setWrapStyleWord(true);
        detail.setBackground(Theme.SURFACE);
        detail.setForeground(Theme.TEXT);
        detail.setFont(font(12f));
        detail.setBorder(BorderFactory.createEmptyBorder(6, 12, 6, 12));
        detailScroll = new JScrollPane(detail);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        // the row's details with a plain way out: ✕, or Esc
        JButton close = new JButton("✕  Закрыть");
        close.putClientProperty("JButton.buttonType", "roundRect");
        close.setFont(font(11.5f));
        close.setFocusable(false);
        close.setToolTipText("Закрыть подробности (Esc)");
        close.addActionListener(e -> list.clearSelection());
        JPanel closeBox = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 6));
        closeBox.setBackground(Theme.SURFACE);
        closeBox.add(close);
        JPanel closeColumn = new JPanel(new BorderLayout());
        closeColumn.setBackground(Theme.SURFACE);
        closeColumn.add(closeBox, BorderLayout.NORTH);
        detailBox = new JPanel(new BorderLayout());
        detailBox.setBackground(Theme.SURFACE);
        detailBox.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        detailBox.add(detailScroll, BorderLayout.CENTER);
        detailBox.add(closeColumn, BorderLayout.EAST);
        detailBox.setPreferredSize(new Dimension(100, 74));
        detailBox.setVisible(false);
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), "orbit.closeDetail");
        getActionMap().put("orbit.closeDetail", action(list::clearSelection));

        JPanel south = new JPanel(new BorderLayout());
        south.setOpaque(false);
        south.add(detailBox, BorderLayout.CENTER);
        status.setForeground(Theme.DIM);
        status.setFont(font(11f));
        status.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(3, 12, 3, 12)));
        south.add(status, BorderLayout.SOUTH);

        add(toolbar(), BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.BORDER));
        setPreferredSize(new Dimension(1000, 260));
        setMinimumSize(new Dimension(100, 0));

        unsubscribe = store.listen(incoming::add);
        rebuild();
        drain = new Timer(250, e -> drain());
        drain.start();
        // «the last hour» moves on: rows that left it go
        ageing = new Timer(10_000, e -> {
            if (periodMs > 0) {
                rebuild();
            }
        });
        ageing.start();
    }

    /** No more lines: the tab was closed. */
    public void dispose() {
        unsubscribe.run();
        drain.stop();
        ageing.stop();
    }

    // ---- toolbar ----

    private JComponent toolbar() {
        JPanel bar = new JPanel(new WrapLayout(FlowLayout.LEFT, 6, 4));
        bar.setBackground(Theme.SURFACE);
        bar.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER),
                BorderFactory.createEmptyBorder(0, 6, 0, 6)));

        ButtonGroup modes = new ButtonGroup();
        for (Mode m : Mode.values()) {
            JToggleButton b = new JToggleButton(switch (m) {
                case JOURNAL -> "Журнал";
                case ALL -> "Всё";
                case PROBLEMS -> "Проблемы";
            }, m == mode);
            // the chosen one filled, the others plain: no doubt which is on
            b.putClientProperty("JButton.buttonType", "roundRect");
            b.putClientProperty("FlatLaf.style", "selectedBackground: #0e5a7c; selectedForeground: #ffffff;"
                    + " background: #18202b; foreground: #8b96a8; font: bold");
            b.setToolTipText(switch (m) {
                case JOURNAL -> "Главное: старт и стоп, смерти, квесты, продажи, переходы, сводка за час, проблемы";
                case ALL -> "Все строки; одинаковые подряд свёрнуты в одну (×N)";
                case PROBLEMS -> "Только предупреждения и ошибки";
            });
            b.setFocusable(false);
            b.addActionListener(e -> {
                mode = m;
                PREFS.put("mode", m.name());
                rebuild();
            });
            modes.add(b);
            modeButtons.put(m, b);
            bar.add(b);
        }
        bar.add(separator());

        if (fixedAccount == null) {
            accounts.addItem("Все аккаунты");
            accounts.setToolTipText("Чьи строки показывать");
            accounts.addActionListener(e -> {
                int i = accounts.getSelectedIndex();
                String a = i <= 0 || i > knownAccounts.size() ? null : knownAccounts.get(i - 1);
                if (!java.util.Objects.equals(a, account)) {
                    account = a;
                    rebuild();
                }
            });
            refreshAccounts();
            bar.add(accounts);
        }
        JComboBox<String> period = new JComboBox<>(PERIODS);
        period.setEditable(true);
        period.setPrototypeDisplayValue("За всё время  ");
        period.setToolTipText("За какое время показывать: выберите или впишите своё — «2ч 10м», «45м», «1.5ч», «90» (минуты)");
        period.addActionListener(e -> {
            Long ms = parsePeriod(String.valueOf(period.getSelectedItem()));
            period.putClientProperty("JComponent.outline", ms == null ? "error" : null);
            if (ms != null && ms != periodMs) {
                periodMs = ms;
                rebuild();
            }
        });
        bar.add(period);

        JTextField search = new JTextField(16);
        search.putClientProperty("JTextField.placeholderText", "Поиск (⌘F)");
        search.putClientProperty("JTextField.showClearButton", true);
        search.setToolTipText("Слова из строки, название раздела или аккаунт");
        Timer typing = new Timer(200, e -> {
            query = search.getText().strip().toLowerCase(Locale.ROOT);
            rebuild();
        });
        typing.setRepeats(false);
        search.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) {
                typing.restart();
            }

            @Override
            public void removeUpdate(DocumentEvent e) {
                typing.restart();
            }

            @Override
            public void changedUpdate(DocumentEvent e) {
                typing.restart();
            }
        });
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(
                KeyStroke.getKeyStroke(KeyEvent.VK_F, menuKey()), "orbit.find");
        getActionMap().put("orbit.find", action(search::requestFocusInWindow));
        bar.add(search);

        JButton prev = small("▲", "Предыдущая проблема");
        prev.addActionListener(e -> jumpToProblem(-1));
        JButton next = small("▼", "Следующая проблема");
        next.addActionListener(e -> jumpToProblem(1));
        JLabel problems = new JLabel("проблемы");
        problems.setForeground(Theme.DIM);
        problems.setFont(font(11f));
        bar.add(prev);
        bar.add(problems);
        bar.add(next);

        follow.setFocusable(false);
        follow.setToolTipText("Показывать новые строки сразу (выключается, если прокрутить вверх)");
        follow.addActionListener(e -> {
            if (follow.isSelected()) {
                toEnd();
            }
        });
        bar.add(follow);

        JButton more = small("⋯", "Ещё");
        more.addActionListener(e -> moreMenu().show(more, 0, more.getHeight()));
        bar.add(more);

        // the subjects on a row of their own, one line whatever the width (scrolled sideways when narrow)
        JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 3));
        row.setBackground(Theme.SURFACE);
        JLabel title = new JLabel("Разделы:");
        title.setForeground(Theme.TEXT);
        title.setFont(font(11.5f).deriveFont(Font.BOLD));
        row.add(title);
        chipsState = new JLabel();
        chipsState.setFont(font(11f));
        row.add(chipsState);
        showAll = new JButton("Показать все");
        showAll.putClientProperty("JButton.buttonType", "roundRect");
        showAll.setFont(font(11f));
        showAll.setFocusable(false);
        showAll.setToolTipText("Вернуть все скрытые разделы");
        showAll.addActionListener(e -> {
            hidden.clear();
            syncChips();
            rebuild();
        });
        row.add(showAll);
        row.add(separator());
        for (LogCategory c : LogCategory.values()) {
            JToggleButton b = chip(c);
            chips.put(c, b);
            row.add(b);
        }
        JScrollPane chipScroll = new JScrollPane(row, JScrollPane.VERTICAL_SCROLLBAR_NEVER,
                JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        chipScroll.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        chipScroll.getViewport().setBackground(Theme.SURFACE);
        chipScroll.setBackground(Theme.SURFACE);
        // the wheel moves the row sideways
        chipScroll.addMouseWheelListener(e -> {
            var vp = chipScroll.getViewport();
            int max = Math.max(0, row.getPreferredSize().width - vp.getWidth());
            int x = Math.max(0, Math.min(max, vp.getViewPosition().x + e.getWheelRotation() * 40));
            vp.setViewPosition(new java.awt.Point(x, 0));
        });
        JPanel top = new JPanel(new BorderLayout());
        top.setBackground(Theme.SURFACE);
        top.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.BORDER));
        bar.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 6));
        top.add(bar, BorderLayout.NORTH);
        top.add(chipScroll, BorderLayout.SOUTH);
        return top;
    }

    private JToggleButton chip(LogCategory c) {
        Chip b = new Chip(c);
        b.setSelected(!hidden.contains(c));
        b.addActionListener(e -> {
            if (b.isSelected()) {
                hidden.remove(c);
            } else {
                hidden.add(c);
            }
            syncChips();
            rebuild();
        });
        b.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    only(c);
                }
            }
        });
        return b;
    }

    /**
     * A subject's switch, unmistakable either way: shown — filled with its colour, a tick, bright words; hidden —
     * empty, a dashed dim edge, a hollow dot, the words struck through.
     */
    private static final class Chip extends JToggleButton {

        private final LogCategory category;
        private int count;
        private boolean hover;

        Chip(LogCategory category) {
            this.category = category;
            setFocusable(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setOpaque(false);
            setFont(font(11.5f));
            setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hover = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hover = false;
                    repaint();
                }
            });
            addItemListener(e -> tip());
            tip();
        }

        void setCount(int n) {
            if (n != count) {
                count = n;
                revalidate();
                repaint();
            }
        }

        private void tip() {
            setToolTipText("«" + category.label() + "» " + (isSelected() ? "показан — щелчок скроет" : "скрыт — щелчок покажет")
                    + "; двойной щелчок — только этот раздел");
        }

        private String countText() {
            return count > 0 ? String.valueOf(count) : "";
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            FontMetrics bm = getFontMetrics(getFont().deriveFont(Font.BOLD));
            int w = 10 + 12 + 6 + bm.stringWidth(category.label()) + 10;
            if (count > 0) {
                w += fm.stringWidth(countText()) + 8;
            }
            return new Dimension(w, 24);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            boolean on = isSelected();
            Color c = category.color();
            int w = getWidth() - 1;
            int h = getHeight() - 1;
            int arc = h;
            if (on) {
                g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), hover ? 85 : 60));
                g.fillRoundRect(0, 0, w, h, arc, arc);
                g.setColor(c);
                g.setStroke(new java.awt.BasicStroke(1.4f));
                g.drawRoundRect(0, 0, w, h, arc, arc);
            } else {
                if (hover) {
                    g.setColor(new Color(255, 255, 255, 14));
                    g.fillRoundRect(0, 0, w, h, arc, arc);
                }
                g.setColor(new Color(0x3a4558));
                g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND,
                        1f, new float[] {3f, 3f}, 0f));
                g.drawRoundRect(0, 0, w, h, arc, arc);
            }
            // the mark: a tick in a coloured disc, or a hollow ring
            int cx = 10;
            int cy = h / 2 - 5;
            if (on) {
                g.setColor(c);
                g.fillOval(cx, cy, 11, 11);
                g.setColor(new Color(0x0b0f17));
                g.setStroke(new java.awt.BasicStroke(1.8f, java.awt.BasicStroke.CAP_ROUND, java.awt.BasicStroke.JOIN_ROUND));
                g.drawPolyline(new int[] {cx + 3, cx + 5, cx + 8}, new int[] {cy + 6, cy + 8, cy + 3}, 3);
            } else {
                g.setColor(new Color(0x5b6678));
                g.setStroke(new java.awt.BasicStroke(1.3f));
                g.drawOval(cx, cy, 10, 10);
            }
            int x = cx + 12 + 6;
            int base = h / 2 + 5;
            Font f = on ? getFont().deriveFont(Font.BOLD) : getFont();
            g.setFont(f);
            FontMetrics fm = g.getFontMetrics();
            String label = category.label();
            g.setColor(on ? Theme.TEXT : new Color(0x6b7688));
            g.drawString(label, x, base);
            int lw = fm.stringWidth(label);
            if (!on) {
                g.setStroke(new java.awt.BasicStroke(1f));
                g.drawLine(x, base - fm.getAscent() / 2 + 1, x + lw, base - fm.getAscent() / 2 + 1);
            }
            if (count > 0) {
                g.setFont(getFont());
                g.setColor(on ? new Color(0xc7d0dc) : new Color(0x5b6678));
                g.drawString(countText(), x + lw + 8, base);
            }
            g.dispose();
        }
    }

    private void only(LogCategory c) {
        hidden.clear();
        for (LogCategory o : LogCategory.values()) {
            if (o != c) {
                hidden.add(o);
            }
        }
        syncChips();
        rebuild();
    }

    private void syncChips() {
        chips.forEach((c, b) -> {
            b.setSelected(!hidden.contains(c));
            ((Chip) b).setCount(counts.getOrDefault(c, 0));
        });
        if (chipsState != null) {
            int n = hidden.size();
            chipsState.setText(n == 0 ? "показаны все"
                    : n == LogCategory.values().length ? "все скрыты"
                    : "показано " + (LogCategory.values().length - n) + " из " + LogCategory.values().length);
            chipsState.setForeground(n == 0 ? Theme.DIM : new Color(0xfbbf24));
            showAll.setVisible(n > 0);
        }
    }

    private JPopupMenu moreMenu() {
        JPopupMenu m = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Копировать показанное");
        copy.addActionListener(e -> copy(model.rows));
        m.add(copy);
        JMenuItem folder = new JMenuItem("Открыть папку с логами");
        folder.addActionListener(e -> open(java.nio.file.Path.of("logs")));
        m.add(folder);
        String a = fixedAccount != null ? fixedAccount : account;
        if (a != null) {
            JMenuItem journal = new JMenuItem("Открыть журнал «" + a + "» (файл)");
            journal.addActionListener(e -> open(java.nio.file.Path.of("logs", "journal-" + a + ".log")));
            m.add(journal);
            JMenuItem full = new JMenuItem("Открыть полный лог «" + a + "» (файл)");
            full.addActionListener(e -> open(java.nio.file.Path.of("logs", "orbit-" + a + ".log")));
            m.add(full);
        }
        m.addSeparator();
        JCheckBoxMenuItem showLogger = new JCheckBoxMenuItem("Показывать, кто написал (класс)", PREFS.getBoolean("logger", false));
        showLogger.addActionListener(e -> {
            PREFS.putBoolean("logger", showLogger.isSelected());
            list.repaint();
        });
        m.add(showLogger);
        return m;
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger()) {
            return;
        }
        int i = list.locationToIndex(e.getPoint());
        if (i < 0) {
            return;
        }
        if (!list.isSelectedIndex(i)) {
            list.setSelectedIndex(i);
        }
        LogEntry row = model.rows.get(i);
        JPopupMenu m = new JPopupMenu();
        JMenuItem copy = new JMenuItem("Копировать");
        copy.addActionListener(ev -> copySelected());
        m.add(copy);
        m.addSeparator();
        JMenuItem only = new JMenuItem("Только «" + row.category().label() + "»");
        only.addActionListener(ev -> only(row.category()));
        m.add(only);
        JMenuItem hide = new JMenuItem("Скрыть «" + row.category().label() + "»");
        hide.addActionListener(ev -> {
            hidden.add(row.category());
            syncChips();
            rebuild();
        });
        m.add(hide);
        if (fixedAccount == null && row.account() != null) {
            JMenuItem mine = new JMenuItem("Только аккаунт «" + row.account() + "»");
            mine.addActionListener(ev -> accounts.setSelectedIndex(knownAccounts.indexOf(row.account()) + 1));
            m.add(mine);
        }
        m.show(list, e.getX(), e.getY());
    }

    // ---- rows ----

    private boolean inScope(LogEntry e) {
        if (fixedAccount != null) {
            return fixedAccount.equals(e.account()) || !strict && e.account() == null;
        }
        return account == null || account.equals(e.account());
    }

    /** Everything but the subject chips (they show their counts under the other filters). */
    private boolean passesButCategory(LogEntry e, long now) {
        if (!inScope(e)) {
            return false;
        }
        if (mode == Mode.JOURNAL && !e.important() || mode == Mode.PROBLEMS && !e.problem()) {
            return false;
        }
        if (periodMs > 0 && now - e.last() > periodMs) {
            return false;
        }
        if (!query.isEmpty()) {
            String hay = (e.text() + " " + e.firstText() + " " + e.category().label() + " "
                    + (e.account() == null ? "" : e.account())).toLowerCase(Locale.ROOT);
            for (String w : query.split("\\s+")) {
                if (!hay.contains(w)) {
                    return false;
                }
            }
        }
        return true;
    }

    private void rebuild() {
        long now = System.currentTimeMillis();
        List<LogEntry> rows = new ArrayList<>();
        counts.clear();
        long maxSeq = seenSeq;
        for (LogEntry e : store.rows()) {
            maxSeq = Math.max(maxSeq, e.seq());
            if (!passesButCategory(e, now)) {
                continue;
            }
            counts.merge(e.category(), 1, Integer::sum);
            if (!hidden.contains(e.category())) {
                rows.add(e);
            }
        }
        seenSeq = maxSeq;
        LogEntry keep = list.getSelectedValue();
        model.set(rows);
        if (keep != null) {
            int i = model.indexOf(keep);
            if (i >= 0) {
                list.setSelectedIndex(i);
            }
        }
        syncChips();
        updateStatus();
        if (follow.isSelected()) {
            toEnd();
        }
    }

    private void drain() {
        if (incoming.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        boolean added = false;
        boolean countsChanged = false;
        java.util.Set<LogEntry> batch = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        for (LogEntry e; (e = incoming.poll()) != null; ) {
            batch.add(e);
        }
        List<LogEntry> ordered = new ArrayList<>(batch);
        ordered.sort(java.util.Comparator.comparingLong(LogEntry::seq));
        for (LogEntry e : ordered) {
            boolean isNew = e.seq() > seenSeq;
            if (isNew) {
                seenSeq = e.seq();
            }
            if (!passesButCategory(e, now)) {
                continue;
            }
            if (isNew) {
                counts.merge(e.category(), 1, Integer::sum);
                countsChanged = true;
                if (!hidden.contains(e.category())) {
                    model.add(e);
                    added = true;
                }
            } else {
                int i = model.indexOf(e);
                if (i >= 0) {
                    model.changed(i);
                } else if (!hidden.contains(e.category())) {
                    // folded into a row older than the view's cut (the period) — show it again at its place
                    model.insert(e);
                    added = true;
                }
            }
        }
        if (model.rows.size() > MAX_ROWS) {
            model.trim(model.rows.size() - MAX_ROWS);
        }
        if (countsChanged) {
            syncChips();
        }
        updateStatus();
        if (fixedAccount == null && store.accounts().size() != knownAccounts.size()) {
            refreshAccounts();
        }
        if (added && follow.isSelected()) {
            toEnd();
        }
        LogEntry picked = list.getSelectedValue();
        if (picked != null && batch.contains(picked)) {
            showDetail();
        }
    }

    private void refreshAccounts() {
        List<String> now = store.accounts();
        if (now.equals(knownAccounts)) {
            return;
        }
        String keep = account;
        knownAccounts.clear();
        knownAccounts.addAll(now);
        ActionListenerMute mute = new ActionListenerMute(accounts);
        accounts.removeAllItems();
        accounts.addItem("Все аккаунты");
        knownAccounts.forEach(accounts::addItem);
        accounts.setSelectedIndex(keep == null ? 0 : knownAccounts.indexOf(keep) + 1);
        mute.restore();
    }

    /** Takes a combo's listeners off while its items are replaced. */
    private static final class ActionListenerMute {
        private final JComboBox<?> box;
        private final java.awt.event.ActionListener[] listeners;

        ActionListenerMute(JComboBox<?> box) {
            this.box = box;
            this.listeners = box.getActionListeners();
            for (java.awt.event.ActionListener l : listeners) {
                box.removeActionListener(l);
            }
        }

        void restore() {
            for (java.awt.event.ActionListener l : listeners) {
                box.addActionListener(l);
            }
        }
    }

    private void updateStatus() {
        int warn = 0;
        int err = 0;
        long lines = 0;
        for (LogEntry e : model.rows) {
            lines += e.count();
            if (e.level() == LogEntry.Level.WARN) {
                warn++;
            } else if (e.level() == LogEntry.Level.ERROR) {
                err++;
            }
        }
        StringBuilder s = new StringBuilder();
        s.append(String.format("%,d", model.rows.size()).replace(',', ' ')).append(" строк");
        if (lines > model.rows.size()) {
            s.append(" (").append(String.format("%,d", lines).replace(',', ' ')).append(" с повторами)");
        }
        if (warn > 0) {
            s.append("   ·   предупреждений ").append(warn);
        }
        if (err > 0) {
            s.append("   ·   ошибок ").append(err);
        }
        if (periodMs > 0) {
            s.append("   ·   за последние ").append(periodText(periodMs)).append(" (с ")
                    .append(time(System.currentTimeMillis() - periodMs, false)).append(")");
        }
        if (!hidden.isEmpty()) {
            s.append("   ·   скрыто разделов: ").append(hidden.size());
        }
        s.append("   ·   щелчок по строке — подробности ниже (✕ или Esc — закрыть), ⌘C — копировать");
        status.setText(s.toString());
    }

    private void showDetail() {
        List<LogEntry> picked = list.getSelectedValuesList();
        if (picked.size() != 1) {
            detailBox.setVisible(false);
            revalidate();
            return;
        }
        LogEntry e = picked.getFirst();
        StringBuilder s = new StringBuilder(e.text());
        s.append("\n");
        List<String> facts = new ArrayList<>();
        facts.add(e.category().label());
        if (e.account() != null) {
            facts.add("аккаунт «" + e.account() + "»");
        }
        if (e.count() > 1) {
            facts.add("повторов " + e.count() + ", с " + time(e.first(), true) + " по " + time(e.last(), true));
            if (!e.firstText().equals(e.text())) {
                facts.add("первая: " + e.firstText());
            }
        } else {
            facts.add(time(e.first(), true));
        }
        facts.add(e.logger());
        s.append(String.join("  ·  ", facts));
        if (!e.details().isEmpty()) {
            s.append("\n\n").append(e.details());
        }
        detail.setText(s.toString());
        detail.setCaretPosition(0);
        detailBox.setPreferredSize(new Dimension(100, e.details().isEmpty() ? 58 : 140));
        if (!detailBox.isVisible()) {
            detailBox.setVisible(true);
        }
        revalidate();
    }

    private void jumpToProblem(int dir) {
        int from = list.getSelectedIndex();
        int n = model.rows.size();
        if (n == 0) {
            return;
        }
        int start = from < 0 ? (dir > 0 ? -1 : n) : from;
        for (int i = start + dir; i >= 0 && i < n; i += dir) {
            if (model.rows.get(i).problem()) {
                follow.setSelected(false);
                list.setSelectedIndex(i);
                list.ensureIndexIsVisible(i);
                return;
            }
        }
        Toolkit.getDefaultToolkit().beep();
    }

    private boolean atBottom() {
        var m = scroll.getVerticalScrollBar().getModel();
        return m.getValue() + m.getExtent() >= m.getMaximum() - ROW_H;
    }

    private void toEnd() {
        int n = model.getSize();
        if (n > 0) {
            adjusting = true;
            list.ensureIndexIsVisible(n - 1);
            adjusting = false;
        }
    }

    private void copySelected() {
        List<LogEntry> picked = list.getSelectedValuesList();
        copy(picked.isEmpty() ? model.rows : picked);
    }

    private static void copy(List<LogEntry> rows) {
        StringBuilder s = new StringBuilder();
        for (LogEntry e : rows) {
            s.append(time(e.first(), true));
            if (e.count() > 1) {
                s.append("–").append(time(e.last(), true)).append(" ×").append(e.count());
            }
            s.append(' ').append(e.level() == LogEntry.Level.INFO ? "" : e.level() + " ");
            if (e.account() != null) {
                s.append('[').append(e.account()).append("] ");
            }
            s.append(e.text()).append('\n');
            if (!e.details().isEmpty()) {
                s.append(e.details()).append('\n');
            }
        }
        Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(s.toString()), null);
    }

    private static void open(java.nio.file.Path p) {
        try {
            java.awt.Desktop.getDesktop().open(p.toAbsolutePath().toFile());
        } catch (Exception e) {
            Toolkit.getDefaultToolkit().beep();
        }
    }

    // ---- painting ----

    static String time(long millis, boolean seconds) {
        LocalDateTime t = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault());
        String hm = seconds ? String.format("%02d:%02d:%02d", t.getHour(), t.getMinute(), t.getSecond())
                : String.format("%02d:%02d", t.getHour(), t.getMinute());
        LocalDate today = LocalDate.now();
        if (t.toLocalDate().equals(today)) {
            return hm;
        }
        return String.format("%02d.%02d ", t.getDayOfMonth(), t.getMonthValue()) + hm;
    }

    private Color accountColor(String a) {
        int i = knownAccounts.indexOf(a);
        if (i < 0) {
            i = Math.abs(a.hashCode());
        }
        return ACCOUNT_COLORS[i % ACCOUNT_COLORS.length];
    }

    private static Font font(float size) {
        Font base = UIManager.getFont("defaultFont");
        if (base == null) {
            base = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        }
        return base.deriveFont(size);
    }

    /** One row, drawn by hand: time, account, subject, ×N, the text, the span of the repeats. */
    private final class RowRenderer extends JComponent implements ListCellRenderer<LogEntry> {

        private final Font text = font(12.5f);
        private final Font bold = text.deriveFont(Font.BOLD);
        private final Font small = font(11f);
        private final Font mono = new Font(Font.MONOSPACED, Font.PLAIN, 11);
        private LogEntry row;
        private boolean selected;
        private int index;

        @Override
        public Component getListCellRendererComponent(JList<? extends LogEntry> l, LogEntry value, int i,
                                                      boolean isSelected, boolean focus) {
            row = value;
            selected = isSelected;
            index = i;
            return this;
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0;
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int w = getWidth();
            int h = getHeight();
            LogEntry e = row;
            Color bg = selected ? Theme.SELECTION
                    : e.level() == LogEntry.Level.ERROR ? ERROR_BG
                    : e.level() == LogEntry.Level.WARN ? WARN_BG
                    : e.category() == LogCategory.SUMMARY ? SUMMARY_BG
                    : index % 2 == 1 ? ROW_ALT : Theme.BACKGROUND;
            g.setColor(bg);
            g.fillRect(0, 0, w, h);
            // the subject's colour as a strip on the left
            g.setColor(e.problem() ? (e.level() == LogEntry.Level.ERROR ? ERROR_FG : WARN_FG) : e.category().color());
            g.fillRect(0, 2, 3, h - 4);
            int base = h / 2 + 4;
            int x = 10;

            g.setFont(mono);
            g.setColor(Theme.DIM);
            String t = time(e.first(), true);
            g.drawString(t, x, base);
            x += Math.max(62, g.getFontMetrics().stringWidth(t) + 8);

            if (fixedAccount == null && e.account() != null) {
                x = pill(g, e.account(), x, accountColor(e.account()), h, false) + 6;
            }
            g.setFont(small);
            g.setColor(e.category().color());
            String cat = e.category().label();
            g.drawString(cat, x, base);
            x += Math.max(96, g.getFontMetrics().stringWidth(cat) + 12);

            if (e.count() > 1) {
                x = pill(g, "×" + e.count(), x, new Color(0x94a3b8), h, true) + 6;
            }
            if (e.problem()) {
                g.setFont(bold);
                g.setColor(e.level() == LogEntry.Level.ERROR ? ERROR_FG : WARN_FG);
                String mark = e.level() == LogEntry.Level.ERROR ? "✖" : "▲";
                g.drawString(mark, x, base);
                x += g.getFontMetrics().stringWidth(mark) + 6;
            }

            String right = "";
            if (e.count() > 1) {
                right = "до " + time(e.last(), false);
            }
            if (PREFS.getBoolean("logger", false)) {
                right = (right.isEmpty() ? "" : right + "  ·  ") + e.logger();
            }
            int rightW = 0;
            if (!right.isEmpty()) {
                g.setFont(small);
                rightW = g.getFontMetrics().stringWidth(right) + 12;
                g.setColor(Theme.DIM);
                g.drawString(right, w - rightW, base);
            }

            Font f = e.important() && mode != Mode.JOURNAL || e.category() == LogCategory.SUMMARY ? bold : text;
            g.setFont(f);
            g.setColor(e.level() == LogEntry.Level.ERROR ? ERROR_FG
                    : e.level() == LogEntry.Level.WARN ? new Color(0xfde68a)
                    : e.important() || e.category() == LogCategory.SUMMARY ? Theme.TEXT : ROUTINE);
            g.drawString(clip(g.getFontMetrics(), e.text(), w - x - rightW - 6), x, base);
        }

        private int pill(Graphics2D g, String s, int x, Color c, int h, boolean dim) {
            g.setFont(small);
            FontMetrics fm = g.getFontMetrics();
            int pw = fm.stringWidth(s) + 12;
            int ph = 16;
            int y = (h - ph) / 2;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), dim ? 40 : 50));
            g.fillRoundRect(x, y, pw, ph, ph, ph);
            g.setColor(c);
            g.drawString(s, x + 6, y + ph - 4);
            return x + pw;
        }
    }

    static String clip(FontMetrics fm, String s, int width) {
        if (width <= 0) {
            return "";
        }
        if (fm.stringWidth(s) <= width) {
            return s;
        }
        int dots = fm.stringWidth("…");
        int lo = 0;
        int hi = s.length();
        while (lo < hi) {
            int mid = (lo + hi + 1) / 2;
            if (fm.stringWidth(s.substring(0, mid)) + dots <= width) {
                lo = mid;
            } else {
                hi = mid - 1;
            }
        }
        return s.substring(0, lo) + "…";
    }

    /** The rows shown, oldest first; found by their {@link LogEntry#seq()}. */
    private static final class Rows extends AbstractListModel<LogEntry> {

        final List<LogEntry> rows = new ArrayList<>();

        @Override
        public int getSize() {
            return rows.size();
        }

        @Override
        public LogEntry getElementAt(int i) {
            return rows.get(i);
        }

        void set(List<LogEntry> all) {
            int before = rows.size();
            rows.clear();
            if (before > 0) {
                fireIntervalRemoved(this, 0, before - 1);
            }
            rows.addAll(all);
            if (!rows.isEmpty()) {
                fireIntervalAdded(this, 0, rows.size() - 1);
            }
        }

        void add(LogEntry e) {
            if (!rows.isEmpty() && rows.getLast().seq() > e.seq()) {
                insert(e);
                return;
            }
            rows.add(e);
            fireIntervalAdded(this, rows.size() - 1, rows.size() - 1);
        }

        void insert(LogEntry e) {
            int i = Collections.binarySearch(rows, e, java.util.Comparator.comparingLong(LogEntry::seq));
            if (i >= 0) {
                return;
            }
            int at = -i - 1;
            rows.add(at, e);
            fireIntervalAdded(this, at, at);
        }

        int indexOf(LogEntry e) {
            int i = Collections.binarySearch(rows, e, java.util.Comparator.comparingLong(LogEntry::seq));
            return i >= 0 ? i : -1;
        }

        void changed(int i) {
            fireContentsChanged(this, i, i);
        }

        void trim(int n) {
            rows.subList(0, n).clear();
            fireIntervalRemoved(this, 0, n - 1);
        }
    }

    private static JComponent separator() {
        JComponent s = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                g.setColor(Theme.BORDER);
                g.fillRect(getWidth() / 2, 3, 1, getHeight() - 6);
            }
        };
        s.setPreferredSize(new Dimension(9, 22));
        return s;
    }

    private static JButton small(String text, String tip) {
        JButton b = new JButton(text);
        b.putClientProperty("JButton.buttonType", "toolBarButton");
        b.setToolTipText(tip);
        b.setFocusable(false);
        return b;
    }

    /**
     * A period in words: «5 мин», «2ч 10м», «1.5ч», «1д 6ч», «90» (minutes); «За всё время», «всё» or empty = 0
     * (no limit); {@code null} when not understood.
     */
    static Long parsePeriod(String text) {
        String t = text == null ? "" : text.strip().toLowerCase(Locale.ROOT);
        if (t.isEmpty() || t.startsWith("за всё") || t.startsWith("за все") || t.equals("всё") || t.equals("все")) {
            return 0L;
        }
        java.util.regex.Matcher m = PERIOD_PART.matcher(t);
        double total = 0;
        int end = 0;
        boolean any = false;
        while (m.find()) {
            if (!t.substring(end, m.start()).isBlank()) {
                return null;
            }
            double v = Double.parseDouble(m.group(1).replace(',', '.'));
            String unit = m.group(2) == null ? "м" : m.group(2);
            double mul = switch (unit) {
                case "сут", "дн", "д", "d" -> 86_400_000;
                case "час", "ч", "h" -> 3_600_000;
                case "сек", "с", "s" -> 1_000;
                default -> 60_000;
            };
            total += v * mul;
            end = m.end();
            any = true;
        }
        if (!any || !t.substring(end).isBlank() || total <= 0) {
            return null;
        }
        return (long) total;
    }

    /** «2 ч 10 мин» for the status line. */
    static String periodText(long ms) {
        long min = Math.round(ms / 60_000.0);
        long d = min / 1440;
        long h = min % 1440 / 60;
        long m = min % 60;
        List<String> parts = new ArrayList<>();
        if (d > 0) {
            parts.add(d + " д");
        }
        if (h > 0) {
            parts.add(h + " ч");
        }
        if (m > 0 || parts.isEmpty()) {
            parts.add(m + " мин");
        }
        return String.join(" ", parts);
    }

    /** ⌘ on a Mac, Ctrl elsewhere (and with no screen, as in tests). */
    private static int menuKey() {
        try {
            return Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();
        } catch (java.awt.HeadlessException e) {
            return InputEvent.META_DOWN_MASK;
        }
    }

    private static javax.swing.Action action(Runnable r) {
        return new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                r.run();
            }
        };
    }

    /** For tests: the rows shown. */
    List<LogEntry> shown() {
        return List.copyOf(model.rows);
    }

    /** For tests: the counts by subject under the other filters. */
    Map<LogCategory, Integer> counts() {
        return new LinkedHashMap<>(counts);
    }

    void setMode(Mode m) {
        mode = m;
        modeButtons.get(m).setSelected(true);
        rebuild();
    }

    void setQuery(String q) {
        query = q.toLowerCase(Locale.ROOT);
        rebuild();
    }

    void drainNow() {
        drain();
    }
}
