package orbit.app.gui.config;

import java.awt.Component;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import javax.swing.AbstractCellEditor;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;
import javax.swing.table.TableCellEditor;
import orbit.config.BotConfig;

/**
 * The «Флаги» cell: a click opens the flags as ticks with what each does; the popup stays open while ticking,
 * the cell takes the ticks when it closes. Flags the bot does not know (imported) are kept as they were.
 */
final class FlagsEditor extends AbstractCellEditor implements TableCellEditor {

    /** What a flag does, in the user's words; the ones the bot does not act on yet say so. */
    static String describe(BotConfig.NpcFlag f) {
        return switch (f) {
            case IGNORE_OWNERSHIP -> "бить, даже если его начал другой игрок";
            case IGNORE_ATTACKED -> "бить, даже если его бьют другие";
            case AGGRESSIVE_FOLLOW -> "догонять, когда убегает";
            case NO_CIRCLE -> "не кружить вокруг";
            case KAMIKAZE -> "камикадзе питомца рядом с ним";
            case PASSIVE -> "не стреляет первым — пока не работает";
            case IGNORE_BOXES -> "не собирать его ящики (30 с у места, где он погиб)";
            case ATTACK_SECOND -> "второй целью в группе — пока не работает";
            case PET_LOCATOR -> "искать пеленгатором питомца, когда его не видно";
            case USE_EMP -> "EMP-01 раз за бой, когда он в радиусе (чужие теряют прицел)";
            case USE_ISH -> "ISH-01, когда HP корабля ниже порога (вкладка «Убивать»)";
            case HP_LINK -> "питомец в режиме HP Link, пока бьём его";
            case USE_LEECH -> "техника «Энергетическая пиявка», когда готова";
            case USE_ABILITY -> "способность корабля (Venom, Sentinel, Solace, Spectrum…), когда готова";
        };
    }

    private final JButton button = new JButton();
    private final JPopupMenu popup = new JPopupMenu();
    private final List<JCheckBox> boxes = new ArrayList<>();
    private Set<String> others = Set.of();
    private String value = "";

    FlagsEditor() {
        button.setHorizontalAlignment(JButton.LEFT);
        button.setBorderPainted(false);
        JPanel list = new JPanel();
        list.setLayout(new BoxLayout(list, BoxLayout.Y_AXIS));
        list.setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));
        for (BotConfig.NpcFlag f : BotConfig.NpcFlag.values()) {
            JCheckBox b = new JCheckBox("<html><b>" + f.name() + "</b> — " + describe(f) + "</html>");
            b.setActionCommand(f.name());
            boxes.add(b);
            list.add(b);
        }
        popup.add(list); // a panel, not menu items: ticking does not close the popup
        popup.addPopupMenuListener(new PopupMenuListener() {
            @Override
            public void popupMenuWillBecomeVisible(PopupMenuEvent e) {
            }

            @Override
            public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
                SwingUtilities.invokeLater(FlagsEditor.this::stopCellEditing);
            }

            @Override
            public void popupMenuCanceled(PopupMenuEvent e) {
            }
        });
        button.addActionListener(e -> popup.show(button, 0, button.getHeight()));
    }

    @Override
    public Component getTableCellEditorComponent(JTable table, Object v, boolean selected, int row, int column) {
        value = v == null ? "" : String.valueOf(v);
        Set<String> now = parse(value);
        Set<String> known = Arrays.stream(BotConfig.NpcFlag.values()).map(Enum::name).collect(Collectors.toSet());
        others = now.stream().filter(s -> !known.contains(s)).collect(Collectors.toCollection(LinkedHashSet::new));
        for (JCheckBox b : boxes) {
            b.setSelected(now.contains(b.getActionCommand()));
        }
        button.setText(value.isEmpty() ? "выбрать…" : value);
        SwingUtilities.invokeLater(() -> {
            if (button.isShowing()) {
                popup.show(button, 0, button.getHeight());
            }
        });
        return button;
    }

    @Override
    public Object getCellEditorValue() {
        Set<String> out = new LinkedHashSet<>();
        for (JCheckBox b : boxes) {
            if (b.isSelected()) {
                out.add(b.getActionCommand());
            }
        }
        out.addAll(others);
        return String.join(", ", out);
    }

    @Override
    public boolean stopCellEditing() {
        popup.setVisible(false);
        return super.stopCellEditing();
    }

    static Set<String> parse(String text) {
        return Arrays.stream(text.split("[,; ]+")).map(String::strip).filter(s -> !s.isEmpty()).map(String::toUpperCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
