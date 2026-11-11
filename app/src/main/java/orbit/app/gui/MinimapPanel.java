package orbit.app.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import javax.swing.JPanel;
import orbit.app.WorldFeed;
import orbit.protocol.gen.MapAddPOICommand;
import orbit.protocol.gen.POITypeModule;
import orbit.world.WorldSnapshot;
import orbit.world.WorldSnapshot.Ship;
import orbit.world.WorldSnapshot.ShipKind;
import orbit.world.maps.MapCatalog;

/**
 * Minimap drawn from the world model: map bounds, zones, stations, portals, boxes and ores,
 * ships and the hero with its destination and target.
 */
final class MinimapPanel extends JPanel {

    static final Color BACKGROUND = new Color(0x10141a);
    static final Color BORDER = new Color(0x4a5568);
    static final Color HERO = new Color(0x4fd1ff);
    static final Color NPC = new Color(0xff4d4d);
    static final Color NPC_TAKEN = new Color(0x8a8a8a);
    static final Color ENEMY = new Color(0xff9f1c);
    static final Color FRIEND = new Color(0x5aa9ff);
    static final Color ALLY = new Color(0x4cd964);
    static final Color PET = new Color(0xb0b0b0);
    static final Color BOX = new Color(0xffd84d);
    static final Color ORE = new Color(0x5ee6d0);
    static final Color PORTAL = new Color(0xf2f2f2);
    /** The pet's enemy locator point. */
    static final Color LOCATOR = new Color(0xc77dff);
    static final Color PORTAL_LABEL = new Color(0xa0aec0);
    static final Color ZONE_DANGER = new Color(255, 60, 60, 60);
    static final Color ZONE_BLOCKED = new Color(160, 160, 160, 70);
    static final Color ZONE_OTHER = new Color(120, 160, 255, 28);
    /** A «DMG» zone (DarkBot: mist, the cloak does not hold; 5-3's palladium field): open to fly. */
    static final Color ZONE_MIST = new Color(170, 120, 255, 40);
    /** Faction colours of stations: MMO, EIC, VRU. */
    static final Color[] FACTION = {new Color(0x9e9e9e), new Color(0xe53935), new Color(0x1e88e5), new Color(0x43a047)};

    private final WorldFeed feed;
    private final MapCatalog catalog = MapCatalog.bundled();
    private final Hud hud;
    private final Hud compactHud;
    /** Where the blocks on the map were drawn last, for the right-click menu. */
    private volatile java.util.Map<java.awt.Rectangle, ViewSettings.Block> cardsAt = java.util.Map.of();
    /** A small map in the overview: a strip with the account instead of the map's own labels. */
    private final boolean compact;
    private final java.util.function.Supplier<String> config;

    MinimapPanel(WorldFeed feed) {
        this(feed, false, () -> null);
    }

    MinimapPanel(WorldFeed feed, boolean compact, java.util.function.Supplier<String> config) {
        this.feed = feed;
        this.compact = compact;
        this.config = config;
        setBackground(BACKGROUND);
        setToolTipText(""); // enables getToolTipText(MouseEvent)
        Font f = javax.swing.UIManager.getFont("Label.font");
        hud = new Hud(f != null ? f : getFont());
        compactHud = new Hud(f != null ? f : getFont(), true);
        setPreferredSize(compact ? new Dimension(420, 262) : new Dimension(840, 524));
        setMinimumSize(new Dimension(240, 150));
        if (!compact) {
            BlockMenu.attach(this, p -> SidePanel.blockAt(cardsAt, p), this::orders);
            addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    if (javax.swing.SwingUtilities.isLeftMouseButton(e) && !e.isPopupTrigger()
                            && SidePanel.blockAt(cardsAt, e.getPoint()) == null) {
                        double[] at = toMap(e.getX(), e.getY());
                        orbit.bot.run.BotRunner r = feed.runner();
                        if (at != null && r != null && r.takesOrders()) {
                            r.order(orbit.bot.run.BotRunner.Order.move(at[0], at[1]));
                            clickX = at[0];
                            clickY = at[1];
                            clickAt = System.nanoTime();
                            repaint();
                        }
                    }
                }
            });
        }
    }

    /** Where the user clicked last, drawn for a moment. */
    private volatile double clickX;
    private volatile double clickY;
    private volatile long clickAt;
    private static final long CLICK_MARK_NS = 1_500_000_000L;

    /** A panel point on the map in game units, or {@code null} off the map or before the ship is known. */
    double[] toMap(int x, int y) {
        WorldSnapshot.Hero hero = feed.snapshot().hero();
        if (hero.id() == 0) {
            return null;
        }
        MapCatalog.MapInfo map = catalog.info(hero.mapId());
        Projection p = projection(map);
        double mx = (x - p.ox()) / p.scale();
        double my = (y - p.oy()) / p.scale();
        if (mx < 0 || my < 0 || mx > map.width() || my > map.height()) {
            return null;
        }
        return new double[] {mx, my};
    }

    /** Portals of the hero's map near a click this far (game units) or 25 px, whichever is more; like DarkBot. */
    private static final double PORTAL_MENU_REACH = 1_000;

    /** The bot's orders for a right click at {@code pt}: jump through a portal there, fly here, pause. */
    private java.util.List<javax.swing.JComponent> orders(java.awt.Point pt) {
        orbit.bot.run.BotRunner r = feed.runner();
        if (r == null || !r.takesOrders()) {
            return java.util.List.of();
        }
        java.util.List<javax.swing.JComponent> out = new java.util.ArrayList<>();
        WorldSnapshot.Hero hero = feed.snapshot().hero();
        double[] at = toMap(pt.x, pt.y);
        if (at != null) {
            double reach = Math.max(PORTAL_MENU_REACH, 25 / projection(catalog.info(hero.mapId())).scale());
            catalog.portals(hero.mapId()).stream()
                    .filter(p -> p.toId() > 0 && (p.faction() == 0 || p.faction() == hero.factionId()))
                    .filter(p -> Math.hypot(p.x() - at[0], p.y() - at[1]) <= reach)
                    .sorted(java.util.Comparator.comparingDouble(p -> Math.hypot(p.x() - at[0], p.y() - at[1])))
                    .forEach(p -> {
                        javax.swing.JMenuItem jump = new javax.swing.JMenuItem("Прыгнуть на " + p.toName());
                        jump.addActionListener(e -> r.order(orbit.bot.run.BotRunner.Order.jump(p.toId())));
                        out.add(jump);
                    });
            javax.swing.JMenuItem fly = new javax.swing.JMenuItem("Лететь сюда");
            fly.addActionListener(e -> r.order(orbit.bot.run.BotRunner.Order.move(at[0], at[1])));
            out.add(fly);
        }
        javax.swing.JCheckBoxMenuItem pause = new javax.swing.JCheckBoxMenuItem("Пауза бота", r.paused());
        pause.setToolTipText("Модуль ждёт, корабль летает только по кликам");
        pause.addActionListener(e -> r.setPaused(pause.isSelected()));
        out.add(pause);
        return out;
    }

    /** Where the map sits in the panel, keeping its proportions (21000:13100 for most maps). */
    private Projection projection(MapCatalog.MapInfo map) {
        boolean mapOnly = !compact && ViewSettings.shared().mapOnlyNow();
        int pad = compact || mapOnly ? 0 : 8; // only the map: edge to edge
        double sx = (getWidth() - 2.0 * pad) / map.width();
        double sy = (getHeight() - 2.0 * pad) / map.height();
        // only the map: it always covers the window (a sliver cut off while the window is being resized, until
        // the window takes the map's shape), so no empty band shows
        double scale = mapOnly ? Math.max(sx, sy) : Math.min(sx, sy);
        double ox = (getWidth() - map.width() * scale) / 2;
        double oy = (getHeight() - map.height() * scale) / 2;
        return new Projection(ox, oy, scale);
    }

    /** Draws the minimap of the feed's current state into an image (no window needed). */
    static java.awt.image.BufferedImage render(WorldFeed feed, int width, int height) {
        MinimapPanel panel = new MinimapPanel(feed);
        panel.setSize(width, height);
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(width, height,
                java.awt.image.BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        try {
            panel.paint(g);
        } finally {
            g.dispose();
        }
        return img;
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            paintWorld(g, feed.snapshot());
        } finally {
            g.dispose();
        }
    }

    private void paintWorld(Graphics2D g, WorldSnapshot w) {
        WorldSnapshot.Hero hero = w.hero();
        MapCatalog.MapInfo map = catalog.info(hero.mapId());
        Projection p = projection(map);
        double ox = p.ox();
        double oy = p.oy();
        double scale = p.scale();
        double mw = map.width() * scale;
        double mh = map.height() * scale;
        // marks grow a little with the map: readable on a big screen, not blobs in the overview
        double u = Math.max(0.8, Math.min(1.5, mw / 900));

        java.awt.geom.RoundRectangle2D.Double frame = new java.awt.geom.RoundRectangle2D.Double(ox, oy, mw, mh, 10, 10);
        Shape oldClip = g.getClip();
        g.clip(frame);
        g.setColor(new Color(0x0b0e14));
        g.fill(frame);
        java.awt.image.BufferedImage bg = hero.id() == 0 ? null : MinimapBackgrounds.get(hero.mapId());
        if (bg != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bg, (int) Math.round(ox), (int) Math.round(oy), (int) Math.round(mw), (int) Math.round(mh), null);
            g.setColor(new Color(6, 8, 12, 110)); // dim the picture so the marks stand out
            g.fill(frame);
        }
        grid(g, p, map);
        g.setClip(oldClip);
        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(frame);
        g.clip(frame); // marks and their names stay inside the map, also at its edges

        for (WorldSnapshot.Zone z : w.zones().values()) {
            if (!z.active()) {
                continue;
            }
            Shape s = zoneShape(z, p);
            if (s == null) {
                continue;
            }
            g.setColor(z.type() == POITypeModule.POITYPE_RADIATION ? ZONE_DANGER
                    : z.type() == POITypeModule.POITYPE_DAMAGE ? ZONE_MIST
                    : z.type() == POITypeModule.POITYPE_NO_ACCESS || z.type() == POITypeModule.POITYPE_FACTION_NO_ACCESS
                    ? ZONE_BLOCKED : ZONE_OTHER);
            if (z.inverted()) {
                Path2D.Double outside = new Path2D.Double(Path2D.WIND_EVEN_ODD);
                outside.append(new Rectangle2D.Double(ox, oy, map.width() * scale, map.height() * scale), false);
                outside.append(s, false);
                g.fill(outside);
            } else {
                g.fill(s);
            }
        }

        for (WorldSnapshot.Asset a : w.assets().values()) {
            if (a.invisible()) {
                continue;
            }
            g.setColor(FACTION[a.factionId() >= 0 && a.factionId() < FACTION.length ? a.factionId() : 0]);
            g.fill(new Rectangle2D.Double(p.x(a.x()) - 3 * u, p.y(a.y()) - 3 * u, 6 * u, 6 * u));
        }
        g.setFont(hud.font(Font.PLAIN, (float) (10 * Math.min(1.2, u))));
        for (WorldSnapshot.Portal portal : w.portals().values()) {
            // the game shows only the own faction's on its minimap; the others are drawn dimmer (logging out by
            // them is as good, they are just not for the jumps)
            double px = p.x(portal.x());
            double py = p.y(portal.y());
            g.setColor(portal.visibleOnMinimap() ? PORTAL : PORTAL.darker().darker());
            g.setStroke(new BasicStroke((float) (1.4 * u)));
            g.draw(new Ellipse2D.Double(px - 6 * u, py - 6 * u, 12 * u, 12 * u));
            g.setStroke(new BasicStroke(1f));
            // where it leads (known for regular portals); gates and event portals stay unlabelled
            MapCatalog.Portal known = catalog.portalNear(hero.mapId(), portal.x(), portal.y(), 50);
            if (known != null && !compact) {
                int tw = g.getFontMetrics().stringWidth(known.toName());
                drawLabel(g, known.toName(), px - tw / 2.0, py - 9 * u, PORTAL_LABEL);
            }
        }
        for (WorldSnapshot.Collectable c : w.collectables().values()) {
            g.setColor(c.kind() == WorldSnapshot.CollectableKind.BOX ? BOX : ORE);
            g.fill(new Rectangle2D.Double(p.x(c.x()) - 1.5 * u, p.y(c.y()) - 1.5 * u, 3 * u, 3 * u));
        }
        // mines: a red cross and the circle the bot keeps off
        for (WorldSnapshot.Mine m : w.mines().values()) {
            double mx = p.x(m.x());
            double my = p.y(m.y());
            double mr = Math.abs(p.x(m.x() + orbit.bot.nav.PathFinder.MINE_RADIUS) - mx);
            g.setColor(new Color(230, 70, 60, 70));
            g.draw(new Ellipse2D.Double(mx - mr, my - mr, 2 * mr, 2 * mr));
            g.setColor(new Color(230, 70, 60));
            g.draw(new java.awt.geom.Line2D.Double(mx - 2.5, my - 2.5, mx + 2.5, my + 2.5));
            g.draw(new java.awt.geom.Line2D.Double(mx - 2.5, my + 2.5, mx + 2.5, my - 2.5));
        }
        Font labelFont = hud.font(Font.PLAIN, (float) (10 * Math.min(1.2, u)));
        for (Ship s : w.ships().values()) {
            Color c = shipColor(s, hero, w.group());
            g.setColor(c);
            double sx = p.x(s.x());
            double sy = p.y(s.y());
            switch (s.kind()) {
                case PET -> g.fill(new Ellipse2D.Double(sx - 2 * u, sy - 2 * u, 4 * u, 4 * u));
                case NPC -> g.fill(new Ellipse2D.Double(sx - 3 * u, sy - 3 * u, 6 * u, 6 * u));
                case PLAYER -> {
                    double d = 4.5 * u;
                    Path2D.Double diamond = new Path2D.Double();
                    diamond.moveTo(sx, sy - d);
                    diamond.lineTo(sx + d, sy);
                    diamond.lineTo(sx, sy + d);
                    diamond.lineTo(sx - d, sy);
                    diamond.closePath();
                    g.fill(diamond);
                }
            }
            // players always, NPCs only when they matter (target or attacker): maps hold dozens of NPCs
            boolean fighting = s.id() == hero.selectedId() || s.id() == hero.attackTargetId()
                    || (hero.attackers().size() <= MAX_ATTACKER_LABELS && hero.attackers().contains(s.id()));
            boolean target = s.id() == hero.attackTargetId() || s.id() == hero.selectedId();
            if (compact ? target : (s.kind() == ShipKind.PLAYER || (s.kind() == ShipKind.NPC && fighting))) {
                g.setFont(labelFont);
                drawLabel(g, label(s), sx + 6 * u, sy - 4 * u, c);
            }
        }

        // group members on this map (their own ships, or the position the group update reports)
        for (WorldSnapshot.Member m : w.group().members()) {
            if (m.id() == hero.id() || m.mapId() != hero.mapId() || m.loggedOut()) {
                continue;
            }
            Ship s = w.ships().get(m.id());
            double mx = p.x(s != null ? s.x() : m.x());
            double my = p.y(s != null ? s.y() : m.y());
            Path2D.Double diamond = new Path2D.Double();
            diamond.moveTo(mx, my - 5);
            diamond.lineTo(mx + 5, my);
            diamond.lineTo(mx, my + 5);
            diamond.lineTo(mx - 5, my);
            diamond.closePath();
            g.setColor(ALLY);
            g.draw(diamond);
        }

        long sinceClick = System.nanoTime() - clickAt;
        if (clickAt != 0 && sinceClick < CLICK_MARK_NS) {
            float a = 1f - (float) sinceClick / CLICK_MARK_NS;
            double cx = p.x(clickX);
            double cy = p.y(clickY);
            double r = 5 + 10 * (1 - a);
            g.setColor(new Color(HERO.getRed(), HERO.getGreen(), HERO.getBlue(), (int) (200 * a)));
            g.setStroke(new BasicStroke(1.5f));
            g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
            g.setStroke(new BasicStroke(1f));
        }
        if (hero.id() != 0) {
            double hx = p.x(hero.x());
            double hy = p.y(hero.y());
            g.setColor(ENEMY);
            for (int id : hero.attackers()) {
                Ship a = w.ships().get(id);
                if (a != null) {
                    g.draw(new Line2D.Double(p.x(a.x()), p.y(a.y()), hx, hy));
                }
            }
            Ship attacked = w.ships().get(hero.attackTargetId());
            if (attacked != null) {
                g.setColor(NPC);
                g.setStroke(new BasicStroke(2f));
                g.draw(new Line2D.Double(hx, hy, p.x(attacked.x()), p.y(attacked.y())));
                g.setStroke(new BasicStroke(1f));
            }
            if (hero.moving()) {
                g.setColor(new Color(HERO.getRed(), HERO.getGreen(), HERO.getBlue(), 120));
                g.draw(new Line2D.Double(hx, hy, p.x(hero.targetX()), p.y(hero.targetY())));
            }
            Ship target = w.ships().get(hero.selectedId());
            if (target != null) {
                g.setColor(NPC);
                g.setStroke(new BasicStroke((float) (1.5 * u)));
                g.draw(new Ellipse2D.Double(p.x(target.x()) - 7 * u, p.y(target.y()) - 7 * u, 14 * u, 14 * u));
                g.setStroke(new BasicStroke(1f));
            }
            locatorRing(g, w, p, u, target, labelFont);
            // the hero: a glow, then the dot
            g.setColor(new Color(HERO.getRed(), HERO.getGreen(), HERO.getBlue(), 60));
            g.fill(new Ellipse2D.Double(hx - 9 * u, hy - 9 * u, 18 * u, 18 * u));
            g.setColor(hero.alive() ? HERO : Color.GRAY);
            g.fill(new Ellipse2D.Double(hx - 4.5 * u, hy - 4.5 * u, 9 * u, 9 * u));
        }
        g.setClip(oldClip);
        // the overlays keep to the part of the map in view (the map may run past the edges in map-only mode)
        double vx = Math.max(ox, 0);
        double vy = Math.max(oy, 0);
        double vw = Math.min(ox + mw, getWidth()) - vx;
        double vh = Math.min(oy + mh, getHeight()) - vy;
        int pillsBottom = hud.boostPills(g, (int) Math.round(vx + vw) - 8, (int) Math.round(vy) + 8,
                (int) Math.round(vw * (compact ? 0.45 : 0.5)), w.boosters(), compact || vw < BIG_MAP);
        if (compact) {
            hud.targetChip(g, (int) Math.round(vx) + 8, (int) Math.round(vy) + 8, (int) Math.round(vw * 0.3), w);
            hud.strip(g, (int) Math.round(vx), (int) Math.round(vy + vh), (int) Math.round(vw), feed, config.get());
        } else {
            mapChip(g, vx, vy, vw, hero, map);
            // the blocks the user put on the map: full cards on a big map, compact ones on a small one
            boolean big = vw >= BIG_MAP;
            java.util.Map<java.awt.Rectangle, ViewSettings.Block> placed = new java.util.LinkedHashMap<>();
            Hud cards = big ? hud : compactHud;
            int panel = big ? (int) Math.max(230, Math.min(320, vw * 0.26)) : (int) Math.max(170, Math.min(250, vw * 0.34));
            int left = (int) Math.round(vx) + 10;
            int right = (int) Math.round(vx + vw) - 10;
            // the account strip along the bottom, as in the overview: the columns end above it
            int stripTop = (int) Math.round(vy + vh) - Hud.STRIP_HEIGHT - 20;
            cards.mapBlocks(g, left, right, (int) Math.round(vy) + 46, Math.max((int) Math.round(vy) + 40, pillsBottom + 8),
                    stripTop, panel, w, feed, config.get(), ViewSettings.shared(), placed, big);
            cardsAt = placed;
            Shape clip = g.getClip();
            g.clip(frame);
            hud.strip(g, (int) Math.round(vx), (int) Math.round(vy + vh), (int) Math.round(vw), feed, config.get());
            g.setClip(clip);
        }
        if (w.boxCheck() != null) {
            boxCheck(g, w, p, vx, vy, vw);
        }
    }

    static final Color BOX_CHECK = new Color(0xff3b30);

    /** The game's box check: its circle on the map and the task with the time left, big, over the map. */
    private void boxCheck(Graphics2D g, WorldSnapshot w, Projection p, double vx, double vy, double vw) {
        WorldSnapshot.BoxCheck c = w.boxCheck();
        double r = c.radius() * p.scale();
        g.setColor(BOX_CHECK);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 5f}, 0f));
        g.draw(new Ellipse2D.Double(p.x(c.x()) - r, p.y(c.y()) - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(1f));
        String text = c.text(w.nanos());
        g.setFont(hud.font(Font.BOLD, compact ? 13f : 18f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int pad = compact ? 6 : 10;
        int width = Math.min((int) vw - 20, fm.stringWidth(text) + 2 * pad);
        int height = fm.getHeight() + 2 * pad - 4;
        int x = (int) Math.round(vx + (vw - width) / 2);
        int y = (int) Math.round(vy) + (compact ? 30 : 52);
        g.setColor(new Color(120, 10, 10, 225));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(x, y, width, height, 10, 10));
        g.setColor(BOX_CHECK);
        g.setStroke(new BasicStroke(2f));
        g.draw(new java.awt.geom.RoundRectangle2D.Double(x, y, width, height, 10, 10));
        g.setStroke(new BasicStroke(1f));
        g.setColor(Color.WHITE);
        Shape clip = g.getClip();
        g.clip(new Rectangle2D.Double(x + pad, y, width - 2 * pad, height));
        g.drawString(text, x + pad, y + pad + fm.getAscent() - 2);
        g.setClip(clip);
    }

    /**
     * The pet's enemy locator point: a ring like the selected target's, in its own colour. On the selected target
     * itself it goes round the red ring at the same gap, so one ring or two never overlap.
     */
    private void locatorRing(Graphics2D g, WorldSnapshot w, Projection p, double u, Ship target, Font labelFont) {
        WorldSnapshot.Pet pet = w.pet();
        if (!pet.active() || !pet.locatorWithin(w.nanos(), Hud.LOCATOR_SHOWN_MS)) {
            return;
        }
        double lx = p.x(pet.locatorX());
        double ly = p.y(pet.locatorY());
        boolean onTarget = target != null && Math.hypot(p.x(target.x()) - lx, p.y(target.y()) - ly) <= 10 * u;
        double cx = onTarget ? p.x(target.x()) : lx;
        double cy = onTarget ? p.y(target.y()) : ly;
        double r = onTarget ? 10 * u : 7 * u;
        g.setColor(LOCATOR);
        g.setStroke(new BasicStroke((float) (1.5 * u)));
        g.draw(new Ellipse2D.Double(cx - r, cy - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(1f));
        if (!onTarget && !compact) {
            g.setFont(labelFont);
            drawLabel(g, "пеленгатор", cx + r + 4 * u, cy + 4 * u, LOCATOR);
        }
    }

    /** The map's name and the hero's place in the top-left corner of the map. */
    private void mapChip(Graphics2D g, double ox, double oy, double width, WorldSnapshot.Hero hero, MapCatalog.MapInfo map) {
        String text = hero.id() == 0 || feed.loggedOut() ? feed.state()
                : map.name() + "   " + hero.x() / 100 + "/" + hero.y() / 100;
        g.setFont(hud.font(Font.BOLD, 13f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        text = Hud.clip(g, text, (int) width - 40); // never past the map's edge
        int w = fm.stringWidth(text) + 20;
        g.setColor(new Color(10, 12, 18, 200));
        g.fill(new java.awt.geom.RoundRectangle2D.Double(ox + 10, oy + 10, w, 26, 13, 13));
        g.setColor(Hud.TEXT);
        g.drawString(text, (float) (ox + 20), (float) (oy + 28));
        if (!ViewSettings.shared().shown(ViewSettings.Section.HEADER)) {
            // the header with its buttons is hidden: say where the menu is
            String hint = getWidth() >= 700 ? "☰ правый клик — меню" : "☰"; // a small map has no room for words
            g.setFont(hud.font(Font.PLAIN, 11f));
            java.awt.FontMetrics hm = g.getFontMetrics();
            int hw = hm.stringWidth(hint) + 16;
            double hx = ox + 10 + w + 6;
            if (hx + hw > ox + width - 10) {
                return; // no room left beside the text
            }
            g.setColor(new Color(10, 12, 18, 200));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(hx, oy + 10, hw, 26, 13, 13));
            g.setColor(Hud.DIM);
            g.drawString(hint, (float) (hx + 8), (float) (oy + 27));
        }
    }

    /** Faint lines every 1000 game units, like the game's own minimap ruler. */
    private void grid(Graphics2D g, Projection p, MapCatalog.MapInfo map) {
        if (p.scale() * 1000 < 18) {
            return;
        }
        g.setColor(new Color(255, 255, 255, 14));
        for (int x = 1000; x < map.width(); x += 1000) {
            g.draw(new Line2D.Double(p.x(x), p.y(0), p.x(x), p.y(map.height())));
        }
        for (int y = 1000; y < map.height(); y += 1000) {
            g.draw(new Line2D.Double(p.x(0), p.y(y), p.x(map.width()), p.y(y)));
        }
    }

    /** A name next to a mark, with a dark shadow so it reads on any background. */
    private static void drawLabel(Graphics2D g, String text, double x, double y, Color color) {
        g.setColor(new Color(0, 0, 0, 170));
        g.drawString(text, (float) x + 1, (float) y + 1);
        g.setColor(color);
        g.drawString(text, (float) x, (float) y);
    }

    /** From this map width the fight and the ship are shown over the map too. */
    static final double BIG_MAP = 760;

    /** Up to this many attackers get a name on the map; a swarm is listed in the corner panel only. */
    private static final int MAX_ATTACKER_LABELS = 3;

    static String label(Ship s) {
        String tag = s.clanTag() == null || s.clanTag().isBlank() ? "" : "[" + s.clanTag() + "] ";
        return tag + s.name();
    }

    /** Tooltip for the object under the mouse: name, kind, ship, health, distance. */
    @Override
    public String getToolTipText(java.awt.event.MouseEvent e) {
        WorldSnapshot w = feed.snapshot();
        WorldSnapshot.Hero hero = w.hero();
        MapCatalog.MapInfo map = catalog.info(hero.mapId());
        Projection pr = projection(map);
        double scale = pr.scale();
        double mx = (e.getX() - pr.ox()) / scale;
        double my = (e.getY() - pr.oy()) / scale;
        double reach = 8 / scale;
        Ship best = null;
        double bestD = reach;
        for (Ship s : w.ships().values()) {
            double d = Math.hypot(s.x() - mx, s.y() - my);
            if (d < bestD) {
                bestD = d;
                best = s;
            }
        }
        if (best == null) {
            for (WorldSnapshot.Portal portal : w.portals().values()) {
                if (Math.hypot(portal.x() - mx, portal.y() - my) < reach) {
                    MapCatalog.Portal known = catalog.portalNear(hero.mapId(), portal.x(), portal.y(), 50);
                    orbit.bot.run.BotRunner r = feed.runner();
                    return "Портал" + (known != null ? " на " + known.toName() : "") + "  (" + portal.x() / 100 + "/"
                            + portal.y() / 100 + ")" + (!compact && known != null && r != null && r.takesOrders()
                            ? " — правый клик: прыгнуть" : "");
                }
            }
            return null;
        }
        String kind = switch (best.kind()) {
            case NPC -> "NPC";
            case PET -> "питомец";
            case PLAYER -> best.factionId() == hero.factionId() ? "свой игрок" : "чужой игрок";
        };
        StringBuilder t = new StringBuilder("<html><b>").append(escape(label(best))).append("</b><br>").append(kind);
        if (!best.typeId().isBlank()) {
            t.append(", ").append(escape(Hud.shipName(best.typeId())));
        }
        if (best.health().known()) {
            t.append(String.format("<br>HP %,.0f / %,.0f", best.health().hp(), best.health().hpMax()));
        }
        if (best.health().shieldMax() > 0) {
            t.append(String.format(", щит %,d / %,d", best.health().shield(), best.health().shieldMax()));
        }
        t.append(String.format("<br>расстояние %,.0f", best.distanceTo(hero.x(), hero.y())));
        if (best.lockedByOther()) {
            t.append("<br>бьёт другой игрок");
        }
        return t.append("</html>").toString();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static Color shipColor(Ship s, WorldSnapshot.Hero hero, WorldSnapshot.Group group) {
        if (group.members().stream().anyMatch(m -> m.id() == s.id())) {
            return ALLY;
        }
        return switch (s.kind()) {
            case PET -> PET;
            case NPC -> s.lockedByOther() ? NPC_TAKEN : NPC;
            case PLAYER -> s.factionId() == hero.factionId() ? (hero.clanId() != 0 && s.clanId() == hero.clanId() ? ALLY : FRIEND)
                    : ENEMY;
        };
    }

    private static Shape zoneShape(WorldSnapshot.Zone z, Projection p) {
        List<Integer> c = z.coords();
        if (z.shape() == MapAddPOICommand.SHAPETYPE_CIRCLE) {
            if (c.size() < 3) {
                return null;
            }
            double r = c.get(2) * p.scale();
            return new Ellipse2D.Double(p.x(c.get(0)) - r, p.y(c.get(1)) - r, 2 * r, 2 * r);
        }
        if (c.size() < 4) {
            return null;
        }
        if (z.shape() == MapAddPOICommand.SHAPETYPE_RECTANGLE) {
            double minX = Double.MAX_VALUE;
            double minY = Double.MAX_VALUE;
            double maxX = -Double.MAX_VALUE;
            double maxY = -Double.MAX_VALUE;
            for (int i = 0; i + 1 < c.size(); i += 2) {
                minX = Math.min(minX, c.get(i));
                maxX = Math.max(maxX, c.get(i));
                minY = Math.min(minY, c.get(i + 1));
                maxY = Math.max(maxY, c.get(i + 1));
            }
            return new Rectangle2D.Double(p.x(minX), p.y(minY), (maxX - minX) * p.scale(), (maxY - minY) * p.scale());
        }
        Path2D.Double poly = new Path2D.Double();
        poly.moveTo(p.x(c.get(0)), p.y(c.get(1)));
        for (int i = 2; i + 1 < c.size(); i += 2) {
            poly.lineTo(p.x(c.get(i)), p.y(c.get(i + 1)));
        }
        poly.closePath();
        return poly;
    }

    private record Projection(double ox, double oy, double scale) {
        double x(double mapX) {
            return ox + mapX * scale;
        }

        double y(double mapY) {
            return oy + mapY * scale;
        }
    }
}
