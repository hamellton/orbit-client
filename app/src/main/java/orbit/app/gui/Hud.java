package orbit.app.gui;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.geom.RoundRectangle2D;
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
 * The account's state as cards of any width (status, ship, P.E.T., target, attackers, group, session
 * results) for the side panel, and the one-line strip drawn over a small map in the overview.
 */
final class Hud {

    static final Color CARD = new Color(0x161b24);
    static final Color CARD_EDGE = new Color(0x262d3a);
    static final Color TEXT = new Color(0xe6ebf2);
    static final Color DIM = new Color(0x8b96a8);
    static final Color HP_BAR = new Color(0x22c55e);
    static final Color SHIELD_BAR = new Color(0x3b82f6);
    static final Color BAR_TRACK = new Color(0x2a3140);
    static final int PAD = 12;
    static final int LINE = 17;
    static final int BAR = 14;
    private static final int MAX_ATTACKER_ROWS = 4;

    /** One card: its height at the given width and how to draw it. */
    interface Card {
        int height(int width);

        void paint(Graphics2D g, int x, int y, int width);
    }

    private final Font base;

    Hud(Font base) {
        this.base = base;
    }

    Font font(int style, float size) {
        return base.deriveFont(style, size);
    }

    // ---- the side panel's cards ----

    List<Card> cards(WorldSnapshot w, WorldFeed feed, String config) {
        List<Card> out = new ArrayList<>();
        out.add(status(w, feed, config));
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0) {
            return out;
        }
        out.add(hero(h, w.inventory()));
        Card target = target(w);
        if (target != null) {
            out.add(target);
        }
        Card attackers = attackers(w);
        if (attackers != null) {
            out.add(attackers);
        }
        if (w.pet().owned()) {
            out.add(pet(w.pet()));
        }
        if (w.group().exists()) {
            out.add(group(w));
        }
        if (feed.stats() != null || !w.rewards().equals(WorldSnapshot.Rewards.NONE)) {
            out.add(stats(w, feed));
        }
        return out;
    }

    private Card status(WorldSnapshot w, WorldFeed feed, String config) {
        AccountStatus st = AccountStatus.of(feed);
        List<String> lines = new ArrayList<>();
        WorldSnapshot.Hero h = w.hero();
        if (config != null) {
            lines.add("Конфиг: " + config);
        }
        if (h.id() != 0) {
            lines.add(MapCatalog.bundled().info(h.mapId()).name() + "  ·  " + h.x() / 100 + "/" + h.y() / 100
                    + "  ·  " + heroStatus(h));
            lines.add("Лазер: " + item(w.selected("lasers")));
            lines.add("Ракеты: " + item(w.selected("rockets")));
        } else {
            lines.add(feed.state());
        }
        long since = feed.sessionStartNanos();
        String time = since == 0 ? "" : clock(Duration.ofNanos(feed.now() - since));
        Font titleFont = font(Font.BOLD, 13f);
        Font textFont = font(Font.PLAIN, 12f);
        Font smallFont = font(Font.PLAIN, 11f);
        return new Card() {
            int timeWidth() {
                return time.isEmpty() ? 0 : measure(smallFont).stringWidth(time) + 10;
            }

            @Override
            public int height(int width) {
                int inner = width - 2 * PAD;
                int n = wrap(measure(titleFont), feed.action(), inner - timeWidth()).size();
                int m = 0;
                for (String line : lines) {
                    m += wrap(measure(textFont), line, inner).size();
                }
                return PAD + n * LINE + 4 + m * LINE + PAD - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), st.color);
                int inner = width - 2 * PAD;
                g.setFont(smallFont);
                g.setColor(DIM);
                g.drawString(time, x + width - PAD - g.getFontMetrics().stringWidth(time), y + PAD + 12);
                g.setFont(titleFont);
                g.setColor(TEXT);
                int ty = y + PAD + 12;
                for (String part : wrap(g.getFontMetrics(), feed.action(), inner - timeWidth())) {
                    g.drawString(part, x + PAD, ty);
                    ty += LINE;
                }
                ty += 4;
                g.setFont(textFont);
                for (String line : lines) {
                    g.setColor(line.startsWith("Конфиг") ? TEXT : DIM);
                    for (String part : wrap(g.getFontMetrics(), line, inner)) {
                        g.drawString(part, x + PAD, ty);
                        ty += LINE;
                    }
                }
            }
        };
    }

    /**
     * The pilot and the ship, as the game shows them: name, ship, level; hull, shield (and nano hull);
     * credits, uridium, experience, honour, speed, cargo, configuration; the booty keys.
     */
    private Card hero(WorldSnapshot.Hero h, WorldSnapshot.Inventory inv) {
        String name = h.name() + (h.shipType().isBlank() ? "" : "  " + shipName(h.shipType()));
        String right = (h.level() > 0 ? "ур. " + h.level() : "") + (h.premium() ? "  ·  премиум" : "");
        List<String[]> stats = new ArrayList<>();
        stats.add(new String[] {"Кредиты", money(h.credits())});
        stats.add(new String[] {"Уридий", money(h.uridium())});
        stats.add(new String[] {"Опыт", money(h.experience())});
        stats.add(new String[] {"Честь", money(h.honor())});
        stats.add(new String[] {"Скорость", h.speed() > 0 ? String.valueOf(h.speed()) : "—"});
        stats.add(new String[] {"Трюм", h.cargoMax() > 0
                ? String.format("%,d / %,d", h.cargoMax() - h.cargoFree(), h.cargoMax()) : "—"});
        stats.add(new String[] {"Конфигурация", h.config() > 0 ? String.valueOf(h.config()) : "—"});
        String keys = keys(inv.bootyKeys());
        boolean nano = h.health().nanoHullMax() > 0;
        Font keyFont = font(Font.PLAIN, 11f);
        return new Card() {
            int cols(int width) {
                return width >= 300 ? 2 : 1;
            }

            int statRows(int width) {
                return (stats.size() + cols(width) - 1) / cols(width);
            }

            @Override
            public int height(int width) {
                int bars = (nano ? 3 : 2) * (BAR + 4);
                int keyRows = wrap(measure(keyFont), keys, width - 2 * PAD).size();
                return PAD + titleLines(name, right, width) * LINE + bars + 6 + statRows(width) * LINE + 4
                        + keyRows * (LINE - 2) + PAD - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.HERO);
                int inner = width - 2 * PAD;
                int rows = titleRow(g, x, y, width, name, right, TEXT);
                int by = y + PAD + rows * LINE;
                Health hp = h.health();
                bar(g, x + PAD, by, inner, BAR, hp.hp(), hp.hpMax(), HP_BAR, false);
                bar(g, x + PAD, by + BAR + 4, inner, BAR, hp.shield(), hp.shieldMax(), SHIELD_BAR, false);
                if (nano) {
                    bar(g, x + PAD, by + 2 * (BAR + 4), inner, BAR, hp.nanoHull(), hp.nanoHullMax(), NANO_BAR, false);
                }
                int sy = by + (nano ? 3 : 2) * (BAR + 4) + 6 + 12;
                int n = cols(width);
                int cw = (inner - (n - 1) * 14) / n;
                g.setFont(font(Font.PLAIN, 12f));
                FontMetrics fm = g.getFontMetrics();
                for (int i = 0; i < stats.size(); i++) {
                    int cx = x + PAD + (i % n) * (cw + 14);
                    int cy = sy + (i / n) * LINE;
                    String value = stats.get(i)[1];
                    g.setColor(DIM);
                    g.drawString(clip(g, stats.get(i)[0], cw - fm.stringWidth(value) - 6), cx, cy);
                    g.setColor(TEXT);
                    g.drawString(value, cx + cw - fm.stringWidth(value), cy);
                }
                int ky = sy + statRows(width) * LINE + 4;
                g.setFont(keyFont);
                g.setColor(DIM);
                for (String part : wrap(g.getFontMetrics(), keys, inner)) {
                    g.drawString(part, x + PAD, ky);
                    ky += LINE - 2;
                }
            }
        };
    }

    static final Color NANO_BAR = new Color(0xf59e0b);

    /** The client's booty key codes ({@code ServerCommands.BOOTY_KEYS_*}). */
    private static final Map<String, String> KEY_NAMES = Map.ofEntries(
            Map.entry("BK", "зелёные"), Map.entry("BKB", "синие"), Map.entry("BKR", "красные"),
            Map.entry("BKS", "серебряные"), Map.entry("BKM", "маскарад"), Map.entry("BKPR", "Прометей"),
            Map.entry("BKBB", "чёрные"), Map.entry("BKBLC", "шифр ЕБГ"), Map.entry("BKBLD", "дешифратор ЕБГ"),
            Map.entry("BKP", "процветания"), Map.entry("BKA", "астральные"), Map.entry("BKAP", "астрал-прайм"),
            Map.entry("BKE", "эмпирей"), Map.entry("BKL", "люцент"), Map.entry("BKPG", "Персей"),
            Map.entry("BKLL", "лавровые"), Map.entry("BKEB", "эхо синие"), Map.entry("BKEG", "эхо зелёные"),
            Map.entry("BKEP", "эхо фиолетовые"), Map.entry("BKER", "эхо красные"), Map.entry("BKAPO", "Аполлон"));

    /** «Ключи: зелёные 12  ·  синие 3», the ones the account has, the common ones first. */
    static String keys(Map<String, Integer> keys) {
        List<String> order = List.of("BK", "BKB", "BKR", "BKS", "BKBB");
        StringBuilder sb = new StringBuilder();
        java.util.function.BiConsumer<String, Integer> add = (code, n) -> {
            if (n != null && n > 0) {
                sb.append(sb.isEmpty() ? "" : "  ·  ").append(KEY_NAMES.getOrDefault(code, code)).append(" ").append(n);
            }
        };
        order.forEach(code -> add.accept(code, keys.get(code)));
        keys.entrySet().stream().filter(e -> !order.contains(e.getKey())).sorted(Map.Entry.comparingByKey())
                .forEach(e -> add.accept(e.getKey(), e.getValue()));
        return "Ключи: " + (sb.isEmpty() ? "нет" : sb);
    }

    private static String money(double v) {
        return v < 0 ? "—" : String.format("%,.0f", v);
    }

    /** The title and its right-hand note in one row, or the note under the title when it does not fit. */
    int titleLines(String title, String right, int width) {
        int need = measure(font(Font.BOLD, 13f)).stringWidth(title) + 12 + measure(font(Font.PLAIN, 11f)).stringWidth(right);
        return right.isEmpty() || need <= width - 2 * PAD ? 1 : 2;
    }

    private static final java.awt.image.BufferedImage SCRATCH =
            new java.awt.image.BufferedImage(1, 1, java.awt.image.BufferedImage.TYPE_INT_ARGB);

    /** Metrics of a font outside painting (to size a card before it is drawn). */
    static FontMetrics measure(Font f) {
        Graphics2D g = SCRATCH.createGraphics();
        try {
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING, java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_FRACTIONALMETRICS, java.awt.RenderingHints.VALUE_FRACTIONALMETRICS_ON);
            return g.getFontMetrics(f);
        } finally {
            g.dispose();
        }
    }

    /** Words onto lines no wider than {@code width}; a word longer than a line is cut where it must. */
    static List<String> wrap(FontMetrics fm, String text, int width) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        width = Math.max(40, width);
        StringBuilder line = new StringBuilder();
        for (String word : text.split(" ")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (fm.stringWidth(candidate) <= width) {
                line.setLength(0);
                line.append(candidate);
                continue;
            }
            if (!line.isEmpty()) {
                out.add(line.toString().strip());
                line.setLength(0);
            }
            String rest = word;
            while (fm.stringWidth(rest) > width && rest.length() > 1) {
                int cut = rest.length() - 1;
                while (cut > 1 && fm.stringWidth(rest.substring(0, cut)) > width) {
                    cut--;
                }
                out.add(rest.substring(0, cut));
                rest = rest.substring(cut);
            }
            line.append(rest);
        }
        if (!line.isEmpty() || out.isEmpty()) {
            out.add(line.toString().strip());
        }
        return out;
    }

    private Card pet(WorldSnapshot.Pet pet) {
        boolean out = pet.active() && pet.id() != 0;
        String right = gearName(pet.gear()) + (pet.level() > 0 ? "  ·  ур. " + pet.level() : "");
        return new Card() {
            @Override
            public int height(int width) {
                return out ? PAD + titleLines(pet.name(), right, width) * LINE + 2 * BAR + 4 + (pet.fuel() >= 0 ? LINE : 0) + PAD
                        : PAD + LINE + PAD - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.PET);
                if (!out) {
                    String why = !pet.alive() ? "Питомец уничтожен" : !pet.hasFuel() ? "Питомец: нет топлива" : "Питомец не выпущен";
                    g.setFont(font(Font.PLAIN, 12f));
                    g.setColor(DIM);
                    g.drawString(why, x + PAD, y + PAD + 12);
                    return;
                }
                int rows = titleRow(g, x, y, width, pet.name(), right, TEXT);
                if (pet.health().known()) {
                    bars(g, x + PAD, y + PAD + rows * LINE, width - 2 * PAD, pet.health());
                }
                if (pet.fuel() >= 0) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString(clip(g, String.format("Топливо %,d / %,d", pet.fuel(), pet.fuelMax())
                                    + (pet.heatMax() > 0 ? String.format("  ·  нагрев %.0f / %.0f", pet.heat(), pet.heatMax()) : ""),
                            width - 2 * PAD), x + PAD, y + PAD + rows * LINE + 2 * BAR + 4 + 13);
                }
            }
        };
    }

    private Card target(WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int targetId = h.attackTargetId() != 0 ? h.attackTargetId() : h.selectedId();
        Ship t = w.ships().get(targetId);
        if (t == null) {
            return null;
        }
        Color accent = MinimapPanel.shipColor(t, h, w.group());
        String kind = (h.attackTargetId() != 0 ? "Атакую" : "Выбрана") + "  ·  " + kindOf(t)
                + (t.typeId().isBlank() ? "" : "  " + shipName(t.typeId())) + String.format("  ·  %,.0f", t.distanceTo(h.x(), h.y()));
        String name = MinimapPanel.label(t);
        Font nameFont = font(Font.BOLD, 13f);
        Font kindFont = font(Font.PLAIN, 11f);
        return new Card() {
            @Override
            public int height(int width) {
                int inner = width - 2 * PAD;
                int rows = wrap(measure(nameFont), name, inner).size() + wrap(measure(kindFont), kind, inner).size();
                return PAD + rows * LINE + (t.health().known() ? 2 * BAR + 4 + 2 : 0) + PAD - 2;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), accent);
                int inner = width - 2 * PAD;
                int ty = y + PAD + 12;
                g.setFont(nameFont);
                g.setColor(accent);
                for (String part : wrap(g.getFontMetrics(), name, inner)) {
                    g.drawString(part, x + PAD, ty);
                    ty += LINE;
                }
                g.setFont(kindFont);
                g.setColor(DIM);
                for (String part : wrap(g.getFontMetrics(), kind, inner)) {
                    g.drawString(part, x + PAD, ty);
                    ty += LINE;
                }
                if (t.health().known()) {
                    bars(g, x + PAD, ty - 12 + 2, inner, t.health());
                }
            }
        };
    }

    private Card attackers(WorldSnapshot w) {
        Map<String, List<Ship>> byName = new LinkedHashMap<>();
        for (int id : w.hero().attackers()) {
            Ship a = w.ships().get(id);
            if (a != null) {
                byName.computeIfAbsent(MinimapPanel.label(a), k -> new ArrayList<>()).add(a);
            }
        }
        if (byName.isEmpty()) {
            return null;
        }
        int rows = Math.min(byName.size(), MAX_ATTACKER_ROWS);
        boolean more = byName.size() > rows;
        int rowH = LINE + 12;
        return new Card() {
            @Override
            public int height(int width) {
                return PAD + LINE + rows * rowH + (more ? LINE : 0) + PAD - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.ENEMY);
                g.setFont(font(Font.PLAIN, 11f));
                g.setColor(DIM);
                g.drawString("Атакуют меня: " + w.hero().attackers().size(), x + PAD, y + PAD + 10);
                int ry = y + PAD + LINE;
                int row = 0;
                for (Map.Entry<String, List<Ship>> e : byName.entrySet()) {
                    if (row++ == rows) {
                        break;
                    }
                    List<Ship> ships = e.getValue();
                    g.setFont(font(Font.BOLD, 12f));
                    g.setColor(MinimapPanel.shipColor(ships.getFirst(), w.hero(), w.group()));
                    g.drawString(clip(g, e.getKey() + (ships.size() > 1 ? "  ×" + ships.size() : ""), width - 2 * PAD),
                            x + PAD, ry + 11);
                    Ship weakest = ships.stream().filter(a -> a.health().known())
                            .min(Comparator.comparingDouble(a -> a.health().hpPercent())).orElse(null);
                    int half = (width - 2 * PAD - 6) / 2;
                    if (weakest != null) {
                        bar(g, x + PAD, ry + 15, half, 9, weakest.health().hp(), weakest.health().hpMax(), HP_BAR, true);
                        bar(g, x + PAD + half + 6, ry + 15, half, 9, weakest.health().shield(), weakest.health().shieldMax(),
                                SHIELD_BAR, true);
                    }
                    ry += rowH;
                }
                if (more) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString("… и ещё " + (byName.size() - rows), x + PAD, ry + 11);
                }
            }
        };
    }

    private Card group(WorldSnapshot w) {
        WorldSnapshot.Group group = w.group();
        WorldSnapshot.Hero h = w.hero();
        List<WorldSnapshot.Member> others = group.members().stream().filter(m -> m.id() != h.id()).toList();
        int rowH = LINE + 2 * 9 + 10;
        return new Card() {
            @Override
            public int height(int width) {
                return PAD + LINE + others.size() * rowH + PAD - 6;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.ALLY);
                g.setFont(font(Font.PLAIN, 11f));
                g.setColor(DIM);
                g.drawString("Группа (" + group.members().size() + ")" + (group.leaderId() == h.id() ? "  ·  вы лидер" : ""),
                        x + PAD, y + PAD + 10);
                int ry = y + PAD + LINE;
                for (WorldSnapshot.Member m : others) {
                    String where = m.loggedOut() ? "не в игре"
                            : m.mapId() != h.mapId() && m.mapId() != 0 ? MapCatalog.bundled().info(m.mapId()).name()
                            : String.format("%,.0f", Math.hypot(m.x() - h.x(), m.y() - h.y()));
                    String right = (m.level() > 0 ? "ур. " + m.level() + "  ·  " : "") + where;
                    g.setFont(font(Font.BOLD, 12f));
                    g.setColor(m.loggedOut() ? DIM : TEXT);
                    FontMetrics fm = g.getFontMetrics(font(Font.PLAIN, 11f));
                    g.drawString(clip(g, m.name() + (m.id() == group.leaderId() ? " ★" : ""),
                            width - 2 * PAD - fm.stringWidth(right) - 8), x + PAD, ry + 11);
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString(right, x + width - PAD - fm.stringWidth(right), ry + 11);
                    if (m.health().known()) {
                        bar(g, x + PAD, ry + 15, width - 2 * PAD, 9, m.health().hp(), m.health().hpMax(), HP_BAR, true);
                        bar(g, x + PAD, ry + 26, width - 2 * PAD, 9, m.health().shield(), m.health().shieldMax(), SHIELD_BAR, true);
                    }
                    ry += rowH;
                }
            }
        };
    }

    private Card stats(WorldSnapshot w, WorldFeed feed) {
        List<String> lines = orbit.app.SessionSummary.lines(w.rewards(), feed.stats(), feed.sessionHours());
        Font textFont = font(Font.PLAIN, 12f);
        return new Card() {
            @Override
            public int height(int width) {
                int rows = 0;
                for (String line : lines) {
                    rows += wrap(measure(textFont), line, width - 2 * PAD).size();
                }
                return PAD + LINE + rows * (LINE - 1) + PAD - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), new Color(0xa78bfa));
                g.setFont(font(Font.PLAIN, 11f));
                g.setColor(DIM);
                g.drawString("Итоги сессии", x + PAD, y + PAD + 10);
                g.setFont(textFont);
                int ty = y + PAD + LINE + 11;
                for (int i = 0; i < lines.size(); i++) {
                    g.setColor(i < 4 ? TEXT : DIM);
                    for (String part : wrap(g.getFontMetrics(), lines.get(i), width - 2 * PAD)) {
                        g.drawString(part, x + PAD, ty);
                        ty += LINE - 1;
                    }
                }
            }
        };
    }

    // ---- the overview ----

    /**
     * Over a small map, top left: whom the ship fights (or has selected) with hull, shield and distance,
     * and how many attack it. Nothing when there is no target and nobody attacks.
     */
    void targetChip(Graphics2D g, int x, int y, int maxWidth, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int targetId = h.attackTargetId() != 0 ? h.attackTargetId() : h.selectedId();
        Ship t = w.ships().get(targetId);
        int attackers = h.attackers().size();
        if (t == null && attackers == 0) {
            return;
        }
        int width = Math.max(160, Math.min(maxWidth, 320));
        int inner = width - 20;
        boolean bars = t != null && t.health().known();
        int height = 10 + (t != null ? 16 + 14 : 0) + (bars ? 2 * 9 + 6 : 0) + (attackers > 0 ? 15 : 0) + 6;
        g.setColor(new Color(10, 12, 18, 215));
        g.fill(new RoundRectangle2D.Double(x, y, width, height, 10, 10));
        g.setColor(CARD_EDGE);
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, width - 1, height - 1, 10, 10));
        int ty = y + 10;
        if (t != null) {
            Color accent = MinimapPanel.shipColor(t, h, w.group());
            g.setColor(accent);
            g.fill(new RoundRectangle2D.Double(x, y + 6, 3, height - 12, 3, 3));
            g.setFont(font(Font.BOLD, 12f));
            g.drawString(clip(g, MinimapPanel.label(t), inner), x + 10, ty + 12);
            ty += 16;
            g.setFont(font(Font.PLAIN, 11f));
            g.setColor(DIM);
            String kind = (h.attackTargetId() != 0 ? "атакую" : "выбрана") + String.format("  ·  %,.0f", t.distanceTo(h.x(), h.y()))
                    + (t.lockedByOther() ? "  ·  бьёт другой игрок" : "");
            g.drawString(clip(g, kind, inner), x + 10, ty + 10);
            ty += 14;
            if (bars) {
                bar(g, x + 10, ty + 2, inner, 9, t.health().hp(), t.health().hpMax(), HP_BAR, true);
                bar(g, x + 10, ty + 13, inner, 9, t.health().shield(), t.health().shieldMax(), SHIELD_BAR, true);
                ty += 2 * 9 + 6;
            }
        }
        if (attackers > 0) {
            g.setFont(font(Font.PLAIN, 11f));
            g.setColor(MinimapPanel.ENEMY);
            g.drawString("Атакуют меня: " + attackers, x + 10, ty + 11);
        }
    }


    /** Over a small map: title and config on the left, status on the right, action, hull and shield. */
    void strip(Graphics2D g, int x, int y, int width, WorldFeed feed, String config) {
        int h = 46;
        int top = y - h;
        g.setPaint(new GradientPaint(0, top - 18, new Color(10, 12, 18, 0), 0, top + 10, new Color(10, 12, 18, 225)));
        g.fillRect(x, top - 18, width, h + 18);
        AccountStatus st = AccountStatus.of(feed);
        g.setFont(font(Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        String chip = st.label;
        int chipW = fm.stringWidth(chip) + 18;
        g.setColor(st.color);
        g.fillOval(x + width - chipW - 8, top + 7, 8, 8);
        g.setColor(TEXT);
        g.drawString(chip, x + width - chipW + 4, top + 15);
        String title = feed.title() + (config != null ? "  ·  " + config : "");
        g.drawString(clip(g, title, width - chipW - 24), x + 10, top + 15);
        g.setFont(font(Font.PLAIN, 11f));
        g.setColor(DIM);
        g.drawString(clip(g, feed.action(), width - 20), x + 10, top + 30);
        WorldSnapshot.Hero hero = feed.snapshot().hero();
        if (hero.id() != 0 && hero.health().known()) {
            int bw = (width - 26) / 2;
            bar(g, x + 10, top + 36, bw, 6, hero.health().hp(), hero.health().hpMax(), HP_BAR, false);
            bar(g, x + 16 + bw, top + 36, bw, 6, hero.health().shield(), hero.health().shieldMax(), SHIELD_BAR, false);
        }
    }

    // ---- drawing helpers ----

    private void frame(Graphics2D g, int x, int y, int w, int h, Color accent) {
        RoundRectangle2D.Double r = new RoundRectangle2D.Double(x, y, w, h, 12, 12);
        g.setColor(CARD);
        g.fill(r);
        g.setColor(CARD_EDGE);
        g.setStroke(new BasicStroke(1f));
        g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, 12, 12));
        g.setColor(accent);
        g.fill(new RoundRectangle2D.Double(x, y + 8, 3, h - 16, 3, 3));
    }

    /** @return the rows taken: 1, or 2 when the note went under the title */
    private int titleRow(Graphics2D g, int x, int y, int width, String title, String right, Color color) {
        int rows = titleLines(title, right, width);
        g.setFont(font(Font.PLAIN, 11f));
        FontMetrics small = g.getFontMetrics();
        int rw = small.stringWidth(right);
        g.setColor(DIM);
        if (rows == 1) {
            g.drawString(right, x + width - PAD - rw, y + PAD + 11);
        } else {
            g.drawString(clip(g, right, width - 2 * PAD), x + PAD, y + PAD + 11 + LINE);
        }
        g.setFont(font(Font.BOLD, 13f));
        g.setColor(color);
        g.drawString(clip(g, title, rows == 1 ? width - 2 * PAD - rw - 8 : width - 2 * PAD), x + PAD, y + PAD + 11);
        return rows;
    }

    private void bars(Graphics2D g, int x, int y, int w, Health h) {
        bar(g, x, y, w, BAR, h.hp(), h.hpMax(), HP_BAR, false);
        bar(g, x, y + BAR + 4, w, BAR, h.shield(), h.shieldMax(), SHIELD_BAR, false);
    }

    private void bar(Graphics2D g, int x, int y, int w, int barHeight, double now, double max, Color color, boolean percent) {
        g.setColor(BAR_TRACK);
        g.fill(new RoundRectangle2D.Double(x, y, w, barHeight, barHeight, barHeight));
        if (max <= 0 || now < 0) {
            return;
        }
        double share = Math.min(1, now / max);
        g.setColor(color);
        g.fill(new RoundRectangle2D.Double(x, y, Math.max(barHeight, w * share), barHeight, barHeight, barHeight));
        if (barHeight < 9) {
            return;
        }
        g.setColor(Color.WHITE);
        g.setFont(font(Font.PLAIN, barHeight >= 13 ? 10f : 8f));
        String text = percent || w < 150 ? String.format("%.0f%%", 100 * share) : String.format("%,.0f / %,.0f", now, max);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (barHeight + fm.getAscent() - fm.getDescent()) / 2);
    }

    static String heroStatus(WorldSnapshot.Hero h) {
        if (!h.alive()) {
            return "уничтожен";
        }
        if (h.beacon().inRadiationZone()) {
            return "радиация!";
        }
        if (h.beacon().inStation()) {
            return "на станции";
        }
        if (h.attackTargetId() != 0) {
            return "в бою";
        }
        if (!h.attackers().isEmpty()) {
            return "под атакой";
        }
        if (h.moving()) {
            return "в полёте";
        }
        return h.beacon().inPeaceArea() ? "мирная зона" : "стоит";
    }

    static String clock(Duration d) {
        long s = Math.max(0, d.toSeconds());
        return String.format("%02d:%02d:%02d", s / 3600, s / 60 % 60, s % 60);
    }

    /** Russian names of the P.E.T. modes ({@code PetGearTypeModule}). */
    static String gearName(int gear) {
        return switch (gear) {
            case 1 -> "пассивный";
            case 2 -> "защита";
            case 4 -> "сбор ящиков";
            case 5 -> "сбор ресурсов";
            case 6 -> "поиск врагов";
            case 7 -> "поиск ресурсов";
            case 8 -> "торговля";
            case 9 -> "саморемонт";
            case 10 -> "камикадзе";
            case 11 -> "ремонт корабля";
            case 12 -> "комбо-защита";
            case 14 -> "жертва";
            case 15 -> "перенацеливание";
            case 16 -> "HP-связь";
            case 17 -> "мега-мина";
            case 18 -> "боевой маяк";
            case 19 -> "маяк HP";
            case 20 -> "сброс нагрева";
            case -1 -> "";
            default -> "режим " + gear;
        };
    }

    /** {@code ship_spectrum} → {@code Spectrum}, {@code ship_solace-plus} → {@code Solace Plus}. */
    static String shipName(String typeId) {
        String t = typeId.replaceFirst("^ship_", "").replace('-', ' ').replace('_', ' ').strip();
        StringBuilder sb = new StringBuilder();
        for (String w : t.split(" +")) {
            if (!w.isEmpty()) {
                sb.append(sb.isEmpty() ? "" : " ").append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
            }
        }
        return sb.toString();
    }

    private static String kindOf(Ship s) {
        return s.kind() == ShipKind.NPC ? "NPC" : s.kind() == ShipKind.PET ? "питомец" : "игрок";
    }

    private static String item(WorldSnapshot.Item i) {
        return i == null ? "—" : String.format("%s  ×%,.0f", i.id().replaceFirst("^ammunition_[a-z]+_", "").toUpperCase(), i.counter());
    }

    static String clip(Graphics2D g, String text, int width) {
        FontMetrics fm = g.getFontMetrics();
        if (text == null) {
            return "";
        }
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
