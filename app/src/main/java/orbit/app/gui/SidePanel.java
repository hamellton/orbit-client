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
        setToolTipText("Правый клик — меню");
        Font f = javax.swing.UIManager.getFont("Label.font");
        this.hud = new Hud(f != null ? f : getFont());
        setOpaque(true);
        setBackground(MainWindow.SURFACE);
    }

    /**
     * Steady places (the user, 2026-11-16: «прыжки в блоке с карточками очень часто»): a card grows at once but gives
     * its room back only after it stayed smaller for {@link #HOLD_MS}; a card gone keeps its place empty as long; each
     * card keeps its column while the columns stay the same.
     */
    static final long HOLD_MS = 10_000;

    static final class Slot {
        int height;
        long smallerSince;
        long goneSince;
        int column = -1;
    }

    /** A card's new height into its slot: taller at once, shorter only after {@link #HOLD_MS} of it. */
    static void steady(Slot sl, int h, long now) {
        if (h >= sl.height) {
            sl.height = h;
            sl.smallerSince = 0;
        } else if (sl.smallerSince == 0) {
            sl.smallerSince = now;
        } else if (now - sl.smallerSince > HOLD_MS) {
            sl.height = h;
            sl.smallerSince = 0;
        }
    }

    private final java.util.EnumMap<ViewSettings.Block, Slot> slots = new java.util.EnumMap<>(ViewSettings.Block.class);
    private int slotColumns;

    /** One laid-out card: where it goes and its slot's height ({@code card} null for a place kept empty). */
    private record Placed(ViewSettings.Block block, Hud.Card card, int column, int y, int height) {
    }

    /** The cards in their steady places at this width (updates the slots). */
    private synchronized List<Placed> layout(int width, long now) {
        int n = columns(width);
        int cw = (width - (n - 1) * GAP) / n;
        if (n != slotColumns) {
            slotColumns = n;
            slots.values().forEach(sl -> sl.column = -1);
        }
        java.util.Map<ViewSettings.Block, Hud.Card> here = new java.util.EnumMap<>(ViewSettings.Block.class);
        for (var e : hud.blocks(feed.snapshot(), feed, config.get()).entrySet()) {
            if (ViewSettings.shared().place(e.getKey()).side()) {
                here.put(e.getKey(), e.getValue());
            }
        }
        int[] col = new int[n];
        List<Placed> out = new java.util.ArrayList<>();
        for (ViewSettings.Block b : ViewSettings.Block.values()) {
            Hud.Card c = here.get(b);
            Slot sl = slots.get(b);
            if (c == null) {
                if (sl == null) {
                    continue;
                }
                if (sl.goneSince == 0) {
                    sl.goneSince = now;
                }
                if (now - sl.goneSince > HOLD_MS) {
                    slots.remove(b);
                    continue;
                }
            } else {
                if (sl == null) {
                    sl = new Slot();
                    slots.put(b, sl);
                }
                sl.goneSince = 0;
                steady(sl, c.height(cw), now);
            }
            if (sl.column < 0 || sl.column >= n) {
                sl.column = shortest(col);
            }
            int i = sl.column;
            out.add(new Placed(b, c, i, col[i], sl.height));
            col[i] += sl.height + GAP;
        }
        return out;
    }

    int columns(int width) {
        return Math.max(1, Math.min(3, (width + GAP) / (MIN_COLUMN + GAP)));
    }

    /** The height the cards take at a width (to size the panel under the map). */
    int contentHeight(int width) {
        int max = 0;
        for (Placed p : layout(width, System.currentTimeMillis())) {
            max = Math.max(max, p.y() + p.height() + GAP);
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
            java.util.Map<java.awt.Rectangle, ViewSettings.Block> placed = new java.util.LinkedHashMap<>();
            int max = 0;
            for (Placed p : layout(width, System.currentTimeMillis())) {
                int x = p.column() * (cw + GAP);
                if (p.card() != null) {
                    p.card().paint(g, x, p.y(), cw);
                    placed.put(new java.awt.Rectangle(x, p.y(), cw, p.card().height(cw)), p.block());
                }
                max = Math.max(max, p.y() + p.height() + GAP);
            }
            cardsAt = placed;
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
