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
        // no tooltip over the cards (the user, 2026-11-19: «надоело всплывающее окно»): the card under the mouse shows
        // its «⋯» button instead — a left click on it opens the same menu as the right click
        java.awt.event.MouseAdapter hover = new java.awt.event.MouseAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                hovered(e.getPoint());
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hovered(null);
            }

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                java.awt.Rectangle r = hoverCard;
                if (javax.swing.SwingUtilities.isLeftMouseButton(e) && r != null && menuButton(r).contains(e.getPoint())) {
                    ViewSettings.Block b = blockAt(cardsAt, e.getPoint());
                    if (b != null) {
                        java.awt.Rectangle m = menuButton(r);
                        BlockMenu.forBlock(b).show(SidePanel.this, m.x, m.y + m.height);
                    }
                }
            }
        };
        addMouseListener(hover);
        addMouseMotionListener(hover);
        Font f = javax.swing.UIManager.getFont("Label.font");
        this.hud = new Hud(f != null ? f : getFont());
        hud.menuRoom = MENU_ROOM; // the headings leave the «⋯» button its place
        setOpaque(true);
        setBackground(MainWindow.SURFACE);
        // a block moved («Сбоку», «На карте», «Скрыть»): laid out anew at once, no place kept for it (the steady places
        // are for data that changes, not for the user's choice)
        ViewSettings.shared().onChange(() -> {
            synchronized (this) {
                slots.clear();
            }
            javax.swing.SwingUtilities.invokeLater(() -> {
                revalidate();
                repaint();
            });
        });
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
        // SPLIT: the live cards (the fight's, the group) in the last column of their own when there are two or more,
        // else under the steady ones — their growing and shrinking moves nothing else (the user, 2026-11-19)
        boolean split = ViewSettings.shared().cardLayout() == ViewSettings.CardLayout.SPLIT;
        List<ViewSettings.Block> order = new java.util.ArrayList<>(List.of(ViewSettings.Block.values()));
        if (split) {
            order.sort(java.util.Comparator.comparingInt(b -> b.live() ? 1 : 0));
        }
        for (ViewSettings.Block b : order) {
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
            if (split && n >= 2) {
                if (b.live()) {
                    sl.column = n - 1;
                } else if (sl.column < 0 || sl.column >= n - 1) {
                    sl.column = shortest(java.util.Arrays.copyOf(col, n - 1));
                }
            } else if (sl.column < 0 || sl.column >= n) {
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
            if (ViewSettings.shared().cardLayout() == ViewSettings.CardLayout.SPLIT && n >= 2
                    && placed.keySet().stream().noneMatch(r -> r.x >= (n - 1) * (cw + GAP))) {
                paintLiveHint(g, (n - 1) * (cw + GAP), cw);
            }
            cardsAt = placed;
            java.awt.Rectangle hc = hoverCard;
            for (java.awt.Rectangle r : placed.keySet()) {
                paintMenu(g, r, r.equals(hc));
            }
            if (Math.abs(max - GAP - contentHeight) > 2) {
                contentHeight = Math.max(0, max - GAP);
                revalidate(); // the scroll pane learns the new height
            }
        } finally {
            g.dispose();
        }
    }

    /** The live column with nothing in it: a quiet dashed place saying what comes there, not a gap that looks broken. */
    private void paintLiveHint(Graphics2D g, int x, int w) {
        java.awt.Stroke was = g.getStroke();
        g.setColor(new java.awt.Color(0x2c3446));
        g.setStroke(new java.awt.BasicStroke(1f, java.awt.BasicStroke.CAP_BUTT, java.awt.BasicStroke.JOIN_ROUND, 1f,
                new float[] {4f, 4f}, 0f));
        g.draw(new java.awt.geom.RoundRectangle2D.Double(x + 0.5, 0.5, w - 1, 52, 10, 10));
        g.setStroke(was);
        g.setColor(Hud.DIM);
        g.setFont(getFont().deriveFont(12f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String a = "Боя нет";
        String b = "цель, атакующие, бустеры, группа — здесь";
        g.drawString(a, x + 12, 8 + fm.getAscent());
        g.drawString(fm.stringWidth(b) <= w - 24 ? b : "бой — здесь", x + 12, 8 + fm.getAscent() + fm.getHeight() + 2);
    }

    /** The card under the mouse ({@code null} none): its frame lit and its «⋯» button shown. */
    private volatile java.awt.Rectangle hoverCard;
    private volatile boolean hoverButton;

    private void hovered(java.awt.Point p) {
        java.awt.Rectangle card = null;
        if (p != null) {
            for (java.awt.Rectangle r : cardsAt.keySet()) {
                if (r.contains(p)) {
                    card = r;
                    break;
                }
            }
        }
        boolean onButton = card != null && menuButton(card).contains(p);
        if (!java.util.Objects.equals(card, hoverCard) || onButton != hoverButton) {
            hoverCard = card;
            hoverButton = onButton;
            setCursor(onButton ? java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR)
                    : java.awt.Cursor.getDefaultCursor());
            repaint();
        }
    }

    /** The heading's room for the button. */
    static final int MENU_ROOM = 24;

    /** The card's «⋯» button: the right end of its heading. */
    static java.awt.Rectangle menuButton(java.awt.Rectangle card) {
        return new java.awt.Rectangle(card.x + card.width - 30, card.y + 7, 22, 16);
    }

    /** The «⋯» button of every card, dim; the card under the mouse — its frame lit, its button bright. */
    private void paintMenu(Graphics2D g, java.awt.Rectangle card, boolean hover) {
        if (hover) {
            g.setColor(new java.awt.Color(0x6b7a99));
            g.draw(new java.awt.geom.RoundRectangle2D.Double(card.x + 0.5, card.y + 0.5, card.width - 1, card.height - 1, 10,
                    10));
        }
        java.awt.Rectangle b = menuButton(card);
        if (!hover) {
            g.setColor(new java.awt.Color(0x5b6578));
            int cy = b.y + b.height / 2;
            for (int i = -1; i <= 1; i++) {
                g.fill(new java.awt.geom.Ellipse2D.Double(b.x + b.width / 2.0 + i * 5 - 1.4, cy - 1.4, 2.8, 2.8));
            }
            return;
        }
        g.setColor(hoverButton ? new java.awt.Color(0x3a4658) : new java.awt.Color(0x262f3d));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(b.x, b.y, b.width, b.height, 8, 8));
        g.setColor(hoverButton ? new java.awt.Color(0x8b9bb8) : new java.awt.Color(0x4a5568));
        g.draw(new java.awt.geom.RoundRectangle2D.Double(b.x + 0.5, b.y + 0.5, b.width - 1, b.height - 1, 8, 8));
        g.setColor(hoverButton ? java.awt.Color.WHITE : new java.awt.Color(0xc4cbd8));
        int cy = b.y + b.height / 2;
        for (int i = -1; i <= 1; i++) {
            g.fill(new java.awt.geom.Ellipse2D.Double(b.x + b.width / 2.0 + i * 5 - 1.6, cy - 1.6, 3.2, 3.2));
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
