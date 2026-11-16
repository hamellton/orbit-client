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
 * A split that is easy to use (the user, 2026-11-16: «стрелки для свёртывания… удобными и юзерфрендли»): the divider says
 * what it does, a double click folds the second part away and brings it back, the small arrows stay for one click, and
 * the place is remembered between runs.
 */
final class Splits {

    private static final Preferences PREFS = Preferences.userNodeForPackage(Splits.class).node("splits");

    private Splits() {
    }

    /**
     * @param key  where its place is kept
     * @param what the second part, for the hint («журнал», «форму»)
     */
    static void setup(JSplitPane split, String key, String what) {
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
        // the place of the last run, once the split has its size
        int saved = PREFS.getInt(key, -1);
        if (saved > 0) {
            split.addHierarchyListener(new java.awt.event.HierarchyListener() {
                @Override
                public void hierarchyChanged(java.awt.event.HierarchyEvent e) {
                    if (split.isShowing() && split.getHeight() > 0) {
                        split.removeHierarchyListener(this);
                        SwingUtilities.invokeLater(() -> {
                            int max = split.getMaximumDividerLocation();
                            if (saved <= max) {
                                split.setDividerLocation(saved);
                            }
                        });
                    }
                }
            });
        }
        split.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
            if (split.isShowing()) {
                PREFS.putInt(key, split.getDividerLocation());
            }
        });
    }
}
