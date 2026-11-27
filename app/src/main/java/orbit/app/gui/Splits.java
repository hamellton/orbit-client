package orbit.app.gui;

import java.awt.Component;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.prefs.Preferences;
import javax.swing.JComponent;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;
import javax.swing.plaf.basic.BasicSplitPaneUI;

/**
 * A split that is easy to use (folding arrows that are handy and friendly): the divider says
 * what it does, a double click folds the second part away and brings it back, the small arrows stay for one click, and
 * the place is remembered between runs.
 */
final class Splits {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Splits.class).node("splits");

    private Splits() {
    }

    /** The keys already set up in this run (a part folded at start is folded only at the program's start). */
    private static final java.util.Set<String> SEEN = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * @param key  where its place is kept
     * @param what the second part, for the hint (e.g. the log, the form)
     */
    static void setup(JSplitPane split, String key, String what) {
        setup(split, key, what, false);
    }

    /**
     * As above; {@code foldedAtStart}: the second part starts folded each time the program starts (the logs, the user's
     * wish 2026-11-27), opened by the arrows or a double click and kept so for the rest of the run.
     */
    static void setup(JSplitPane split, String key, String what, boolean foldedAtStart) {
        if (foldedAtStart && SEEN.add(key)) {
            PREFS.putBoolean(key + ".folded", true);
        }
        split.setOneTouchExpandable(true);
        if (!(split.getUI() instanceof BasicSplitPaneUI ui)) {
            return;
        }
        Component divider = ui.getDivider();
        if (divider instanceof JComponent d) {
            d.setToolTipText("Потяните, чтобы изменить размер · двойной щелчок — свернуть или вернуть " + what);
        }
        divider.setCursor(Cursor.getPredefinedCursor(split.getOrientation() == JSplitPane.VERTICAL_SPLIT
                ? Cursor.N_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR));
        int[] before = {-1};
        divider.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() != 2 || !SwingUtilities.isLeftMouseButton(e)) {
                    return;
                }
                int max = split.getMaximumDividerLocation();
                int now = split.getDividerLocation();
                if (now >= max - 4) {
                    split.setDividerLocation(before[0] > 0 && before[0] < max - 4 ? before[0] : max * 2 / 3);
                } else {
                    before[0] = now;
                    split.setDividerLocation(max); // the second part folded away
                }
            }
        });
        // the place of the last run, once the split has its size; a folded part stays folded (live 2026-11-27: the log
        // folded on «Все», an account opened and closed, the overview made anew — the log was back: the saved place was
        // the old maximum, a pixel past the new one, and the first layout wrote its own place over it)
        int saved = PREFS.getInt(key, -1);
        boolean folded = PREFS.getBoolean(key + ".folded", false);
        boolean[] ready = {saved <= 0 && !folded};
        if (!ready[0]) {
            // once shown with a size: a split made anew in a window already open (an overview rebuilt after an account's tab
            // was closed) is showing before its first layout gives it a height, and no hierarchy event follows that layout —
            // so the size's arrival counts too (live 2026-11-28: the group's log came back open)
            boolean[] applied = {false};
            Runnable apply = () -> {
                if (applied[0] || !split.isShowing() || split.getHeight() <= 0) {
                    return;
                }
                applied[0] = true;
                SwingUtilities.invokeLater(() -> {
                    int max = split.getMaximumDividerLocation();
                    if (folded) {
                        // opened again (arrow or double click) to the size it had when open last
                        int open = saved > 0 && saved < max - 4 ? saved : max * 2 / 3;
                        before[0] = open;
                        split.setDividerLocation(max);
                        split.setLastDividerLocation(open);
                    } else if (saved <= max) {
                        split.setDividerLocation(saved);
                    }
                    ready[0] = true;
                });
            };
            split.addHierarchyListener(e -> apply.run());
            split.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    apply.run();
                }

                @Override
                public void componentShown(java.awt.event.ComponentEvent e) {
                    apply.run();
                }
            });
        }
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (ready[0] && split.isShowing()) {
                int now = split.getDividerLocation();
                boolean isFolded = now >= split.getMaximumDividerLocation() - 4;
                if (!isFolded) {
                    PREFS.putInt(key, now); // the open size, to open to again
                }
                PREFS.putBoolean(key + ".folded", isFolded);
            }
        });
    }
}
