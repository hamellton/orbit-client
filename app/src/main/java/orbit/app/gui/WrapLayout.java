package orbit.app.gui;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * A {@link FlowLayout} whose preferred height grows when its components wrap onto more rows, so a
 * toolbar stays whole in a narrow window instead of cutting buttons off.
 */
final class WrapLayout extends FlowLayout {

    WrapLayout(int align, int hgap, int vgap) {
        super(align, hgap, vgap);
    }

    @Override
    public Dimension preferredLayoutSize(Container target) {
        return size(target, true);
    }

    @Override
    public Dimension minimumLayoutSize(Container target) {
        Dimension d = size(target, false);
        d.width -= getHgap() + 1;
        return d;
    }

    private Dimension size(Container target, boolean preferred) {
        synchronized (target.getTreeLock()) {
            int targetWidth = target.getSize().width;
            Container c = target;
            while (c.getSize().width == 0 && c.getParent() != null) {
                c = c.getParent();
            }
            targetWidth = c.getSize().width;
            if (targetWidth == 0) {
                targetWidth = Integer.MAX_VALUE;
            }
            Insets in = target.getInsets();
            int max = targetWidth - (in.left + in.right + getHgap() * 2);
            Dimension out = new Dimension(0, 0);
            int rowWidth = 0;
            int rowHeight = 0;
            for (Component m : target.getComponents()) {
                if (!m.isVisible()) {
                    continue;
                }
                Dimension d = preferred ? m.getPreferredSize() : m.getMinimumSize();
                if (rowWidth + d.width > max) {
                    out.width = Math.max(out.width, rowWidth);
                    out.height += rowHeight + getVgap();
                    rowWidth = 0;
                    rowHeight = 0;
                }
                if (rowWidth != 0) {
                    rowWidth += getHgap();
                }
                rowWidth += d.width;
                rowHeight = Math.max(rowHeight, d.height);
            }
            out.width = Math.max(out.width, rowWidth);
            out.height += rowHeight;
            out.width += in.left + in.right + getHgap() * 2;
            out.height += in.top + in.bottom + getVgap() * 2;
            Container scroll = SwingUtilities.getAncestorOfClass(JScrollPane.class, target);
            if (scroll != null && target.isValid()) {
                out.width -= getHgap() + 1;
            }
            return out;
        }
    }
}
