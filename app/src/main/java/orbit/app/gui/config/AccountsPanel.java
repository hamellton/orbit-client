package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.DefaultCellEditor;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.DefaultTableModel;
import orbit.config.AccountSettings;
import orbit.config.Schedule;

/**
 * «Аккаунты и расписание»: the config of each account and its timetable (like AstraBot's time table):
 * windows «days, from, to, config»; a window with «перерыв» is a break; outside the windows the bot is offline.
 */
final class AccountsPanel {

    static final String BREAK = "перерыв";

    private final Path file;
    private final Supplier<List<String>> configs;
    private AccountSettings settings;
    private final DefaultListModel<String> names = new DefaultListModel<>();
    private final JList<String> list = new JList<>(names);
    private final JComboBox<String> config = new JComboBox<>();
    private final JCheckBox scheduleOn = new JCheckBox("Работать по расписанию");
    private final JSpinner jitter = Form.number(0, 60, 1);
    private final DefaultTableModel entries = new DefaultTableModel(new Object[] {"Дни", "С", "По", "Конфиг"}, 0);
    private final JTable table = new JTable(entries);
    private final JLabel now = new JLabel(" ");
    private final JComponent view;
    private String current;

    AccountsPanel(Path file, Supplier<List<String>> configs) {
        this.file = file;
        this.configs = configs;
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                select(list.getSelectedValue());
            }
        });
        table.setRowHeight(table.getRowHeight() + 4);

        JButton add = new JButton("Добавить");
        add.addActionListener(e -> {
            String n = JOptionPane.showInputDialog(list, "Имя аккаунта (как в vault list):");
            if (n != null && !n.isBlank() && !names.contains(n.strip())) {
                keep();
                settings = settings.with(n.strip(), new AccountSettings.Entry(null, null));
                names.addElement(n.strip());
                list.setSelectedValue(n.strip(), true);
            }
        });
        JButton remove = new JButton("Убрать");
        remove.addActionListener(e -> {
            String n = list.getSelectedValue();
            if (n != null && JOptionPane.showConfirmDialog(list, "Убрать настройки «" + n + "»?\n"
                    + "Логин и пароль останутся.", "Аккаунты", JOptionPane.OK_CANCEL_OPTION) == JOptionPane.OK_OPTION) {
                settings = settings.with(n, null);
                current = null;
                names.removeElement(n);
            }
        });
        JPanel left = new JPanel(new BorderLayout(0, 6));
        left.add(new JScrollPane(list), BorderLayout.CENTER);
        left.add(Form.bar(add, remove), BorderLayout.SOUTH);

        JButton addRow = new JButton("Добавить окно");
        addRow.addActionListener(e -> entries.addRow(new Object[] {"все", "08:00", "12:00", firstConfig()}));
        JButton removeRow = new JButton("Удалить окно");
        removeRow.addActionListener(e -> {
            int r = table.getSelectedRow();
            if (r >= 0) {
                entries.removeRow(r);
            }
        });
        JButton save = new JButton("Сохранить аккаунты");
        save.addActionListener(e -> save());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new java.awt.Dimension(520, 220));
        scroll.setMinimumSize(new java.awt.Dimension(240, 120));
        JComponent right = new Form()
                .section("Конфиг аккаунта")
                .row("Конфиг", config)
                .section("Расписание")
                .row("", scheduleOn)
                .row("Разброс ±, мин", jitter)
                .note("Действует первое подходящее окно; «перерыв» и вне окон — не в игре.")
                .note("Дни: «все», «Пн-Пт», «Сб, Вс». Через полночь можно: 22:00–02:00.")
                .wide(Form.inline(addRow, removeRow), false)
                .wide(scroll, true)
                .row("", now)
                .wide(Form.inline(save), false)
                .build(true);
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(200);
        view = split;
        reload();
    }

    JComponent view() {
        return view;
    }

    void reload() {
        try {
            settings = AccountSettings.load(file);
        } catch (IOException e) {
            settings = new AccountSettings(null);
            JOptionPane.showMessageDialog(null, "Не прочитать " + file + ": " + e.getMessage());
        }
        names.clear();
        settings.accounts().keySet().forEach(names::addElement);
        current = null;
        refreshConfigs();
        if (!names.isEmpty()) {
            list.setSelectedIndex(0);
        }
    }

    /** The config list changed (created, renamed, deleted). */
    void refreshConfigs() {
        Object sel = config.getSelectedItem();
        config.removeAllItems();
        config.addItem("—");
        configs.get().forEach(config::addItem);
        config.setSelectedItem(sel == null ? "—" : sel);
        JComboBox<String> cell = new JComboBox<>();
        cell.addItem(BREAK);
        configs.get().forEach(cell::addItem);
        table.getColumnModel().getColumn(3).setCellEditor(new DefaultCellEditor(cell));
    }

    private String firstConfig() {
        List<String> c = configs.get();
        return c.isEmpty() ? BREAK : c.getFirst();
    }

    private void select(String name) {
        if (name == null || name.equals(current)) {
            return;
        }
        if (!keep()) {
            list.setSelectedValue(current, true);
            return;
        }
        current = name;
        AccountSettings.Entry e = settings.account(name);
        config.setSelectedItem(e.config() == null ? "—" : e.config());
        scheduleOn.setSelected(e.schedule().enabled());
        Form.set(jitter, e.schedule().jitterMinutes());
        entries.setRowCount(0);
        for (Schedule.Entry s : e.schedule().entries()) {
            entries.addRow(new Object[] {Days.text(s.days()), s.from(), s.to(), s.isBreak() ? BREAK : s.config()});
        }
        showNow();
    }

    /** Puts the form of the current account into {@link #settings}; false (with a message) on bad input. */
    private boolean keep() {
        if (current == null) {
            return true;
        }
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        try {
            List<Schedule.Entry> list = new ArrayList<>();
            for (int r = 0; r < entries.getRowCount(); r++) {
                String cfg = String.valueOf(entries.getValueAt(r, 3));
                list.add(new Schedule.Entry(Days.parse(String.valueOf(entries.getValueAt(r, 0))),
                        time(entries.getValueAt(r, 1)), time(entries.getValueAt(r, 2)), BREAK.equals(cfg) ? null : cfg));
            }
            Object c = config.getSelectedItem();
            settings = settings.with(current, settings.account(current).withConfig("—".equals(c) || c == null ? null : (String) c)
                    .withSchedule(new Schedule(scheduleOn.isSelected(), list, Form.intOf(jitter))));
            return true;
        } catch (RuntimeException e) {
            JOptionPane.showMessageDialog(view, "Расписание «" + current + "»: " + e.getMessage(), "Аккаунты",
                    JOptionPane.WARNING_MESSAGE);
            return false;
        }
    }

    /** «8:00» → «08:00»; refuses what is not a time. */
    private static String time(Object v) {
        String t = String.valueOf(v).strip();
        if (t.matches("\\d:\\d\\d")) {
            t = "0" + t;
        }
        try {
            java.time.LocalTime.parse(t);
        } catch (java.time.DateTimeException e) {
            throw new IllegalArgumentException("время «" + v + "» — нужно ЧЧ:ММ");
        }
        return t;
    }

    private void showNow() {
        if (current == null) {
            now.setText(" ");
            return;
        }
        Schedule.Decision d = settings.account(current).decide(LocalDateTime.now(), current.hashCode());
        String until = d.until() == null ? "" : " до " + d.until().toLocalDate() + " " + d.until().toLocalTime().withNano(0);
        now.setText(d.online() ? "Сейчас: " + d.config() + until : "Сейчас: не в игре" + until);
    }

    boolean save() {
        if (!keep()) {
            return false;
        }
        try {
            settings.save(file);
            showNow();
            return true;
        } catch (IOException e) {
            JOptionPane.showMessageDialog(view, "Не сохранить " + file + ": " + e.getMessage());
            return false;
        }
    }

    /** Unsaved account edits. */
    boolean dirty() {
        if (!keep()) {
            return true;
        }
        try {
            return !settings.equals(AccountSettings.load(file));
        } catch (IOException e) {
            return true;
        }
    }
}
