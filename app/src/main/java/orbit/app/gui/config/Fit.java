package orbit.app.gui.config;

import java.awt.BorderLayout;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Rectangle;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JViewport;
import javax.swing.Scrollable;

/**
 * A tab that fits any window: when the window has room, the content takes exactly its size (a table in it
 * gets all the spare height and scrolls by itself); when it is too small, the whole tab scrolls instead of
 * squeezing anything below its minimum.
 */
final class Fit extends JPanel implements Scrollable {

    private final JComponent content;

    private Fit(JComponent content) {
        super(new BorderLayout());
        this.content = content;
        add(content, BorderLayout.CENTER);
    }

    static JScrollPane of(JComponent content) {
        JScrollPane s = new JScrollPane(new Fit(content));
        s.setBorder(null);
        s.getVerticalScrollBar().setUnitIncrement(16);
        s.getHorizontalScrollBar().setUnitIncrement(16);
        return s;
    }

    private Dimension viewport() {
        Container p = getParent();
        return p instanceof JViewport v ? v.getExtentSize() : getSize();
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return viewport().width >= content.getMinimumSize().width;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return viewport().height >= content.getMinimumSize().height;
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 16;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return Math.max(16, (orientation == javax.swing.SwingConstants.VERTICAL ? visible.height : visible.width) - 32);
    }
}
