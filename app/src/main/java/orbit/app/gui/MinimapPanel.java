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
    /** The cards over the map: see-through and short (the side panel keeps the full ones). */
    private final Hud mapHud;
    private final Hud mapCompactHud;
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
        mapHud = new Hud(f != null ? f : getFont(), false, true);
        mapCompactHud = new Hud(f != null ? f : getFont(), true, true);
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
        labels.clear();
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
        ViewSettings.MapBackground ground = ViewSettings.shared().mapBackground();
        boolean picture = ground == ViewSettings.MapBackground.PICTURE;
        g.setColor(ground == ViewSettings.MapBackground.SLATE ? SLATE : new Color(0x0b0e14));
        g.fill(frame);
        java.awt.image.BufferedImage bg = hero.id() == 0 || !picture ? null : MinimapBackgrounds.get(hero.mapId());
        if (bg != null) {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(bg, (int) Math.round(ox), (int) Math.round(oy), (int) Math.round(mw), (int) Math.round(mh), null);
            g.setColor(new Color(6, 8, 12, 110)); // dim the picture so the marks stand out
            g.fill(frame);
        }
        grid(g, p, map, ground != ViewSettings.MapBackground.SLATE);
        if (hero.id() != 0 && !compact) {
            watermark(g, ox, oy, mw, mh, map);
        }
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
            String label = gateLabel(hero.mapId(), portal);
            if (label == null && known != null) {
                label = known.toName();
            }
            if (label != null && !compact) {
                int tw = g.getFontMetrics().stringWidth(label);
                placeLabel(g, label, px - tw / 2.0, py - 9 * u, PORTAL_LABEL, false);
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
        // players who attacked the ship this session: the circle the bot keeps clear, and the name
        orbit.bot.run.BotRunner runner = feed.runner();
        if (runner != null) {
            g.setFont(labelFont);
            for (orbit.bot.run.Offenders.Circle c : runner.avoidedPlayers()) {
                double cx = p.x(c.x());
                double cy = p.y(c.y());
                double cr = Math.abs(p.x(c.x() + c.radius()) - cx);
                g.setColor(OFFENDER_FILL);
                g.fill(new Ellipse2D.Double(cx - cr, cy - cr, 2 * cr, 2 * cr));
                g.setColor(OFFENDER);
                g.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {5f, 4f}, 0f));
                g.draw(new Ellipse2D.Double(cx - cr, cy - cr, 2 * cr, 2 * cr));
                g.setStroke(new BasicStroke(1f));
                String label = "обидчик: " + c.name();
                int lw = g.getFontMetrics().stringWidth(label);
                g.drawString(label, (float) (cx - lw / 2.0), (float) (cy - cr - 4));
            }
        }
        // names only where they help and never on top of each other (the user, 2026-11-16: «подписи без каши»): the
        // target, the fight, the nearest players; the rest under the mouse
        java.util.Set<Integer> named = nearestPlayers(w, hero, NAMED_PLAYERS);
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
            if (compact ? target : (fighting || s.kind() == ShipKind.PLAYER && named.contains(s.id()))) {
                g.setFont(labelFont);
                placeLabel(g, label(s), sx + 6 * u, sy - 4 * u, c, target);
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
            int chipBottom = mapChip(g, vx, vy, vw, hero, map);
            int ratesBottom = chipBottom;
            int plaqueBottom = gatePlaque(g, vx, vy, vw, w, hero);
            gameFeed(g, vx, plaqueBottom + 6, vw);
            // the blocks the user put on the map: full cards on a big map, compact ones on a small one
            boolean big = vw >= BIG_MAP;
            // a low map: the small cards too, so more of them shows (the user, 2026-11-16: a small window)
            boolean roomy = big && vh >= 700;
            java.util.Map<java.awt.Rectangle, ViewSettings.Block> placed = new java.util.LinkedHashMap<>();
            Hud cards = roomy ? mapHud : mapCompactHud;
            int panel = big ? (int) Math.max(230, Math.min(320, vw * 0.26)) : (int) Math.max(170, Math.min(250, vw * 0.34));
            int left = (int) Math.round(vx) + 10;
            int right = (int) Math.round(vx + vw) - 10;
            // the account strip along the bottom, as in the overview: the columns end above it
            // the columns end above the «в час» block and the target's strip (they sit on the bottom strip)
            int stripTop = (int) Math.round(vy + vh) - Hud.STRIP_HEIGHT - 22 - 4 * 16 - 10 - 8;
            cards.mapBlocks(g, left, right, Math.max((int) Math.round(vy) + 46, ratesBottom + 8),
                    Math.max((int) Math.round(vy) + 40, pillsBottom + 8),
                    stripTop, panel, w, feed, config.get(), ViewSettings.shared(), placed, big);
            cardsAt = placed;
            Shape clip = g.getClip();
            g.clip(frame);
            hud.strip(g, (int) Math.round(vx), (int) Math.round(vy + vh), (int) Math.round(vw), feed, config.get());
            int above = (int) Math.round(vy + vh) - Hud.STRIP_HEIGHT - 22;
            if (ViewSettings.shared().onMap(ViewSettings.Block.TARGET)) {
                // «Цель» on the map is this strip (as the card was): «Сбоку» or «Скрыть» take it off the map
                hud.targetStrip(g, (int) Math.round(vx + vw) - 8, above, (int) Math.max(220, Math.min(380, vw * 0.38)), w);
            }
            rates(g, vx + 8, above, w); // «в час» on the left, the target on the right, the ship's strip under both
            g.setClip(clip);
        }
        if (w.boxCheck() != null) {
            boxCheck(g, w, p, vx, vy, vw);
        }
    }

    /** The map without the game's picture: DarkBot's slate (the user, 2026-11-16: «у DarkBot больше нравится»). */
    static final Color SLATE = new Color(0x26303b);
    /** Players named on the map: the nearest this many (the rest under the mouse). */
    static final int NAMED_PLAYERS = 3;
    /** The game's lines over the map: the last few, each fully seen this long, then fading out. */
    static final int FEED_LINES = 4;
    static final long FEED_SHOW_NS = 4_000_000_000L;
    static final long FEED_FADE_NS = 1_500_000_000L;

    /** The names drawn this frame: a new one finds a free place or is left out. */
    private final java.util.List<Rectangle2D> labels = new java.util.ArrayList<>();

    /** A name beside a mark where no other name is: as asked, a little lower, a little higher, or not at all. */
    private void placeLabel(Graphics2D g, String text, double x, double y, Color color, boolean always) {
        java.awt.FontMetrics fm = g.getFontMetrics();
        double w = fm.stringWidth(text);
        double h = fm.getAscent();
        for (double dy : new double[] {0, h + 2, -(h + 2), 2 * (h + 2)}) {
            Rectangle2D r = new Rectangle2D.Double(x - 1, y + dy - h, w + 2, h + 3);
            if (labels.stream().noneMatch(r::intersects)) {
                labels.add(r);
                drawLabel(g, text, x, y + dy, color);
                return;
            }
        }
        if (always) {
            drawLabel(g, text, x, y, color); // the target is named whatever is near
        }
    }

    private static java.util.Set<Integer> nearestPlayers(WorldSnapshot w, WorldSnapshot.Hero hero, int n) {
        java.util.Set<Integer> out = new java.util.HashSet<>();
        w.ships().values().stream().filter(s -> s.kind() == ShipKind.PLAYER)
                .sorted(java.util.Comparator.comparingDouble(s -> s.distanceTo(hero.x(), hero.y())))
                .limit(n).forEach(s -> out.add(s.id()));
        return out;
    }

    /** The map's name big and faint in the middle, the session's time under it (as AstraBot and DarkBot). */
    private void watermark(Graphics2D g, double ox, double oy, double mw, double mh, MapCatalog.MapInfo map) {
        float size = (float) Math.max(28, Math.min(110, mh * 0.2));
        String name = map.name();
        g.setFont(hud.font(Font.BOLD, size));
        // a long name («Eternal Blacklight») no wider than about half the map (the user, 2026-11-16: «слишком большая»)
        double fit = mw * 0.55;
        int nw = g.getFontMetrics().stringWidth(name);
        if (nw > fit) {
            size = (float) Math.max(20, size * fit / nw);
            g.setFont(hud.font(Font.BOLD, size));
        }
        java.awt.FontMetrics fm = g.getFontMetrics();
        g.setColor(new Color(255, 255, 255, 34));
        double cy = oy + mh / 2;
        g.drawString(name, (float) (ox + (mw - fm.stringWidth(name)) / 2), (float) (cy + fm.getAscent() * 0.35));
        long ns = feed.sessionNanos();
        if (ns > 0) {
            String t = "сессия " + Hud.clock(java.time.Duration.ofNanos(ns));
            g.setFont(hud.font(Font.PLAIN, Math.max(11f, size * 0.2f)));
            java.awt.FontMetrics tm = g.getFontMetrics();
            g.setColor(new Color(255, 255, 255, 70));
            g.drawString(t, (float) (ox + (mw - tm.stringWidth(t)) / 2), (float) (cy + fm.getAscent() * 0.35 + tm.getHeight() + 4));
        }
    }

    /** «В час»: uridium and credits per hour, deaths, the hold — a few lines above the bottom strip, on the left. */
    private void rates(Graphics2D g, double x, int bottom, WorldSnapshot w) {
        double hours = feed.sessionHours();
        WorldSnapshot.Hero hero = w.hero();
        if (hero.id() == 0) {
            return;
        }
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        if (hours >= 1.0 / 60) {
            WorldSnapshot.Rewards r = feed.sessionRewards();
            rows.add(new String[] {"уридий / ч", orbit.app.log.Friendly.short_(r.uridium() / hours)});
            rows.add(new String[] {"кредиты / ч", orbit.app.log.Friendly.short_(r.credits() / hours)});
        }
        orbit.bot.run.Stats st = feed.sessionStats();
        if (st != null) {
            rows.add(new String[] {"смерти", String.valueOf(st.deaths.get())});
        }
        if (hero.cargoMax() > 0) {
            long used = Math.max(0, hero.cargoMax() - w.cargoFree());
            rows.add(new String[] {"трюм", String.format("%,d / %,d", used, hero.cargoMax()).replace(',', ' ')});
        }
        if (rows.isEmpty()) {
            return;
        }
        Font key = hud.font(Font.PLAIN, 11f);
        Font val = new Font(Font.MONOSPACED, Font.PLAIN, 12);
        int width = 196;
        int line = 16;
        int h = rows.size() * line + 10;
        int y = bottom - h;
        g.setColor(new Color(10, 12, 18, 150)); // the portals under it show through
        g.fillRect((int) x, y, width, h);
        int ty = y + 16;
        for (String[] r : rows) {
            g.setFont(key);
            g.setColor(Hud.DIM);
            g.drawString(r[0], (int) x + 10, ty);
            g.setFont(val);
            g.setColor(Hud.TEXT);
            g.drawString(r[1], (int) x + width - 10 - g.getFontMetrics().stringWidth(r[1]), ty);
            ty += line;
        }
    }

    private static final java.util.regex.Pattern COUNT = java.util.regex.Pattern.compile("-=-\\s*(\\d+)\\s*-=-");

    /** In a galaxy gate: the gate, its wave, the lives and the countdown to the wave, top centre. @return its bottom */
    private int gatePlaque(Graphics2D g, double vx, double vy, double vw, WorldSnapshot w, WorldSnapshot.Hero hero) {
        orbit.config.GateCatalog.Gate gate = hero.id() == 0 ? null : orbit.config.GateCatalog.byMap(hero.mapId());
        if (gate == null) {
            return (int) Math.round(vy + 8);
        }
        WorldSnapshot.Gate st = gate.builderId() != 0 ? w.gates().gate(gate.builderId()) : null;
        StringBuilder t = new StringBuilder("eternal".equals(gate.key()) ? "Вечные врата" : "Врата " + gate.name().split(" ")[0]);
        if ("eternal".equals(gate.key())) {
            WorldSnapshot.Eternal e = w.gates().eternal();
            if (e.wave() > 0) {
                t.append("  ·  волна ").append(e.wave());
            }
            if (e.cpus() >= 0) {
                t.append("  ·  CPU ").append(e.cpus());
            }
        }
        if (st != null && st.wave() > 0) {
            t.append("  ·  волна ").append(st.wave()).append(gate.waves() > 0 ? " / " + gate.waves() : "");
        }
        if (st != null && st.lives() > 0) {
            t.append("  ·  жизней ").append(st.lives());
        }
        long now = feed.nowNanos();
        java.util.List<orbit.world.World.GameNote> notes = feed.gameNotes();
        for (int i = notes.size() - 1; i >= 0; i--) {
            orbit.world.World.GameNote n = notes.get(i);
            java.util.regex.Matcher m = n.text() == null ? null : COUNT.matcher(n.text());
            if (m != null && m.find()) {
                int left = Integer.parseInt(m.group(1));
                if (now - n.nanos() < 1_500_000_000L) {
                    t.append("  ·  ").append(left == 0 ? "волна пошла" : "⏱ волна через " + left + " с");
                }
                break;
            }
        }
        g.setFont(hud.font(Font.BOLD, 13f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        String text = Hud.clip(g, t.toString(), (int) vw - 60);
        int width = fm.stringWidth(text) + 24;
        int x = (int) Math.round(vx + (vw - width) / 2);
        int y = (int) Math.round(vy + 10);
        g.setColor(new Color(10, 12, 18, 200));
        g.fillRect(x, y, width, 28);
        g.setColor(new Color(MinimapPanel.HERO.getRed(), MinimapPanel.HERO.getGreen(), MinimapPanel.HERO.getBlue(), 170));
        g.fillRect(x, y, width, 2);
        g.setColor(Hud.TEXT);
        g.drawString(text, x + 12, y + 19);
        return y + 28;
    }

    /**
     * The game's own lines (the user, 2026-11-16: «лента на карте — журнал с самой игры»): «Вы получили 26 880 опыта»,
     * the items, the server's text — the last {@link #FEED_LINES}, a new one at once, the old fading out.
     */
    private void gameFeed(Graphics2D g, double vx, int top, double vw) {
        long now = feed.nowNanos();
        java.util.List<orbit.world.World.GameNote> all = feed.gameNotes();
        java.util.List<orbit.world.World.GameNote> shown = new java.util.ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && shown.size() < FEED_LINES; i--) {
            orbit.world.World.GameNote n = all.get(i);
            long age = now - n.nanos();
            if (age > FEED_SHOW_NS + FEED_FADE_NS) {
                break;
            }
            if (n.text() != null && COUNT.matcher(n.text()).find()) {
                continue; // the countdown is on the gate's plaque
            }
            shown.add(0, n);
        }
        g.setFont(hud.font(Font.PLAIN, 12f));
        java.awt.FontMetrics fm = g.getFontMetrics();
        int y = top + fm.getAscent();
        for (orbit.world.World.GameNote n : shown) {
            long age = now - n.nanos();
            float a = age <= FEED_SHOW_NS ? 1f : Math.max(0f, 1f - (float) (age - FEED_SHOW_NS) / FEED_FADE_NS);
            String text = noteText(n);
            int tx = (int) Math.round(vx + (vw - fm.stringWidth(text)) / 2);
            g.setColor(new Color(0, 0, 0, (int) (170 * a)));
            g.drawString(text, tx + 1, y + 1);
            Color c = n.amount() < 0 ? MinimapPanel.ENEMY : Hud.TEXT;
            g.setColor(new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (235 * a)));
            g.drawString(text, tx, y);
            y += fm.getHeight() + 2;
        }
    }

    /** A game's line in the client's words: «Вы получили 100 уридия», «Потрачено 500 уридия», the server's text. */
    static String noteText(orbit.world.World.GameNote n) {
        if (n.text() != null) {
            return n.text();
        }
        String what = switch (n.item()) {
            case "uridium" -> "уридия";
            case "credits" -> "кредитов";
            case "experience" -> "опыта";
            case "honor" -> "чести";
            default -> orbit.app.SessionSummary.displayName(n.item());
        };
        String amount = String.format("%,.0f", Math.abs(n.amount())).replace(',', ' ').replace('\u00a0', ' ');
        return (n.amount() < 0 ? "Потрачено " : "Вы получили ") + amount + " " + what;
    }

    static final Color BOX_CHECK = new Color(0xff3b30);
    static final Color OFFENDER = new Color(0xff6b3d);
    static final Color OFFENDER_FILL = new Color(255, 107, 61, 26);

    /** The game's box check: its circle on the map and the task with the time left, big, over the map. */
    private void boxCheck(Graphics2D g, WorldSnapshot w, Projection p, double vx, double vy, double vw) {
        WorldSnapshot.BoxCheck c = w.boxCheck();
        double r = c.radius() * p.scale();
        g.setColor(BOX_CHECK);
        g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND, 1f, new float[] {6f, 5f}, 0f));
        g.draw(new Ellipse2D.Double(p.x(c.x()) - r, p.y(c.y()) - r, 2 * r, 2 * r));
        g.setStroke(new BasicStroke(1f));
        double s = compact ? 3 : 4;
        for (WorldSnapshot.BoxCheck.CheckBox b : c.boxes()) {
            if (b.picked()) {
                continue;
            }
            Rectangle2D.Double box = new Rectangle2D.Double(p.x(b.x()) - s, p.y(b.y()) - s, 2 * s, 2 * s);
            g.setColor(b.red() ? BOX_CHECK : Color.BLACK);
            g.fill(box);
            g.setColor(b.red() == c.pickRed() ? Color.WHITE : Color.GRAY);
            g.draw(box);
        }
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
    /** @return the chip's bottom */
    private int mapChip(Graphics2D g, double ox, double oy, double width, WorldSnapshot.Hero hero, MapCatalog.MapInfo map) {
        // the map's name is the watermark in the middle now: here where the ship is
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
                return (int) Math.round(oy + 36); // no room left beside the text
            }
            g.setColor(new Color(10, 12, 18, 200));
            g.fill(new java.awt.geom.RoundRectangle2D.Double(hx, oy + 10, hw, 26, 13, 13));
            g.setColor(Hud.DIM);
            g.drawString(hint, (float) (hx + 8), (float) (oy + 27));
        }
        return (int) Math.round(oy + 36);
    }

    /** Faint lines every 1000 game units, like the game's own minimap ruler. */
    private void grid(Graphics2D g, Projection p, MapCatalog.MapInfo map, boolean picture) {
        if (p.scale() * 1000 < 18) {
            return;
        }
        g.setColor(new Color(255, 255, 255, picture ? 14 : 20));
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
                    String gl = gateLabel(hero.mapId(), portal);
                    if (gl != null) {
                        return "Портал: " + gl + "  (" + portal.x() / 100 + "/" + portal.y() / 100 + ")";
                    }
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

    /**
     * In a galaxy gate a portal by its type: home (type 1, out of the gate) or on (the next stage, the next map);
     * {@code null} out of a gate (the user, 2026-11-15: «в АБГ порт слева показывает 3-1, а это в следующую волну»).
     */
    static String gateLabel(int mapId, WorldSnapshot.Portal p) {
        if (orbit.config.GateCatalog.byMap(mapId) == null) {
            return null;
        }
        return p.designId() == orbit.config.GateCatalog.HOME_PORTAL ? "домой" : "дальше";
    }
}
