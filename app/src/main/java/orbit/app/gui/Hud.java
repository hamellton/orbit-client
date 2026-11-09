package orbit.app.gui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Composite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import orbit.app.WorldFeed;
import orbit.world.Health;
import orbit.world.WorldSnapshot;
import orbit.world.WorldSnapshot.Ship;
import orbit.world.WorldSnapshot.ShipKind;
import orbit.world.maps.MapCatalog;

/**
 * Text and cards drawn over the minimap: action and supplies top left, connection and session
 * time top right, the map name in the middle, the hero bottom left, the target and the attackers
 * bottom right.
 */
final class Hud {

    static final int CARD_WIDTH = 250;
    private static final int MARGIN = 14;
    private static final Color CARD_BACKGROUND = new Color(16, 20, 26, 215);
    private static final Color TEXT = new Color(0xe2e8f0);
    private static final Color DIM = new Color(0x94a3b8);
    private static final Color HP_BAR = new Color(0x43a047);
    private static final Color SHIELD_BAR = new Color(0x1e88e5);
    private static final Color BAR_TRACK = new Color(0x2d3748);
    private static final int MAX_ATTACKER_ROWS = 4;

    private final Font base;

    Hud(Font base) {
        this.base = base;
    }

    /** Map name and hero state in the middle, under the map objects. */
    void paintBackground(Graphics2D g, int width, int height, WorldSnapshot w, MapCatalog catalog) {
        WorldSnapshot.Hero hero = w.hero();
        paintMapName(g, width, height, hero, catalog.info(hero.mapId()));
    }

    /** Texts and cards over the map objects. */
    void paint(Graphics2D g, int width, int height, WorldSnapshot w, WorldFeed feed) {
        WorldSnapshot.Hero hero = w.hero();
        paintTopLeft(g, w, feed);
        paintTopRight(g, width, feed);
        if (hero.id() == 0) {
            return;
        }
        paintHero(g, height, hero);
        paintTargetAndAttackers(g, width, height, w);
    }

    // ---- centre ----

    private void paintMapName(Graphics2D g, int width, int height, WorldSnapshot.Hero hero, MapCatalog.MapInfo map) {
        Composite old = g.getComposite();
        g.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.22f));
        g.setFont(base.deriveFont(Font.BOLD, 64f));
        g.setColor(TEXT);
        String name = hero.id() == 0 ? "—" : map.name();
        FontMetrics fm = g.getFontMetrics();
        g.drawString(name, (width - fm.stringWidth(name)) / 2, height / 2 + fm.getAscent() / 3);
        g.setComposite(old);
        if (hero.id() == 0) {
            return;
        }
        g.setFont(base.deriveFont(Font.PLAIN, 12f));
        g.setColor(DIM);
        String status = heroStatus(hero) + "   " + hero.x() / 100 + "/" + hero.y() / 100;
        fm = g.getFontMetrics();
        g.drawString(status, (width - fm.stringWidth(status)) / 2, height / 2 + 40);
    }

    static String heroStatus(WorldSnapshot.Hero h) {
        if (!h.alive()) {
            return "Уничтожен";
        }
        if (h.beacon().inRadiationZone()) {
            return "Радиация!";
        }
        if (h.beacon().inStation()) {
            return "На станции";
        }
        if (h.attackTargetId() != 0) {
            return "В бою";
        }
        if (!h.attackers().isEmpty()) {
            return "Под атакой";
        }
        if (h.moving()) {
            return "В полёте";
        }
        return h.beacon().inPeaceArea() ? "Мирная зона" : "Стоит";
    }

    // ---- top ----

    private void paintTopLeft(Graphics2D g, WorldSnapshot w, WorldFeed feed) {
        List<String> lines = new ArrayList<>();
        lines.add("Действие: " + feed.action());
        WorldSnapshot.Hero h = w.hero();
        if (h.id() != 0) {
            lines.add("Лазер " + item(w.selected("lasers")) + "   Ракеты " + item(w.selected("rockets")));
            if (h.cargoMax() > 0) {
                lines.add(String.format("Трюм %d/%d", h.cargoMax() - h.cargoFree(), h.cargoMax()));
            }
        }
        g.setFont(base.deriveFont(Font.PLAIN, 12f));
        FontMetrics fm = g.getFontMetrics();
        int textWidth = lines.stream().mapToInt(fm::stringWidth).max().orElse(0);
        g.setColor(CARD_BACKGROUND);
        g.fillRoundRect(MARGIN - 6, MARGIN - 2, textWidth + 12, lines.size() * 16 + 8, 8, 8);
        int y = MARGIN + 12;
        for (String line : lines) {
            g.setColor(y == MARGIN + 12 ? TEXT : DIM);
            g.drawString(line, MARGIN, y);
            y += 16;
        }
        paintGroup(g, MARGIN, y + 6, w);
    }

    /** One card per other group member: name (★ leader), level, map if elsewhere, hull and shield. */
    private void paintGroup(Graphics2D g, int x, int y, WorldSnapshot w) {
        WorldSnapshot.Group group = w.group();
        if (!group.exists()) {
            return;
        }
        WorldSnapshot.Hero h = w.hero();
        g.setFont(base.deriveFont(Font.PLAIN, 10f));
        g.setColor(DIM);
        g.drawString("Группа (" + group.members().size() + ")" + (group.leaderId() == h.id() ? " — вы лидер" : ""), x, y + 10);
        y += 16;
        for (WorldSnapshot.Member m : group.members()) {
            if (m.id() == h.id()) {
                continue;
            }
            int cardHeight = 50;
            card(g, x, y, cardHeight, m.loggedOut() ? Color.GRAY : MinimapPanel.ALLY);
            g.setFont(base.deriveFont(Font.BOLD, 12f));
            g.setColor(m.loggedOut() ? DIM : TEXT);
            g.drawString(clip(g, m.name() + (m.id() == group.leaderId() ? " ★" : ""), CARD_WIDTH - 90), x + 10, y + 15);
            g.setFont(base.deriveFont(Font.PLAIN, 10f));
            g.setColor(DIM);
            String where = m.loggedOut() ? "не в игре"
                    : m.mapId() != h.mapId() && m.mapId() != 0 ? MapCatalog.bundled().info(m.mapId()).name()
                    : String.format("%,.0f", Math.hypot(m.x() - h.x(), m.y() - h.y()));
            String right = (m.level() > 0 ? "ур. " + m.level() + "   " : "") + where + (m.cloaked() ? "  невид." : "");
            FontMetrics fm = g.getFontMetrics();
            g.drawString(right, x + CARD_WIDTH - 10 - fm.stringWidth(right), y + 15);
            if (m.health().known()) {
                bars(g, x + 10, y + 21, m.health());
            } else {
                g.drawString("HP неизвестно", x + 10, y + 33);
            }
            y += cardHeight + 6;
        }
    }

    private void paintTopRight(Graphics2D g, int width, WorldFeed feed) {
        g.setFont(base.deriveFont(Font.PLAIN, 12f));
        FontMetrics fm = g.getFontMetrics();
        String state = feed.state();
        long since = feed.sessionStartNanos();
        String time = since == 0 ? "" : "сессия " + clock(Duration.ofNanos(feed.now() - since));
        g.setColor(TEXT);
        g.drawString(state, width - MARGIN - fm.stringWidth(state), MARGIN + 12);
        g.setColor(DIM);
        g.drawString(time, width - MARGIN - fm.stringWidth(time), MARGIN + 28);
    }

    static String clock(Duration d) {
        long s = Math.max(0, d.toSeconds());
        return String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    // ---- bottom ----

    private void paintHero(Graphics2D g, int height, WorldSnapshot.Hero h) {
        int x = MARGIN;
        int y = height - MARGIN - 52;
        card(g, x, y, 52, MinimapPanel.HERO);
        g.setFont(base.deriveFont(Font.BOLD, 12f));
        g.setColor(TEXT);
        g.drawString(h.name(), x + 10, y + 15);
        g.setFont(base.deriveFont(Font.PLAIN, 10f));
        g.setColor(DIM);
        String right = (h.config() > 0 ? h.config() + "C" : "") + (h.level() > 0 ? "   ур. " + h.level() : "");
        FontMetrics fm = g.getFontMetrics();
        g.drawString(right, x + CARD_WIDTH - 10 - fm.stringWidth(right), y + 15);
        bars(g, x + 10, y + 22, h.health());
    }

    private void paintTargetAndAttackers(Graphics2D g, int width, int height, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int x = width - MARGIN - CARD_WIDTH;
        int y = height - MARGIN;
        int targetId = h.attackTargetId() != 0 ? h.attackTargetId() : h.selectedId();
        Ship target = w.ships().get(targetId);
        if (target != null) {
            y -= 64;
            Color accent = MinimapPanel.shipColor(target, h, w.group());
            card(g, x, y, 64, accent);
            g.setFont(base.deriveFont(Font.BOLD, 12f));
            g.setColor(accent);
            g.drawString(clip(g, MinimapPanel.label(target), CARD_WIDTH - 20), x + 10, y + 15);
            g.setFont(base.deriveFont(Font.PLAIN, 10f));
            g.setColor(DIM);
            String kind = (h.attackTargetId() != 0 ? "атакую" : "выбрана") + " · " + kindOf(target)
                    + (target.typeId().isBlank() ? "" : " " + target.typeId())
                    + String.format(" · %,.0f", target.distanceTo(h.x(), h.y()));
            g.drawString(clip(g, kind, CARD_WIDTH - 20), x + 10, y + 28);
            if (target.health().known()) {
                bars(g, x + 10, y + 34, target.health());
            } else {
                g.drawString("HP неизвестно", x + 10, y + 46);
            }
            y -= 6;
        }
        paintAttackers(g, x, y, w);
    }

    /** Attackers grouped by name (NPCs come in swarms), drawn upwards from {@code bottom}. */
    private void paintAttackers(Graphics2D g, int x, int bottom, WorldSnapshot w) {
        Map<String, List<Ship>> byName = new LinkedHashMap<>();
        for (int id : w.hero().attackers()) {
            Ship a = w.ships().get(id);
            if (a != null) {
                byName.computeIfAbsent(MinimapPanel.label(a), k -> new ArrayList<>()).add(a);
            }
        }
        if (byName.isEmpty()) {
            return;
        }
        int rows = Math.min(byName.size(), MAX_ATTACKER_ROWS);
        int h = 18 + rows * 28 + (byName.size() > rows ? 14 : 0);
        int y = bottom - h;
        card(g, x, y, h, MinimapPanel.ENEMY);
        g.setFont(base.deriveFont(Font.PLAIN, 10f));
        g.setColor(DIM);
        g.drawString("Атакуют меня: " + w.hero().attackers().size(), x + 10, y + 13);
        int ry = y + 18;
        int row = 0;
        for (Map.Entry<String, List<Ship>> e : byName.entrySet()) {
            if (row++ == rows) {
                g.setFont(base.deriveFont(Font.PLAIN, 10f));
                g.setColor(DIM);
                g.drawString("… и ещё " + (byName.size() - rows), x + 10, ry + 10);
                break;
            }
            List<Ship> ships = e.getValue();
            g.setFont(base.deriveFont(Font.BOLD, 10f));
            g.setColor(MinimapPanel.shipColor(ships.getFirst(), w.hero(), w.group()));
            g.drawString(clip(g, e.getKey() + (ships.size() > 1 ? "  ×" + ships.size() : ""), CARD_WIDTH - 20), x + 10, ry + 9);
            Ship weakest = ships.stream().filter(a -> a.health().known())
                    .min(Comparator.comparingDouble(a -> a.health().hpPercent())).orElse(null);
            int half = (CARD_WIDTH - 20) / 2 - 2;
            if (weakest != null) {
                bar(g, x + 10, ry + 13, half, weakest.health().hp(), weakest.health().hpMax(), HP_BAR, true);
                bar(g, x + 14 + half, ry + 13, half, weakest.health().shield(), weakest.health().shieldMax(), SHIELD_BAR, true);
            } else {
                g.setFont(base.deriveFont(Font.PLAIN, 9f));
                g.setColor(DIM);
                g.drawString("HP неизвестно", x + 10, ry + 21);
            }
            ry += 28;
        }
    }

    // ---- drawing helpers ----

    private static void card(Graphics2D g, int x, int y, int h, Color accent) {
        g.setColor(CARD_BACKGROUND);
        g.fillRoundRect(x, y, CARD_WIDTH, h, 8, 8);
        g.setColor(accent);
        g.fillRect(x, y + 4, 3, h - 8);
    }

    private void bars(Graphics2D g, int x, int y, Health h) {
        bar(g, x, y, CARD_WIDTH - 20, h.hp(), h.hpMax(), HP_BAR, false);
        bar(g, x, y + 13, CARD_WIDTH - 20, h.shield(), h.shieldMax(), SHIELD_BAR, false);
    }

    private void bar(Graphics2D g, int x, int y, int w, double now, double max, Color color, boolean percent) {
        int barHeight = percent ? 9 : 11;
        g.setColor(BAR_TRACK);
        g.fillRect(x, y, w, barHeight);
        if (max <= 0 || now < 0) {
            return;
        }
        g.setColor(color);
        g.fillRect(x, y, (int) Math.round(w * Math.min(1, now / max)), barHeight);
        g.setColor(Color.WHITE);
        g.setFont(base.deriveFont(Font.PLAIN, percent ? 8f : 9f));
        String text = percent ? String.format("%.0f%%", 100 * Math.min(1, now / max)) : String.format("%,.0f / %,.0f", now, max);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + barHeight - 2);
    }

    private static String kindOf(Ship s) {
        return s.kind() == ShipKind.NPC ? "NPC" : s.kind() == ShipKind.PET ? "питомец" : "игрок";
    }

    private static String item(WorldSnapshot.Item i) {
        return i == null ? "—" : String.format("%s ×%,.0f", i.id().replaceFirst("^ammunition_[a-z]+_", "").toUpperCase(), i.counter());
    }

    static String clip(Graphics2D g, String text, int width) {
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) <= width) {
            return text;
        }
        String t = text;
        while (t.length() > 1 && fm.stringWidth(t + "…") > width) {
            t = t.substring(0, t.length() - 1);
        }
        return t + "…";
    }
}
