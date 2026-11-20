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
        attach(c, at, p -> java.util.List.of());
    }

    /** …and {@code extra} items for that point (the bot's orders) above the window's items off the cards. */
    static void attach(JComponent c, Function<Point, ViewSettings.Block> at,
                       Function<Point, java.util.List<JComponent>> extra) {
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
                JPopupMenu menu = b != null ? forBlock(b) : all();
                if (b == null) {
                    java.util.List<JComponent> items = extra.apply(e.getPoint());
                    if (!items.isEmpty()) {
                        menu.insert(new JPopupMenu.Separator(), 0);
                        for (int i = items.size() - 1; i >= 0; i--) {
                            menu.insert(items.get(i), 0);
                        }
                    }
                }
                menu.show(c, e.getX(), e.getY());
            }
        });
    }

    /** The target block: beside the map or hidden. */
    static JPopupMenu forBlock(ViewSettings.Block b) {
        JPopupMenu menu = new JPopupMenu(b.label);
        JMenuItem title = new JMenuItem(b.label);
        title.setEnabled(false);
        menu.add(title);
        menu.addSeparator();
        ButtonGroup choice = new ButtonGroup();
        ViewSettings v = ViewSettings.shared();
        for (ViewSettings.Place p : ViewSettings.Place.choices()) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(p.label, v.place(b) == p);
            r.addActionListener(e -> v.set(b, p));
            choice.add(r);
            menu.add(r);
        }
        if (b == ViewSettings.Block.GROUP || b == ViewSettings.Block.TARGET) {
            menu.addSeparator();
            JMenu numbers = new JMenu("HP и щит показывать");
            ButtonGroup style = new ButtonGroup();
            for (ViewSettings.Numbers n : ViewSettings.Numbers.values()) {
                JRadioButtonMenuItem r = new JRadioButtonMenuItem(n.label, v.numbers() == n);
                r.addActionListener(e -> v.setNumbers(n));
                style.add(r);
                numbers.add(r);
            }
            menu.add(numbers);
        }
        menu.addSeparator();
        menu.add(allMenu());
        JMenu window = new JMenu("Окно");
        for (JMenuItem i : windowItems()) {
            window.add(i);
        }
        menu.add(window);
        return menu;
    }

    /** Map only, show all, and a check per part of the window. */
    private static JMenuItem[] windowItems() {
        ViewSettings v = ViewSettings.shared();
        java.util.List<JMenuItem> out = new java.util.ArrayList<>();
        JMenuItem only = new JMenuItem("Только карта");
        only.addActionListener(e -> v.mapOnly());
        JMenuItem all = new JMenuItem("Показать всё");
        all.addActionListener(e -> v.showAll());
        out.add(only);
        out.add(all);
        for (ViewSettings.Section s : ViewSettings.Section.values()) {
            javax.swing.JCheckBoxMenuItem c = new javax.swing.JCheckBoxMenuItem(s.label, v.shown(s));
            c.addActionListener(e -> v.show(s, c.isSelected()));
            out.add(c);
        }
        javax.swing.JMenu ground = new javax.swing.JMenu("Фон карты:  " + v.mapBackground().label.toLowerCase());
        ButtonGroup grounds = new ButtonGroup();
        for (ViewSettings.MapBackground b : ViewSettings.MapBackground.values()) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(b.label, v.mapBackground() == b);
            r.addActionListener(e -> v.setMapBackground(b));
            grounds.add(r);
            ground.add(r);
        }
        out.add(ground);
        javax.swing.JMenu cards = new javax.swing.JMenu("Карточки сбоку:  " + (v.cardLayout() == ViewSettings.CardLayout.SPLIT
                ? "живые отдельно" : "как раньше"));
        ButtonGroup layouts = new ButtonGroup();
        for (ViewSettings.CardLayout l : ViewSettings.CardLayout.values()) {
            JRadioButtonMenuItem r = new JRadioButtonMenuItem(l.label, v.cardLayout() == l);
            r.addActionListener(e -> v.setCardLayout(l));
            layouts.add(r);
            cards.add(r);
        }
        out.add(cards);
        return out.toArray(JMenuItem[]::new);
    }

    /** Every block with its place, and all at once. */
    static JPopupMenu all() {
        ViewSettings v = ViewSettings.shared();
        JPopupMenu menu = new JPopupMenu();
        for (JMenuItem i : windowItems()) {
            menu.add(i);
        }
        menu.addSeparator();
        JMenuItem hint = new JMenuItem("Блоки:");
        hint.setEnabled(false);
        menu.add(hint);
        for (ViewSettings.Block b : ViewSettings.Block.values()) {
            JMenu item = new JMenu(b.label + ":  " + v.place(b).label.toLowerCase());
            ButtonGroup choice = new ButtonGroup();
            for (ViewSettings.Place p : ViewSettings.Place.choices()) {
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
        JMenuItem side = new JMenuItem("Все сбоку");
        side.addActionListener(e -> v.setAll(ViewSettings.Place.SIDE));
        JMenuItem none = new JMenuItem("Скрыть все");
        none.addActionListener(e -> v.setAll(ViewSettings.Place.HIDDEN));
        return new JMenuItem[] {side, none};
    }
}
