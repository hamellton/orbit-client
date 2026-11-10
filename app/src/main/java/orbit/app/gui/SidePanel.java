package orbit.app.gui;

import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.List;
import java.util.function.Supplier;
import javax.swing.JPanel;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import orbit.app.WorldFeed;

/**
 * The account's cards next to (or under) the map, in as many columns as fit: one in a side column,
 * two or three under the map of a narrow window; each card goes to the shortest column.
 */
final class SidePanel extends JPanel implements Scrollable {

    static final int GAP = 10;
    static final int MIN_COLUMN = 270;

    private final WorldFeed feed;
    private final Supplier<String> config;
    private final Hud hud;
    private int contentHeight = 200;

    /** Where each card was drawn last, for the right-click menu. */
    private volatile java.util.Map<java.awt.Rectangle, ViewSettings.Block> cardsAt = java.util.Map.of();

    SidePanel(WorldFeed feed, Supplier<String> config) {
        this.feed = feed;
        this.config = config;
        BlockMenu.attach(this, p -> blockAt(cardsAt, p));
        setToolTipText("Правый клик по карточке — перенести на карту или скрыть");
        Font f = javax.swing.UIManager.getFont("Label.font");
        this.hud = new Hud(f != null ? f : getFont());
        setOpaque(true);
        setBackground(MainWindow.SURFACE);
    }

    int columns(int width) {
        return Math.max(1, Math.min(3, (width + GAP) / (MIN_COLUMN + GAP)));
    }

    /** The height the cards take at a width (to size the panel under the map). */
    int contentHeight(int width) {
        List<Hud.Card> cards = hud.cards(feed.snapshot(), feed, config.get(), ViewSettings.shared(), ViewSettings.Place.SIDE);
        int n = columns(width);
        int cw = (width - (n - 1) * GAP) / n;
        int[] col = new int[n];
        for (Hud.Card c : cards) {
            int i = shortest(col);
            col[i] += c.height(cw) + GAP;
        }
        int max = 0;
        for (int h : col) {
            max = Math.max(max, h);
        }
        return Math.max(0, max - GAP);
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            int width = getWidth();
            int n = columns(width);
            int cw = (width - (n - 1) * GAP) / n;
            int[] col = new int[n];
            java.util.Map<java.awt.Rectangle, ViewSettings.Block> placed = new java.util.LinkedHashMap<>();
            for (var e : hud.blocks(feed.snapshot(), feed, config.get()).entrySet()) {
                if (ViewSettings.shared().place(e.getKey()) != ViewSettings.Place.SIDE) {
                    continue;
                }
                Hud.Card c = e.getValue();
                int i = shortest(col);
                int h = c.height(cw);
                c.paint(g, i * (cw + GAP), col[i], cw);
                placed.put(new java.awt.Rectangle(i * (cw + GAP), col[i], cw, h), e.getKey());
                col[i] += h + GAP;
            }
            cardsAt = placed;
            int max = 0;
            for (int h : col) {
                max = Math.max(max, h);
            }
            if (Math.abs(max - GAP - contentHeight) > 2) {
                contentHeight = Math.max(0, max - GAP);
                revalidate(); // the scroll pane learns the new height
            }
        } finally {
            g.dispose();
        }
    }

    static ViewSettings.Block blockAt(java.util.Map<java.awt.Rectangle, ViewSettings.Block> at, java.awt.Point p) {
        for (var e : at.entrySet()) {
            if (e.getKey().contains(p)) {
                return e.getValue();
            }
        }
        return null;
    }

    private static int shortest(int[] col) {
        int best = 0;
        for (int i = 1; i < col.length; i++) {
            if (col[i] < col[best]) {
                best = i;
            }
        }
        return best;
    }

    @Override
    public Dimension getPreferredSize() {
        int width = getWidth() > 0 ? getWidth() : MIN_COLUMN;
        contentHeight = contentHeight(width);
        return new Dimension(MIN_COLUMN, contentHeight);
    }

    @Override
    public Dimension getPreferredScrollableViewportSize() {
        return getPreferredSize();
    }

    @Override
    public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) {
        return 24;
    }

    @Override
    public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) {
        return orientation == SwingConstants.VERTICAL ? visible.height - 24 : visible.width;
    }

    @Override
    public boolean getScrollableTracksViewportWidth() {
        return true;
    }

    @Override
    public boolean getScrollableTracksViewportHeight() {
        return false;
    }
}
