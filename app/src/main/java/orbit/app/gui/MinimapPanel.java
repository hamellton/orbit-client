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
    static final Color ZONE_DANGER = new Color(255, 60, 60, 60);
    static final Color ZONE_BLOCKED = new Color(160, 160, 160, 70);
    static final Color ZONE_OTHER = new Color(120, 160, 255, 28);
    /** Faction colours of stations: MMO, EIC, VRU. */
    static final Color[] FACTION = {new Color(0x9e9e9e), new Color(0xe53935), new Color(0x1e88e5), new Color(0x43a047)};

    private final WorldFeed feed;
    private final MapCatalog catalog = MapCatalog.bundled();

    MinimapPanel(WorldFeed feed) {
        this.feed = feed;
        setBackground(BACKGROUND);
        setPreferredSize(new Dimension(900, 562));
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
        int pad = 12;
        double scale = Math.min((getWidth() - 2.0 * pad) / map.width(), (getHeight() - 2.0 * pad) / map.height());
        double ox = (getWidth() - map.width() * scale) / 2;
        double oy = (getHeight() - map.height() * scale) / 2;
        Projection p = new Projection(ox, oy, scale);

        g.setColor(BORDER);
        g.setStroke(new BasicStroke(1f));
        g.draw(new Rectangle2D.Double(ox, oy, map.width() * scale, map.height() * scale));
        g.setFont(getFont().deriveFont(Font.PLAIN, 11f));
        g.drawString(hero.id() == 0 ? "нет данных" : map.name() + "  " + hero.x() / 100 + "/" + hero.y() / 100,
                (int) ox + 4, (int) oy + 14);

        for (WorldSnapshot.Zone z : w.zones().values()) {
            if (!z.active()) {
                continue;
            }
            Shape s = zoneShape(z, p);
            if (s == null) {
                continue;
            }
            g.setColor(z.type() == POITypeModule.POITYPE_RADIATION || z.type() == POITypeModule.POITYPE_DAMAGE ? ZONE_DANGER
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
            g.fill(new Rectangle2D.Double(p.x(a.x()) - 3, p.y(a.y()) - 3, 6, 6));
        }
        g.setColor(PORTAL);
        for (WorldSnapshot.Portal portal : w.portals().values()) {
            if (portal.visibleOnMinimap()) {
                g.draw(new Ellipse2D.Double(p.x(portal.x()) - 6, p.y(portal.y()) - 6, 12, 12));
            }
        }
        for (WorldSnapshot.Collectable c : w.collectables().values()) {
            g.setColor(c.kind() == WorldSnapshot.CollectableKind.BOX ? BOX : ORE);
            g.fill(new Rectangle2D.Double(p.x(c.x()) - 1.5, p.y(c.y()) - 1.5, 3, 3));
        }
        for (Ship s : w.ships().values()) {
            g.setColor(shipColor(s, hero, w.group()));
            double r = s.kind() == ShipKind.PET ? 2 : 3;
            g.fill(new Ellipse2D.Double(p.x(s.x()) - r, p.y(s.y()) - r, 2 * r, 2 * r));
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
                g.draw(new Ellipse2D.Double(p.x(target.x()) - 6, p.y(target.y()) - 6, 12, 12));
            }
            g.setColor(hero.alive() ? HERO : Color.GRAY);
            g.fill(new Ellipse2D.Double(hx - 4, hy - 4, 8, 8));
        }
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
