package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.table.DefaultTableModel;
import orbit.app.WorldFeed;
import orbit.world.WorldSnapshot;

/**
 * «Добавить из игры…»: players the running accounts see now or saw in this run, their friends, clan
 * members and group, the accounts themselves; the ticked ones (or their clans as {@code [TAG]}) are
 * appended to a name list of the config.
 */
final class PlayerPicker {

    /** One row: a name or a {@code [TAG]}, its clan and where it came from. */
    record Row(String entry, String clan, String source) {
    }

    private PlayerPicker() {
    }

    /** The list with the button under it. */
    static JComponent withButton(JTextArea list) {
        JButton add = new JButton("Добавить из игры…");
        add.setToolTipText("Игроки рядом и встреченные за сеанс, друзья, соклановцы, группа и свои аккаунты");
        add.addActionListener(e -> pick(list));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 2));
        buttons.add(add);
        JPanel p = new JPanel(new BorderLayout());
        p.add(new JScrollPane(list), BorderLayout.CENTER);
        p.add(buttons, BorderLayout.SOUTH);
        return p;
    }

    /** Who the running accounts know of, each name once (the first source wins). */
    static List<Row> rows(List<WorldFeed> feeds, long nowMillis) {
        Map<String, Row> out = new LinkedHashMap<>();
        Map<String, Row> clans = new LinkedHashMap<>();
        for (WorldFeed f : feeds) {
            String hero = f.snapshot().hero().name();
            if (hero != null && !hero.isBlank()) {
                put(out, new Row(hero, "", "свой аккаунт (" + f.title() + ")"));
            }
        }
        for (WorldFeed f : feeds) {
            WorldSnapshot w = f.snapshot();
            String who = f.title();
            w.group().members().forEach(m -> put(out, new Row(m.name(), "", "группа (" + who + ")")));
            for (WorldSnapshot.Ship s : w.ships().values()) {
                if (s.kind() == WorldSnapshot.ShipKind.PLAYER && s.id() != w.hero().id()) {
                    int d = (int) s.distanceTo(w.hero().x(), w.hero().y());
                    put(out, new Row(s.name(), tag(s.clanTag()), "рядом с " + who + ", " + d));
                    clan(clans, s.clanTag(), who);
                }
            }
            for (WorldSnapshot.Contact c : w.contacts()) {
                String kind = c.friend() ? "друг" : c.clanMember() ? "соклановец" : null;
                if (kind != null) {
                    put(out, new Row(c.name(), tag(c.clanTag()), kind + " (" + who + ")" + (c.online() ? ", в сети" : "")));
                }
            }
        }
        for (WorldFeed f : feeds) {
            for (WorldFeed.SeenPlayer p : f.seenPlayers()) {
                long min = Math.max(0, (nowMillis - p.atMillis()) / 60_000);
                put(out, new Row(p.name(), tag(p.clanTag()), "встречен " + (min == 0 ? "только что" : min + " мин назад")
                        + " (" + f.title() + ")"));
                clan(clans, p.clanTag(), f.title());
            }
        }
        List<Row> all = new ArrayList<>(out.values());
        all.addAll(clans.values());
        return all;
    }

    private static void put(Map<String, Row> out, Row r) {
        if (r.entry() != null && !r.entry().isBlank()) {
            out.putIfAbsent(r.entry().toLowerCase(Locale.ROOT), r);
        }
    }

    private static void clan(Map<String, Row> clans, String tag, String who) {
        if (tag != null && !tag.isBlank()) {
            String t = "[" + tag + "]";
            clans.putIfAbsent(t.toLowerCase(Locale.ROOT), new Row(t, t, "весь клан (" + who + ")"));
        }
    }

    private static String tag(String t) {
        return t == null || t.isBlank() ? "" : "[" + t + "]";
    }

    /** {@code text} with the new entries appended, none twice (any case). */
    static String append(String text, List<String> entries) {
        Set<String> have = new java.util.HashSet<>();
        SafetySection.names(text).forEach(n -> have.add(n.toLowerCase(Locale.ROOT)));
        StringBuilder sb = new StringBuilder(text.strip());
        for (String e : entries) {
            if (have.add(e.toLowerCase(Locale.ROOT))) {
                sb.append(sb.isEmpty() ? "" : "\n").append(e);
            }
        }
        return sb.toString();
    }

    private static void pick(JTextArea list) {
        List<Row> rows = rows(WorldFeed.live().stream().filter(f -> f.account() != null).toList(),
                System.currentTimeMillis());
        DefaultTableModel model = new DefaultTableModel(new Object[] {"", "Игрок или клан", "Клан", "Откуда"}, 0) {
            @Override
            public Class<?> getColumnClass(int c) {
                return c == 0 ? Boolean.class : String.class;
            }

            @Override
            public boolean isCellEditable(int r, int c) {
                return c == 0;
            }
        };
        rows.forEach(r -> model.addRow(new Object[] {false, r.entry(), r.clan(), r.source()}));
        JTable table = new JTable(model);
        table.setAutoCreateRowSorter(true);
        table.getColumnModel().getColumn(0).setMaxWidth(30);
        table.getColumnModel().getColumn(1).setPreferredWidth(180);
        table.getColumnModel().getColumn(3).setPreferredWidth(300);
        JDialog d = new JDialog(SwingUtilities.getWindowAncestor(list), "Добавить игроков из игры",
                java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        JPanel p = new JPanel(new BorderLayout(0, 8));
        p.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        p.add(new JLabel(rows.isEmpty()
                ? "Никого нет: игроки появятся, когда запущенный в этой программе аккаунт их увидит."
                : "Отметьте игроков или кланы — они добавятся в список."), BorderLayout.NORTH);
        p.add(new JScrollPane(table), BorderLayout.CENTER);
        JButton ok = new JButton("Добавить");
        JButton cancel = new JButton("Отмена");
        ok.addActionListener(e -> {
            List<String> chosen = new ArrayList<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                if (Boolean.TRUE.equals(model.getValueAt(i, 0))) {
                    chosen.add((String) model.getValueAt(i, 1));
                }
            }
            list.setText(append(list.getText(), chosen));
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        p.add(buttons, BorderLayout.SOUTH);
        d.setContentPane(p);
        d.setSize(720, 420);
        d.setLocationRelativeTo(list);
        d.setVisible(true);
    }
}
