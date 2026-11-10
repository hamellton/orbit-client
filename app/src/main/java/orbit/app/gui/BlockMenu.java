package orbit.app.gui;

import java.awt.Point;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.function.Function;
import javax.swing.ButtonGroup;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import javax.swing.JRadioButtonMenuItem;

/** The menus that move a block: beside the map, onto it, or away — for one card or for all. */
final class BlockMenu {

    private BlockMenu() {
    }

    /** A right click on a card of {@code c} opens the menu of its block ({@code at} finds it). */
    static void attach(JComponent c, Function<Point, ViewSettings.Block> at) {
        c.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                open(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                open(e);
            }

            private void open(MouseEvent e) {
                if (!e.isPopupTrigger()) {
                    return;
                }
                ViewSettings.Block b = at.apply(e.getPoint());
                (b != null ? forBlock(b) : all()).show(c, e.getX(), e.getY());
            }
        });
    }

    /** «Цель»: Сбоку / На карте / Скрыть. */
    static JPopupMenu forBlock(ViewSettings.Block b) {
        JPopupMenu menu = new JPopupMenu(b.label);
        JMenuItem title = new JMenuItem(b.label);
        title.setEnabled(false);
        menu.add(title);
        menu.addSeparator();
        ButtonGroup choice = new ButtonGroup();
        ViewSettings v = ViewSettings.shared();
        for (ViewSettings.Place p : ViewSettings.Place.values()) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(p.label, v.place(b) == p);
            r.addActionListener(e -> v.set(b, p));
            choice.add(r);
            menu.add(r);
        }
        menu.addSeparator();
        menu.add(allMenu());
        return menu;
    }

    /** Every block with its place, and all at once. */
    static JPopupMenu all() {
        ViewSettings v = ViewSettings.shared();
        JPopupMenu menu = new JPopupMenu();
        JMenuItem hint = new JMenuItem("Правый клик по карточке — её меню");
        hint.setEnabled(false);
        menu.add(hint);
        menu.addSeparator();
        for (ViewSettings.Block b : ViewSettings.Block.values()) {
            JMenu item = new JMenu(b.label + ":  " + v.place(b).label.toLowerCase());
            ButtonGroup choice = new ButtonGroup();
            for (ViewSettings.Place p : ViewSettings.Place.values()) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(p.label, v.place(b) == p);
                r.addActionListener(e -> v.set(b, p));
                choice.add(r);
                item.add(r);
            }
            menu.add(item);
        }
        menu.addSeparator();
        for (JMenuItem i : allItems()) {
            menu.add(i);
        }
        return menu;
    }

    private static JMenu allMenu() {
        JMenu m = new JMenu("Все блоки");
        for (JMenuItem i : allItems()) {
            m.add(i);
        }
        return m;
    }

    private static JMenuItem[] allItems() {
        ViewSettings v = ViewSettings.shared();
        JMenuItem side = new JMenuItem("Все блоки сбоку");
        side.addActionListener(e -> v.setAll(ViewSettings.Place.SIDE));
        JMenuItem map = new JMenuItem("Все блоки на карте");
        map.addActionListener(e -> v.setAll(ViewSettings.Place.MAP));
        JMenuItem both = new JMenuItem("Все блоки везде");
        both.addActionListener(e -> v.setAll(ViewSettings.Place.BOTH));
        JMenuItem none = new JMenuItem("Скрыть все блоки");
        none.addActionListener(e -> v.setAll(ViewSettings.Place.HIDDEN));
        return new JMenuItem[] {side, map, both, none};
    }
}
