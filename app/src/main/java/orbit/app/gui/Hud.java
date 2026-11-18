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

    static final Color CARD = new Color(0x171e29);
    static final Color CARD_EDGE = new Color(0x313c4e);
    static final Color TEXT = Theme.TEXT;
    static final Color DIM = Theme.DIM;
    static final Color HP_BAR = new Color(0x3fa864);
    static final Color SHIELD_BAR = new Color(0x4f82d1);
    static final Color BAR_TRACK = new Color(0x232a36);
    static final int PAD = 12;
    static final int LINE = 19;
    static final int BAR = 14;

    /** Sizes of this renderer: the normal ones, or tighter for cards on a small map. */
    final int pad;
    final int line;
    /** From a heading's top to the first row under its rule: a little more than a row (the user: «отступа не хватает»). */
    final int head;
    final int bar;
    private final float fontScale;
    private static final int MAX_ATTACKER_ROWS = 4;

    /** One card: its height at the given width and how to draw it. */
    interface Card {
        int height(int width);

        void paint(Graphics2D g, int x, int y, int width);
    }

    private final Font base;

    Hud(Font base) {
        this(base, false);
    }

    /**
     * Cards drawn over the map (the user, 2026-11-16: «карточки на карте — компактно»): see-through, no frame, the
     * strip's duplicates left out (state, target), the ship and the results short, every attacker on one line.
     */
    private boolean onMap;

    Hud(Font base, boolean compact, boolean onMap) {
        this(base, compact);
        this.onMap = onMap;
    }

    /** @param compact smaller type, padding and bars, to fit cards on a small map */
    Hud(Font base, boolean compact) {
        this.base = base;
        this.pad = compact ? 8 : PAD;
        this.line = compact ? 15 : LINE;
        this.head = line + (compact ? 4 : 8);
        this.bar = compact ? 10 : BAR;
        this.fontScale = compact ? 0.86f : 1f;
    }

    /**
     * Fonts made once and kept: on Windows FlatLaf's base font is a composite with fallbacks, and each
     * {@code deriveFont} to another style makes a new composite font with its own glyph caches (soft references, so
     * only memory pressure frees them) — derived on every frame they took the VPS program to 3.6 GB in an hour
     * (live 2026-11-16: 517 000 CompositeFont objects, all from {@link #boostPills}).
     */
    private final java.util.Map<Long, Font> fonts = new java.util.concurrent.ConcurrentHashMap<>();

    Font font(int style, float size) {
        float s = size * fontScale;
        return fonts.computeIfAbsent(((long) style << 32) | Float.floatToIntBits(s), k -> base.deriveFont(style, s));
    }

    /** The card headings' font: bold, a little spaced. */
    private Font headingFont() {
        Font f = fonts.get(-1L);
        if (f == null) {
            f = font(Font.BOLD, 10.5f).deriveFont(Map.of(java.awt.font.TextAttribute.TRACKING, 0.08f));
            fonts.put(-1L, f);
        }
        return f;
    }

    // ---- the side panel's cards ----

    /** Every block there is something to show for, in the blocks' order. */
    Map<ViewSettings.Block, Card> blocks(WorldSnapshot w, WorldFeed feed, String config) {
        Map<ViewSettings.Block, Card> out = new java.util.EnumMap<>(ViewSettings.Block.class);
        // on the map the bottom strip tells the state: the card only when put there on purpose («На карте», «И там, и
        // там»), not just because the side panel is hidden
        if (!onMap || ViewSettings.shared().place(ViewSettings.Block.STATUS).map()) {
            out.put(ViewSettings.Block.STATUS, status(w, feed, config));
        }
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0) {
            return out;
        }
        out.put(ViewSettings.Block.SHIP, onMap ? heroShort(h, w.inventory(), w.items()) : hero(h, w.inventory(), w.items()));
        if (!onMap) {
            put(out, ViewSettings.Block.TARGET, target(w)); // on the map: the target's strip at the bottom
        }
        put(out, ViewSettings.Block.ATTACKERS, onMap ? attackersShort(w) : attackers(w));
        put(out, ViewSettings.Block.BOOSTERS, boosters(w.boosters(), feed.now()));
        // «не выпущен» says nothing over the map, unless the pet was put there on purpose
        if (w.pet().owned() && (!onMap || w.pet().active() || ViewSettings.shared().place(ViewSettings.Block.PET).map())) {
            out.put(ViewSettings.Block.PET, pet(w.pet(), w.nanos()));
        }
        if (w.group().exists()) {
            out.put(ViewSettings.Block.GROUP, group(w));
        }
        put(out, ViewSettings.Block.QUESTS, quests(w.quests(), doing(feed)));
        put(out, ViewSettings.Block.GATES, gates(w, feed));
        put(out, ViewSettings.Block.SKYLAB, skylab(feed.skylab()));
        put(out, ViewSettings.Block.LAB, lab(w.inventory().lab(), feed.now()));
        if (feed.stats() != null || !w.rewards().equals(WorldSnapshot.Rewards.NONE)) {
            out.put(ViewSettings.Block.STATS, onMap ? statsShort(feed) : stats(w, feed));
        }
        return out;
    }

    /**
     * The smallest form of a block over the map (the user, 2026-11-16: on a small window «ничего не открыто и не
     * поместилось» — AstraBot shows less, but shows it): a few lines, used when the short card does not fit; {@code null}
     * for a block with none (it folds to its heading).
     */
    Card mini(ViewSettings.Block b, WorldSnapshot w, WorldFeed feed) {
        WorldSnapshot.Hero h = w.hero();
        if (h.id() == 0) {
            return null;
        }
        List<String[]> rows = new ArrayList<>();
        switch (b) {
            case SHIP -> {
                long used = w.inventory().ores().isEmpty() ? h.cargoMax() - h.cargoFree() : w.inventory().cargoUsed();
                rows.add(new String[] {"Кредиты", orbit.app.log.Friendly.short_(h.credits())});
                rows.add(new String[] {"Уридий", orbit.app.log.Friendly.short_(h.uridium())});
                rows.add(new String[] {"Трюм", h.cargoMax() > 0 ? used + " / " + h.cargoMax() : "—"});
                return rows(h.name() + (h.shipType().isBlank() ? "" : "  " + shipName(h.shipType())), "", true,
                        MinimapPanel.HERO, rows);
            }
            case STATS -> {
                WorldSnapshot.Rewards r = feed.sessionRewards();
                orbit.bot.run.Stats s = feed.sessionStats();
                rows.add(new String[] {"Уридий", orbit.app.log.Friendly.short_(r.uridium())});
                rows.add(new String[] {"Кредиты", orbit.app.log.Friendly.short_(r.credits())});
                if (s != null) {
                    rows.add(s.cubesFought() > 0 ? new String[] {"Кубы", s.cubesKilled.get() + " из " + s.cubesFought()}
                            : new String[] {"Убито NPC", String.valueOf(s.kills.get())});
                }
                return rows("Итоги сессии", "", false, new Color(0xa78bfa), rows);
            }
            case BOOSTERS -> {
                WorldSnapshot.Boosters bs = w.boosters();
                if (!bs.known() || bs.active().isEmpty()) {
                    return null;
                }
                long now = feed.now();
                bs.active().values().stream()
                        .sorted(Comparator.comparingLong((WorldSnapshot.Booster x) -> x.secondsLeftAt(now) < 0
                                ? Long.MAX_VALUE : x.secondsLeftAt(now)))
                        .limit(3)
                        .forEach(x -> rows.add(new String[] {x.lootId().replaceFirst("^booster_", "")
                                .replaceFirst("^equipment_[a-z]+_[a-z]+_", "").toUpperCase(java.util.Locale.ROOT),
                                x.secondsLeftAt(now) >= 0 ? hm(x.secondsLeftAt(now)) : ""}));
                return rows("Бустеры", bs.active().size() > 3 ? String.valueOf(bs.active().size()) : "", false, BOOST, rows);
            }
            case ATTACKERS -> {
                for (int id : h.attackers()) {
                    Ship a = w.ships().get(id);
                    if (a != null && rows.size() < 3) {
                        rows.add(new String[] {MinimapPanel.label(a), a.health().known()
                                ? Math.round(100 * a.health().hpPercent()) + "%" : ""});
                    }
                }
                return rows.isEmpty() ? null
                        : rows("Атакуют меня", String.valueOf(h.attackers().size()), false, MinimapPanel.ENEMY, rows);
            }
            case LAB -> {
                WorldSnapshot.Lab lab = w.inventory().lab();
                for (int i = 0; i < LAB_ITEMS.size(); i++) {
                    WorldSnapshot.Enrichment e = lab.items().get(i);
                    if (e != null && e.left() > 0) {
                        rows.add(new String[] {LAB_ITEMS.get(i), orbit.app.log.Friendly.short_(e.left()) + " "
                                + LAB_UNITS.get(i)});
                    }
                }
                return rows.isEmpty() ? null : rows("Обогащение", "", false, LAB_BAR, rows);
            }
            case SKYLAB -> {
                orbit.auth.Skylab.Info sk = feed.skylab();
                if (sk == null) {
                    return null;
                }
                rows.add(new String[] {"Транспорт", sk.transporting() ? "в пути" : "свободен"});
                return rows("Скайлаб", "", false, SKYLAB_BAR, rows);
            }
            default -> {
                return null;
            }
        }
    }

    /**
     * «Мимесис» (docs/MIMESIS.md) in the gates' place: the keys, the run's and the event's score, the time to the next
     * freighter or to the entry's close, as the server tells them. While the Mimesis module runs or on an escort map.
     */
    private Card escort(WorldSnapshot w, orbit.bot.run.BotRunner r) {
        WorldSnapshot.Escort e = w.gates().escort();
        boolean on = r != null && r.moduleKind() == orbit.config.BotConfig.Module.MIMESIS
                || orbit.config.GateCatalog.escort(w.hero().mapId());
        if (!on || !e.known()) {
            return null;
        }
        List<String[]> rows = new ArrayList<>();
        if (orbit.config.GateCatalog.escort(w.hero().mapId())) {
            rows.add(new String[] {"Рейс", "идёт"});
        }
        rows.add(new String[] {"Очки рейса", money(e.runScore())});
        rows.add(new String[] {"Очки всего", money(e.totalScore())});
        if (e.timesKnown()) {
            long close = (long) e.left(e.gateClose(), w.nanos());
            long next = (long) e.left(e.nextSpawn(), w.nanos());
            if (close > 0) {
                rows.add(new String[] {"Вход открыт ещё", close / 60 + ":" + String.format("%02d", close % 60), "!"});
            } else {
                rows.add(new String[] {"Фрейтер через", next / 60 + " мин"});
            }
        }
        return rows("Мимесис", e.keys() >= 0 ? "ключей " + e.keys() : "", false, GATE_BAR, rows);
    }

    /** Label — value rows in one column, the values in columns: the short cards of the map. */
    private Card rows(String title, String right, boolean name, Color accent, List<String[]> rows) {
        return new Card() {
            @Override
            public int height(int width) {
                return pad + (name ? titleLines(title, right, width) * line : head) + rows.size() * line + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), accent);
                int ry;
                if (name) {
                    ry = y + pad + titleRow(g, x, y, width, title, right, TEXT) * line;
                } else {
                    heading(g, x, y, width, title, right);
                    ry = y + pad + head;
                }
                for (String[] r : rows) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString(clip(g, r[0], width - 2 * pad - valueWidth(r[1]) - 6), x + pad, ry + 11);
                    value(g, r[1], x + width - pad, ry + 11, r.length > 2 ? ENEMY_SOON : TEXT);
                    ry += line;
                }
            }
        };
    }

    /** The ship on the map: its name and the numbers, one column (the hull and shield are in the bottom strip). */
    private Card heroShort(WorldSnapshot.Hero h, WorldSnapshot.Inventory inv, Map<String, WorldSnapshot.Item> items) {
        String name = h.name() + (h.shipType().isBlank() ? "" : "  " + shipName(h.shipType()));
        long used = inv.ores().isEmpty() ? h.cargoMax() - h.cargoFree() : inv.cargoUsed();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Кредиты", money(h.credits())});
        rows.add(new String[] {"Уридий", money(h.uridium())});
        rows.add(new String[] {"Опыт", money(h.experience())});
        rows.add(new String[] {"Честь", money(h.honor())});
        rows.add(new String[] {"Трюм", h.cargoMax() > 0 ? String.format("%,d / %,d", used, h.cargoMax()) : "—"});
        rows.add(new String[] {"Скорость", h.speed() > 0 ? String.valueOf(h.speed()) : "—"});
        rows.add(new String[] {"Конфигурация", h.config() > 0 ? String.valueOf(h.config()) : "—"});
        rows.add(new String[] {"Формация", formation(items)});
        return rows(name, h.level() > 0 ? "ур. " + h.level() : "", true, MinimapPanel.HERO, rows);
    }

    /** Every attacker on the map, one line each: the name and its hull and shield in per cent. */
    private Card attackersShort(WorldSnapshot w) {
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
        return new Card() {
            @Override
            public int height(int width) {
                return pad + head + byName.size() * line + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.ENEMY);
                heading(g, x, y, width, "Атакуют меня", String.valueOf(w.hero().attackers().size()));
                int ry = y + pad + head;
                for (Map.Entry<String, List<Ship>> e : byName.entrySet()) {
                    List<Ship> ships = e.getValue();
                    Ship weakest = ships.stream().filter(a -> a.health().known())
                            .min(Comparator.comparingDouble(a -> a.health().hpPercent())).orElse(null);
                    String hp = weakest == null ? "" : Math.round(100 * weakest.health().hpPercent()) + "%  · щит "
                            + Math.round(weakest.health().shieldMax() > 0
                            ? 100.0 * weakest.health().shield() / weakest.health().shieldMax() : 0) + "%";
                    int hw = valueWidth(hp);
                    g.setFont(font(Font.BOLD, 11f));
                    g.setColor(MinimapPanel.shipColor(ships.getFirst(), w.hero(), w.group()));
                    g.drawString(clip(g, e.getKey() + (ships.size() > 1 ? "  ×" + ships.size() : ""), width - 2 * pad - hw - 6),
                            x + pad, ry + 11);
                    value(g, hp, x + width - pad, ry + 11, DIM);
                    ry += line;
                }
            }
        };
    }

    /** The session's results on the map: the gains, the cubes or NPCs, deaths and what was spent. */
    private Card statsShort(WorldFeed feed) {
        WorldSnapshot.Rewards r = feed.sessionRewards();
        orbit.bot.run.Stats s = feed.sessionStats();
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"Уридий", money(r.uridium())});
        rows.add(new String[] {"Кредиты", money(r.credits())});
        rows.add(new String[] {"Опыт", money(r.experience())});
        rows.add(new String[] {"Честь", money(r.honor())});
        if (s != null) {
            if (s.cubesFought() > 0) {
                rows.add(new String[] {"Кубы", s.cubesKilled.get() + " из " + s.cubesFought()});
            } else {
                rows.add(new String[] {"Убито NPC", String.valueOf(s.kills.get())});
            }
            rows.add(new String[] {"Смерти", String.valueOf(s.deaths.get())});
        }
        if (r.spentUridium() > 0) {
            rows.add(new String[] {"Потрачено уридия", money(r.spentUridium()), "!"});
        }
        if (r.spentCredits() > 0) {
            rows.add(new String[] {"Потрачено кредитов", money(r.spentCredits()), "!"});
        }
        return rows("Итоги сессии", "", false, new Color(0xa78bfa), rows);
    }

    /** The drone formation the item bar marks as selected, by its name; «—» when none is marked. */
    static String formation(Map<String, WorldSnapshot.Item> items) {
        return items.values().stream()
                .filter(i -> i.selected() && i.id().startsWith("drone_formation_"))
                .map(i -> orbit.config.ItemCatalog.label(i.id()))
                .findFirst().orElse("—");
    }

    static final Color QUEST_BAR = new Color(0x9071cf);
    static final Color GATE_BAR = new Color(0x3aa3b8);

    /** The gates the card lists: ABG, ζ, κ first (the user's first test), then any other the builder tells of. */
    static final List<String> GATE_ORDER = List.of("alpha", "beta", "gamma", "zeta", "kappa", "delta", "epsilon", "lambda",
            "kronos", "hades", "kuiper");

    /**
     * «Врата»: the builder as the gate module sees it (the user, 2026-11-14: «а как мы показываем в боте билд гг?») —
     * free energy and uridium, each gate's parts of all with a bar, its lives and wave; the gate the ship is in marked.
     * Shown while the gate module runs, or in a gate.
     */
    private Card gates(WorldSnapshot w, WorldFeed feed) {
        WorldSnapshot.Gates gs = w.gates();
        orbit.config.GateCatalog.Gate in = orbit.config.GateCatalog.byMap(w.hero().mapId());
        orbit.bot.run.BotRunner r = feed.runner();
        Card escort = escort(w, r);
        if (escort != null) {
            return escort;
        }
        boolean module = r != null && r.moduleKind() == orbit.config.BotConfig.Module.GALAXY_GATE;
        if (gs.infoNanos() == 0 || !module && in == null) {
            return null;
        }
        List<orbit.config.GateCatalog.Gate> list = new ArrayList<>();
        for (String k : GATE_ORDER) {
            orbit.config.GateCatalog.Gate g = orbit.config.GateCatalog.byKey(k);
            WorldSnapshot.Gate st = gs.gate(g.builderId());
            boolean first = k.equals("alpha") || k.equals("beta") || k.equals("gamma") || k.equals("zeta") || k.equals("kappa");
            if (st != null && (first || st.parts() > 0 || st.lives() > 0 || g == in)) {
                list.add(g);
            }
        }
        int rowH = line + 12;
        return new Card() {
            @Override
            public int height(int width) {
                return pad + head + list.size() * rowH + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), GATE_BAR);
                heading(g, x, y, width, "Врата", "энергия " + money((long) Math.max(0, gs.samples())) + " · уридий "
                        + money((long) Math.max(0, gs.money())));
                int ry = y + pad + head;
                for (orbit.config.GateCatalog.Gate gate : list) {
                    WorldSnapshot.Gate st = gs.gate(gate.builderId());
                    int parts = Math.max(0, st.parts());
                    boolean placed = st.lives() > 0;
                    // the parts are on the bar: the line says what the bar does not
                    String text = (gate == in ? "▶ " : "") + gate.name()
                            + (placed ? " · жизней " + st.lives() : parts >= gate.parts() ? " · собраны" : "")
                            + (st.wave() > 0 ? " · волна " + st.wave() + "/" + gate.waves() : "");
                    g.setFont(font(gate == in ? Font.BOLD : Font.PLAIN, 11f));
                    g.setColor(placed || gate == in ? TEXT : DIM);
                    g.drawString(clip(g, text, width - 2 * pad), x + pad, ry + 10);
                    bar(g, x + pad, ry + 13, width - 2 * pad, 9, parts, gate.parts(), placed ? HP_BAR : GATE_BAR, false);
                    ry += rowH;
                }
            }
        };
    }
    static final Color SKYLAB_BAR = new Color(0x3fa864);
    static final Color LAB_BAR = new Color(0xd29a3a);

    /** The lab's items in the window's words (LabItemModule: laser 0, rockets 1, drive 2, shield 3). */
    static final List<String> LAB_ITEMS = List.of("Лазеры", "Ракеты", "Двигатели", "Щиты");
    /** The game's units of what is left (its lab window, 2026-11-15): shots, rockets, minutes, minutes. */
    static final List<String> LAB_UNITS = List.of("выстр.", "шт.", "мин.", "мин.");

    /** OreTypeModule numbers of the enriching ores. */
    static String oreName(int ore) {
        return switch (ore) {
            case 0 -> "Prometium";
            case 1 -> "Endurium";
            case 2 -> "Terbium";
            case 3 -> "Xenomit";
            case 4 -> "Prometid";
            case 5 -> "Duranium";
            case 6 -> "Promerium";
            case 7 -> "Seprom";
            case 8 -> "Palladium";
            default -> "руда №" + ore;
        };
    }

    /**
     * «Обогащение» (the user, 2026-11-15: «обогащены ли сейчас лазеры, движки, ракеты и щиты, чем и сколько осталось»):
     * the lab's answer — each item's ore and what is left of it; when it was told and how often it is asked.
     */
    Card lab(WorldSnapshot.Lab lab, long nowNanos) {
        if (lab == null || lab.nanos() == 0) {
            return null;
        }
        long millis = System.currentTimeMillis() - Math.max(0, nowNanos - lab.nanos()) / 1_000_000;
        String age = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(millis),
                java.time.ZoneId.systemDefault()).withNano(0).toString();
        return new Card() {
            @Override
            public int height(int width) {
                return pad + head + LAB_ITEMS.size() * line + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), LAB_BAR);
                heading(g, x, y, width, "Обогащение", age + " · раз в 5 мин");
                g.setFont(font(Font.PLAIN, 11f));
                int ry = y + pad + head;
                for (int i = 0; i < LAB_ITEMS.size(); i++) {
                    WorldSnapshot.Enrichment e = lab.items().get(i);
                    boolean has = e != null && e.left() > 0;
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString(LAB_ITEMS.get(i), x + pad, ry + 10);
                    String v = has ? oreName(e.ore()) + "  " + money(e.left()) + " " + LAB_UNITS.get(i) : "нет";
                    value(g, v, x + width - pad, ry + 10, has ? TEXT : DIM);
                    ry += line;
                }
            }
        };
    }

    /** The page names of the Skylab's modules, in the window's words. */
    static final Map<String, String> SKYLAB_MODULES = Map.ofEntries(Map.entry("baseModule", "база"),
            Map.entry("solarModule", "солнечный"), Map.entry("storageModule", "склад"),
            Map.entry("transportModule", "транспорт"), Map.entry("xenoModule", "ксено"),
            Map.entry("prometiumCollector", "сбор prometium"), Map.entry("enduriumCollector", "сбор endurium"),
            Map.entry("terbiumCollector", "сбор terbium"), Map.entry("prometidRefinery", "prometid"),
            Map.entry("duraniumRefinery", "duranium"), Map.entry("promeriumRefinery", "promerium"),
            Map.entry("sepromRefinery", "seprom"));

    /**
     * «Скайлаб» (the user, 2026-11-15: «чтобы в боте всё отображалось, на каждом аке»): the website's Skylab, read by
     * the bot every 20 min — the transport, the modules not at the top level (or all at 20) with the stopped ones, and
     * each ore's stock of the storage with a bar.
     */
    Card skylab(orbit.auth.Skylab.Info s) {
        if (s == null) {
            return null;
        }
        List<String> modules = new ArrayList<>();
        int top = 0;
        for (orbit.auth.Skylab.Module m : s.modules()) {
            String name = SKYLAB_MODULES.getOrDefault(m.name(), m.name());
            if (m.level() >= 20 && m.active()) {
                top++;
            } else {
                modules.add(name + " " + m.level() + (m.active() ? "" : " (выкл.)"));
            }
        }
        String modLine = (top > 0 ? "на 20 ур.: " + top + (modules.isEmpty() ? "" : " · ") : "") + String.join(" · ", modules);
        int rowH = line + 12;
        int robots = s.robots().values().stream().mapToInt(Integer::intValue).sum();
        String age = java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(s.readMillis()),
                java.time.ZoneId.systemDefault()).withNano(0).withSecond(0).toString();
        return new Card() {
            @Override
            public int height(int width) {
                return pad + head + line + wrap(measure(font(Font.PLAIN, 11f)), modLine, width - 2 * pad).size() * line
                        + s.ores().size() * rowH + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), SKYLAB_BAR);
                heading(g, x, y, width, "Скайлаб", age + " · раз в 20 мин");
                g.setFont(font(Font.PLAIN, 11f));
                int ry = y + pad + head;
                g.setColor(s.transporting() ? TEXT : DIM);
                g.drawString(clip(g, "Транспорт: " + (s.transporting() ? "в пути" : "свободен")
                        + (robots > 0 ? " · роботов ждут: " + robots : ""), width - 2 * pad), x + pad, ry + 10);
                ry += line;
                g.setColor(DIM);
                for (String part : wrap(g.getFontMetrics(), modLine, width - 2 * pad)) {
                    g.drawString(part, x + pad, ry + 10);
                    ry += line;
                }
                for (orbit.auth.Skylab.Ore o : s.ores()) {
                    g.setColor(TEXT);
                    String name = Character.toUpperCase(o.name().charAt(0)) + o.name().substring(1);
                    g.drawString(clip(g, name + "  " + money(o.amount()) + (o.capacity() > 0 ? " / " + money(o.capacity()) : ""),
                            width - 2 * pad), x + pad, ry + 10);
                    bar(g, x + pad, ry + 13, width - 2 * pad, 7, Math.max(0, o.amount()), Math.max(1, o.capacity()),
                            SKYLAB_BAR, false);
                    ry += rowH;
                }
            }
        };
    }

    static final int MAX_QUESTS = 6;
    /** Over the map fewer: the map stays to be seen. */
    static final int MAX_QUESTS_ON_MAP = 3;

    /** The quest module's work now ({quest, condition}), {@code null} none or another module. */
    private static int[] doing(WorldFeed feed) {
        orbit.bot.run.BotRunner r = feed.runner();
        return r != null && r.module() instanceof orbit.bot.mod.QuestModule m ? m.doing() : null;
    }

    /**
     * The taken quests: type and number, then each condition with its progress. The one the bot works on first, marked
     * «▶ сейчас» with a bar at the left and its condition marked, the others dimmed (the user, 2026-11-17: which quest is
     * being done was not to be seen); then the HUD's quest.
     */
    Card quests(orbit.world.Quests q, int[] doing) {
        if (q.active().isEmpty()) {
            return null;
        }
        int now = doing != null && q.active().containsKey(doing[0]) ? doing[0] : 0;
        List<orbit.world.Quests.Quest> list = q.active().values().stream()
                .sorted(Comparator.comparingInt((orbit.world.Quests.Quest x) -> x.id() == now ? 0 : 1)
                        .thenComparingInt(x -> x.id() == q.privileged() ? 0 : 1)
                        .thenComparingInt(orbit.world.Quests.Quest::id))
                .limit(onMap ? MAX_QUESTS_ON_MAP : MAX_QUESTS).toList();
        int more = q.active().size() - list.size();
        int condH = line + 12;
        return new Card() {
            @Override
            public int height(int width) {
                int h = pad + head;
                for (orbit.world.Quests.Quest x : list) {
                    h += line + x.leaves().size() * condH;
                }
                return h + (more > 0 ? line : 0) + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), QUEST_BAR);
                heading(g, x, y, width, "Квесты", String.valueOf(q.active().size()));
                int ry = y + pad + head;
                for (orbit.world.Quests.Quest quest : list) {
                    boolean current = quest.id() == now;
                    boolean dim = now != 0 && !current;
                    int top = ry;
                    g.setFont(font(Font.BOLD, 12f));
                    g.setColor(dim ? DIM : TEXT);
                    String title = orbit.world.QuestTitles.of(quest.id());
                    String head = (current ? "▶ " : "") + orbit.world.QuestTexts.type(quest.types()) + " " + quest.id()
                            + (title != null ? " «" + title + "»" : "") + (quest.id() == q.privileged() ? "  ★" : "");
                    String tag = current ? "сейчас" : "";
                    int tagW = tag.isEmpty() ? 0 : g.getFontMetrics().stringWidth(tag) + 8;
                    g.drawString(clip(g, head, width - 2 * pad - tagW), x + pad, ry + 11);
                    if (current) {
                        g.setColor(QUEST_BAR);
                        g.drawString(tag, x + width - pad - tagW + 8, ry + 11);
                    }
                    ry += line;
                    for (orbit.world.Quests.Condition c : quest.leaves()) {
                        boolean working = current && doing[1] == c.id();
                        g.setFont(font(working ? Font.BOLD : Font.PLAIN, 11f));
                        g.setColor(c.completed() ? HP_BAR : dim ? DIM : TEXT);
                        String text = (working ? "▶ " : c.completed() ? "✓ " : "") + orbit.world.QuestTexts.condition(c);
                        g.drawString(clip(g, text, width - 2 * pad), x + pad, ry + 10);
                        if (c.target() > 0 && !orbit.world.QuestTexts.isTime(c) && c.type() != orbit.world.Quests.AVOID_DEATH) {
                            bar(g, x + pad, ry + 13, width - 2 * pad, 9, c.current(), c.target(),
                                    dim ? BAR_TRACK.brighter() : QUEST_BAR, false);
                        }
                        ry += condH;
                    }
                    if (current) {
                        g.setColor(QUEST_BAR);
                        g.fillRect(x + 3, top + 1, 3, ry - top - 4); // the bar at the left of the quest at work
                    }
                }
                if (more > 0) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString("… и ещё " + more, x + pad, ry + 11);
                }
            }
        };
    }

    private static void put(Map<ViewSettings.Block, Card> out, ViewSettings.Block b, Card c) {
        if (c != null) {
            out.put(b, c);
        }
    }

    /** The cards of the blocks placed at {@code where}. */
    List<Card> cards(WorldSnapshot w, WorldFeed feed, String config, ViewSettings v, ViewSettings.Place where) {
        List<Card> out = new ArrayList<>();
        blocks(w, feed, config).forEach((b, c) -> {
            if (where == ViewSettings.Place.SIDE ? v.place(b).side() : where == ViewSettings.Place.MAP ? v.place(b).map()
                    : v.place(b) == where) {
                out.add(c);
            }
        });
        return out;
    }

    /**
     * The map's blocks as two columns over it: the ship side on the left from {@code leftTop}, the fight
     * and the results on the right from {@code rightTop}; what does not fit above {@code bottom} is left out
     * (an ellipsis says so).
     */
    void mapBlocks(Graphics2D g, int left, int right, int leftTop, int rightTop, int bottom, int width,
                   WorldSnapshot w, WorldFeed feed, String config, ViewSettings v,
                   Map<java.awt.Rectangle, ViewSettings.Block> placed, boolean bigMap) {
        // a narrow map: one column on the left
        boolean oneColumn = right - left < 2 * width + 20;
        int ly = leftTop;
        int ry = rightTop;
        boolean leftCut = false;
        boolean rightCut = false;
        for (Map.Entry<ViewSettings.Block, Card> e : blocks(w, feed, config).entrySet()) {
            ViewSettings.Place where = v.place(e.getKey());
            // «everywhere» on a small map: beside it only, so the map stays to be seen (unless the side is hidden)
            boolean sideShown = v.shown(ViewSettings.Section.SIDE);
            if (!v.onMap(e.getKey()) || (where == ViewSettings.Place.BOTH && !bigMap && sideShown)) {
                continue;
            }
            if (e.getKey() == ViewSettings.Block.TARGET) {
                continue; // the map's bottom shows the target (targetStrip)
            }
            Card c = e.getValue();
            int h = c.height(width);
            boolean isRight = !oneColumn && e.getKey().right();
            int x = isRight ? right - width : left;
            int y = isRight ? ry : ly;
            if (y + h > bottom && onMap) {
                // no room for the card: its smallest form, if that fits (AstraBot shows less, but shows it)
                Card m = mini(e.getKey(), w, feed);
                if (m != null && y + m.height(width) <= bottom) {
                    c = m;
                    h = m.height(width);
                }
            }
            if (y + h > bottom) {
                // no room for the whole card: its heading alone, folded (not a card cut off)
                int stub = pad + line + 2;
                if (y + stub <= bottom) {
                    frame(g, x, y, width, stub, DIM);
                    g.setFont(headingFont());
                    g.setColor(HEADING);
                    g.drawString(e.getKey().label.toUpperCase(java.util.Locale.ROOT) + "  ▸", x + pad, y + pad + 10);
                    placed.put(new java.awt.Rectangle(x, y, width, stub), e.getKey());
                    if (isRight) {
                        ry += stub + 6;
                    } else {
                        ly += stub + 6;
                    }
                } else if (isRight) {
                    rightCut = true;
                } else {
                    leftCut = true;
                }
                continue;
            }
            if (!onMap) {
                translucent(g, x, y, width, h);
            }
            c.paint(g, x, y, width);
            placed.put(new java.awt.Rectangle(x, y, width, h), e.getKey());
            if (isRight) {
                ry += h + 6;
            } else {
                ly += h + 6;
            }
        }
        g.setFont(font(Font.BOLD, 12f));
        g.setColor(DIM);
        if (leftCut) {
            g.drawString("…", left + 4, Math.min(ly + 10, bottom));
        }
        if (rightCut) {
            g.drawString("…", right - width + 4, Math.min(ry + 10, bottom));
        }
    }

    private Card status(WorldSnapshot w, WorldFeed feed, String config) {
        AccountStatus st = AccountStatus.of(feed);
        List<String> lines = new ArrayList<>();
        WorldSnapshot.Hero h = w.hero();
        if (config != null) {
            lines.add("Конфиг: " + config);
        }
        if (h.id() != 0 && !feed.loggedOut()) {
            lines.add(MapCatalog.bundled().info(h.mapId()).name() + "  ·  " + h.x() / 100 + "/" + h.y() / 100
                    + "  ·  " + heroStatus(h));
            // the configuration and the formation chosen now (the user, 2026-11-15: «в карточке выбранную формацию показывать»)
            lines.add("Конфигурация " + (h.config() > 0 ? h.config() : "—") + "  ·  " + formation(w.items()));
            lines.add("Лазер: " + item(w.selected("lasers")));
            lines.add("Ракеты: " + item(w.selected("rockets")));
            // the launcher only on a ship that has one; with its load (the button's counter, 0…5 on the HST)
            WorldSnapshot.Item launcher = w.items().values().stream()
                    .filter(i -> i.id().startsWith("equipment_weapon_rocketlauncher_") && i.available()).findFirst().orElse(null);
            if (launcher != null) {
                int full = launcher.maxCounter() > 0 ? (int) launcher.maxCounter() : 5;
                lines.add("Ракетомёт: " + item(w.selected("rocket_launchers")) + "  ·  " + (int) launcher.counter() + "/" + full);
            }
        } else {
            lines.add(feed.state());
        }
        String time = feed.sessionStartNanos() == 0 ? "" : clock(Duration.ofNanos(feed.sessionNanos()));
        Font titleFont = font(Font.BOLD, 13f);
        Font textFont = font(Font.PLAIN, 12f);
        Font smallFont = font(Font.PLAIN, 11f);
        return new Card() {
            int timeWidth() {
                return time.isEmpty() ? 0 : measure(smallFont).stringWidth(time) + 10;
            }

            @Override
            public int height(int width) {
                int inner = width - 2 * pad;
                int n = wrap(measure(titleFont), feed.action(), inner - timeWidth()).size();
                int m = 0;
                for (String text : lines) {
                    m += wrap(measure(textFont), text, inner).size();
                }
                return pad + n * line + 4 + m * line + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), st.color);
                int inner = width - 2 * pad;
                g.setFont(smallFont);
                g.setColor(DIM);
                g.drawString(time, x + width - pad - g.getFontMetrics().stringWidth(time), y + pad + 12);
                g.setFont(titleFont);
                g.setColor(TEXT);
                int ty = y + pad + 12;
                for (String part : wrap(g.getFontMetrics(), feed.action(), inner - timeWidth())) {
                    g.drawString(part, x + pad, ty);
                    ty += line;
                }
                ty += 4;
                g.setFont(textFont);
                for (String text : lines) {
                    g.setColor(text.startsWith("Конфиг") ? TEXT : DIM);
                    for (String part : wrap(g.getFontMetrics(), text, inner)) {
                        g.drawString(part, x + pad, ty);
                        ty += line;
                    }
                }
            }
        };
    }

    /**
     * The pilot and the ship, as the game shows them: name, ship, level; hull, shield (and nano hull);
     * credits, uridium, experience, honour, speed, cargo, configuration; the booty keys.
     */
    private Card hero(WorldSnapshot.Hero h, WorldSnapshot.Inventory inv, Map<String, WorldSnapshot.Item> items) {
        String name = h.name() + (h.shipType().isBlank() ? "" : "  " + shipName(h.shipType()));
        String right = (h.level() > 0 ? "ур. " + h.level() : "") + (h.premium() ? "  ·  премиум" : "");
        List<String[]> stats = new ArrayList<>();
        stats.add(new String[] {"Кредиты", money(h.credits())});
        stats.add(new String[] {"Уридий", money(h.uridium())});
        stats.add(new String[] {"Опыт", money(h.experience())});
        stats.add(new String[] {"Честь", money(h.honor())});
        stats.add(new String[] {"Скорость", h.speed() > 0 ? String.valueOf(h.speed()) : "—"});
        // the ore in the hold, as it changes (pickups, sales); the login's cargoSpace is only the start
        long used = inv.ores().isEmpty() ? h.cargoMax() - h.cargoFree() : inv.cargoUsed();
        stats.add(new String[] {"Трюм", h.cargoMax() > 0 ? String.format("%,d / %,d", used, h.cargoMax()) : "—"});
        stats.add(new String[] {"Конфигурация", h.config() > 0 ? String.valueOf(h.config()) : "—"});
        // the formation the item bar marks as chosen (the user, 2026-11-15: «я не понимаю, какая формация сейчас»)
        stats.add(new String[] {"Формация", formation(items)});
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
                int bars = (nano ? 3 : 2) * (bar + 4);
                int keyRows = wrap(measure(keyFont), keys, width - 2 * pad).size();
                return pad + titleLines(name, right, width) * line + bars + 6 + statRows(width) * line + 4
                        + keyRows * (line - 2) + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.HERO);
                int inner = width - 2 * pad;
                int rows = titleRow(g, x, y, width, name, right, TEXT);
                int by = y + pad + rows * line;
                Health hp = h.health();
                bar(g, x + pad, by, inner, bar, hp.hp(), hp.hpMax(), HP_BAR, false);
                bar(g, x + pad, by + bar + 4, inner, bar, hp.shield(), hp.shieldMax(), SHIELD_BAR, false);
                if (nano) {
                    bar(g, x + pad, by + 2 * (bar + 4), inner, bar, hp.nanoHull(), hp.nanoHullMax(), NANO_BAR, false);
                }
                int sy = by + (nano ? 3 : 2) * (bar + 4) + 6 + 12;
                int n = cols(width);
                int cw = (inner - (n - 1) * 14) / n;
                g.setFont(font(Font.PLAIN, 12f));
                FontMetrics fm = g.getFontMetrics();
                for (int i = 0; i < stats.size(); i++) {
                    int cx = x + pad + (i % n) * (cw + 14);
                    int cy = sy + (i / n) * line;
                    String value = stats.get(i)[1];
                    g.setColor(DIM);
                    g.drawString(clip(g, stats.get(i)[0], cw - valueWidth(value) - 6), cx, cy);
                    value(g, value, cx + cw, cy, TEXT);
                    g.setFont(font(Font.PLAIN, 12f));
                }
                int ky = sy + statRows(width) * line + 4;
                g.setFont(keyFont);
                g.setColor(DIM);
                for (String part : wrap(g.getFontMetrics(), keys, inner)) {
                    g.drawString(part, x + pad, ky);
                    ky += line - 2;
                }
            }
        };
    }

    static final Color NANO_BAR = new Color(0xd29a3a);
    static final Color BOOST = new Color(0xd9b04a);

    /** Short names of the boosted attributes ({@code BoosterUpdateModule.attributeType}). */
    static String boostName(String attribute) {
        return switch (attribute) {
            case "DAMAGE" -> "Урон";
            case "SHIELD" -> "Щит";
            case "HITPOINTS" -> "HP";
            case "HONOUR_POINTS" -> "Честь";
            case "EXPERIENCE_POINTS" -> "Опыт";
            case "REPAIR" -> "Ремонт";
            case "SHIELD_REGENERATION" -> "Реген щита";
            case "ABILITY_COOLDOWN_TIME" -> "Перезарядка";
            case "COLLECT_RESOURCES", "COLLECT_RESOURCES_NEWBIE" -> "Ресурсы";
            default -> attribute.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
        };
    }

    /** «Урон +13%» for every attribute with a bonus, the biggest first. */
    static List<String> boostPills(WorldSnapshot.Boosters b) {
        Map<String, Double> byName = new LinkedHashMap<>();
        b.totals().forEach((k, v) -> {
            if (v != null && v > 0) {
                byName.merge(boostName(k), v, Double::sum);
            }
        });
        return byName.entrySet().stream().sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> e.getKey() + " +" + String.format("%.0f", e.getValue()) + "%").toList();
    }

    /** The boosters by time left: own ones with hours and minutes, equipment ones with their count. */
    private Card boosters(WorldSnapshot.Boosters b, long now) {
        if (!b.known() || b.active().isEmpty()) {
            return null;
        }
        List<String[]> rows = new ArrayList<>();
        b.active().values().stream()
                .sorted(Comparator.comparingLong((WorldSnapshot.Booster x) -> x.secondsLeftAt(now) < 0 ? Long.MAX_VALUE : x.secondsLeftAt(now)))
                .forEach(x -> {
                    String name = x.lootId().replaceFirst("^booster_", "").replaceFirst("^equipment_[a-z]+_[a-z]+_", "")
                            .toUpperCase(java.util.Locale.ROOT);
                    long left = x.secondsLeftAt(now);
                    String value = left >= 0 ? hm(left) : x.amount() > 1 ? "×" + x.amount() : "";
                    rows.add(new String[] {name, value});
                });
        List<String> pills = boostPills(b);
        return new Card() {
            @Override
            public int height(int width) {
                return pad + head + rows.size() * (line - 1) + (pills.isEmpty() ? 0 : line + 2) + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), BOOST);
                heading(g, x, y, width, "Бустеры", "");
                int ty = y + pad + head + 11;
                g.setFont(font(Font.PLAIN, 12f));
                FontMetrics fm = g.getFontMetrics();
                for (String[] r : rows) {
                    g.setColor(TEXT);
                    g.drawString(clip(g, r[0], width - 2 * pad - valueWidth(r[1]) - 8), x + pad, ty);
                    value(g, r[1], x + width - pad, ty, r[1].startsWith("0:") ? ENEMY_SOON : DIM);
                    g.setFont(font(Font.PLAIN, 12f));
                    ty += line - 1;
                }
                if (!pills.isEmpty()) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(BOOST);
                    g.drawString(clip(g, String.join("  ·  ", pills), width - 2 * pad), x + pad, ty + 3);
                }
            }
        };
    }

    private static final Color ENEMY_SOON = new Color(0xdc7a3c);

    /** 3725 s → {@code 1:02}, under an hour {@code 0:47}. */
    static String hm(long seconds) {
        return String.format("%d:%02d", seconds / 3600, seconds / 60 % 60);
    }

    /**
     * The bonuses as pills in a corner of the map, right-aligned from {@code right}, wrapping onto more rows
     * when the map is narrow.
     */
    /** @return the bottom of the last row of pills ({@code top} when there are none) */
    int boostPills(Graphics2D g, int right, int top, int maxWidth, WorldSnapshot.Boosters b, boolean small) {
        List<String> pills = boostPills(b);
        if (pills.isEmpty()) {
            return top;
        }
        g.setFont(font(Font.BOLD, small ? 10f : 11f));
        FontMetrics fm = g.getFontMetrics();
        int h = small ? 18 : 22;
        int gap = 5;
        int x = right;
        int y = top;
        for (String p : pills) {
            int w = fm.stringWidth(p) + (small ? 12 : 16);
            if (x - w < right - maxWidth && x != right) {
                x = right;
                y += h + 4;
            }
            x -= w;
            g.setColor(new Color(10, 12, 18, 210));
            g.fill(new RoundRectangle2D.Double(x, y, w, h, h, h));
            g.setColor(new Color(BOOST.getRed(), BOOST.getGreen(), BOOST.getBlue(), 140));
            g.draw(new RoundRectangle2D.Double(x + 0.5, y + 0.5, w - 1, h - 1, h, h));
            g.setColor(BOOST);
            g.drawString(p, x + (w - fm.stringWidth(p)) / 2f, y + (h + fm.getAscent() - fm.getDescent()) / 2f);
            x -= gap;
        }
        return y + h;
    }

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
        return right.isEmpty() || need <= width - 2 * pad ? 1 : 2;
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

    /** The locator's point counts as current this long (as for the bot, {@code CubikonModule.LOCATOR_FRESH_MS}). */
    static final long LOCATOR_SHOWN_MS = 10_000;

    private Card pet(WorldSnapshot.Pet pet, long now) {
        boolean out = pet.active() && pet.id() != 0;
        String right = gearName(pet.gear()) + (pet.level() > 0 ? "  ·  ур. " + pet.level() : "");
        // what the enemy locator found: where and how long ago, or that it finds nothing
        // by the point's age: the server names the mode 12–32 s after the points start
        String locator = !out ? null
                : pet.locatorWithin(now, LOCATOR_SHOWN_MS)
                ? "Пеленгатор нашёл цель: " + pet.locatorX() / 100 + "/" + pet.locatorY() / 100 + "  ·  "
                        + Math.max(0, (now - pet.locatorNanos()) / 1_000_000_000L) + " с назад"
                : pet.gear() != orbit.protocol.gen.PetGearTypeModule.PETGEARTYPE_ENEMY_LOCATOR ? null
                // no point at all: the game said «nothing» (it sends -1/-1) or none came yet on this map; an old one:
                // the game sends points now and then — while the ship fights the target there are none (live
                // 2026-11-12: «not found» while the bot killed Saboteurs)
                : pet.locatorNanos() == 0 ? "Пеленгатор: цель не найдена"
                : "Пеленгатор ждёт новую точку  ·  последняя " + Math.max(0, (now - pet.locatorNanos()) / 1_000_000_000L)
                        + " с назад";
        return new Card() {
            @Override
            public int height(int width) {
                return out ? pad + titleLines(pet.name(), right, width) * line + 2 * bar + 4 + (pet.fuel() >= 0 ? line : 0)
                        + (locator != null ? line : 0) + pad
                        : pad + line + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.PET);
                if (!out) {
                    String why = !pet.alive() ? "Питомец уничтожен" : !pet.hasFuel() ? "Питомец: нет топлива" : "Питомец не выпущен";
                    g.setFont(font(Font.PLAIN, 12f));
                    g.setColor(DIM);
                    g.drawString(clip(g, why, width - 2 * pad), x + pad, y + pad + 12);
                    return;
                }
                int rows = titleRow(g, x, y, width, pet.name(), right, TEXT);
                if (pet.health().known()) {
                    bars(g, x + pad, y + pad + rows * line, width - 2 * pad, pet.health());
                }
                if (pet.fuel() >= 0) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString(clip(g, String.format("Топливо %,d / %,d", pet.fuel(), pet.fuelMax())
                                    + (pet.heatMax() > 0 ? String.format("  ·  нагрев %.0f / %.0f", pet.heat(), pet.heatMax()) : ""),
                            width - 2 * pad), x + pad, y + pad + rows * line + 2 * bar + 4 + 13);
                }
                if (locator != null) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(locator.contains("нашёл") ? MinimapPanel.LOCATOR : DIM);
                    g.drawString(clip(g, locator, width - 2 * pad), x + pad,
                            y + pad + rows * line + 2 * bar + 4 + (pet.fuel() >= 0 ? line : 0) + 13);
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
                int inner = width - 2 * pad;
                int rows = wrap(measure(nameFont), name, inner).size() + wrap(measure(kindFont), kind, inner).size();
                return pad + rows * line + (t.health().known() ? 2 * bar + 4 + 2 : 0) + pad - 2;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), accent);
                int inner = width - 2 * pad;
                int ty = y + pad + 12;
                g.setFont(nameFont);
                g.setColor(accent);
                for (String part : wrap(g.getFontMetrics(), name, inner)) {
                    g.drawString(part, x + pad, ty);
                    ty += line;
                }
                g.setFont(kindFont);
                g.setColor(DIM);
                for (String part : wrap(g.getFontMetrics(), kind, inner)) {
                    g.drawString(part, x + pad, ty);
                    ty += line;
                }
                if (t.health().known()) {
                    bars(g, x + pad, ty - 12 + 2, inner, t.health());
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
        int rowH = line + 12;
        return new Card() {
            @Override
            public int height(int width) {
                return pad + head + rows * rowH + (more ? line : 0) + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.ENEMY);
                heading(g, x, y, width, "Атакуют меня", String.valueOf(w.hero().attackers().size()));
                g.setFont(font(Font.PLAIN, 11f));
                int ry = y + pad + head;
                int row = 0;
                for (Map.Entry<String, List<Ship>> e : byName.entrySet()) {
                    if (row++ == rows) {
                        break;
                    }
                    List<Ship> ships = e.getValue();
                    g.setFont(font(Font.BOLD, 12f));
                    g.setColor(MinimapPanel.shipColor(ships.getFirst(), w.hero(), w.group()));
                    g.drawString(clip(g, e.getKey() + (ships.size() > 1 ? "  ×" + ships.size() : ""), width - 2 * pad),
                            x + pad, ry + 11);
                    Ship weakest = ships.stream().filter(a -> a.health().known())
                            .min(Comparator.comparingDouble(a -> a.health().hpPercent())).orElse(null);
                    int half = (width - 2 * pad - 6) / 2;
                    if (weakest != null) {
                        bar(g, x + pad, ry + 15, half, 9, weakest.health().hp(), weakest.health().hpMax(), HP_BAR, true);
                        bar(g, x + pad + half + 6, ry + 15, half, 9, weakest.health().shield(), weakest.health().shieldMax(),
                                SHIELD_BAR, true);
                    }
                    ry += rowH;
                }
                if (more) {
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString("… и ещё " + (byName.size() - rows), x + pad, ry + 11);
                }
            }
        };
    }

    /**
     * A group member's hull and shield: from his ship when it is in sight (the server then sends its hits, not the group's
     * updates — live 2026-11-12 the bars stood still for a big group), else from the group's packets.
     */
    static Health memberHealth(WorldSnapshot w, WorldSnapshot.Member m) {
        Ship s = w.ships().get(m.id());
        return s != null && s.health().known() ? s.health() : m.health();
    }

    private Card group(WorldSnapshot w) {
        WorldSnapshot.Group group = w.group();
        WorldSnapshot.Hero h = w.hero();
        List<WorldSnapshot.Member> others = group.members().stream().filter(m -> m.id() != h.id()).toList();
        int rowH = line + 2 * 11 + 12;
        // what each one has selected, as the game's group window shows it
        int targetH = 14 + 11 + 6;
        return new Card() {
            @Override
            public int height(int width) {
                int hgt = pad + head;
                for (WorldSnapshot.Member m : others) {
                    hgt += rowH + (m.targetName().isEmpty() || m.loggedOut() ? 0 : targetH);
                }
                return hgt + pad - 6;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), MinimapPanel.ALLY);
                heading(g, x, y, width, "Группа", group.members().size() + (group.leaderId() == h.id() ? " · вы лидер" : ""));
                g.setFont(font(Font.PLAIN, 11f));
                int ry = y + pad + head;
                for (WorldSnapshot.Member m : others) {
                    String where = m.loggedOut() ? "не в игре"
                            : m.mapId() != h.mapId() && m.mapId() != 0 ? MapCatalog.bundled().info(m.mapId()).name()
                            : String.format("%,.0f", Math.hypot(m.x() - h.x(), m.y() - h.y()));
                    String right = (m.level() > 0 ? "ур. " + m.level() + "  ·  " : "") + where;
                    g.setFont(font(Font.BOLD, 12f));
                    g.setColor(m.loggedOut() ? DIM : TEXT);
                    FontMetrics fm = g.getFontMetrics(font(Font.PLAIN, 11f));
                    g.drawString(clip(g, m.name() + (m.id() == group.leaderId() ? " ★" : ""),
                            width - 2 * pad - fm.stringWidth(right) - 8), x + pad, ry + 11);
                    g.setFont(font(Font.PLAIN, 11f));
                    g.setColor(DIM);
                    g.drawString(right, x + width - pad - fm.stringWidth(right), ry + 11);
                    Health mh = memberHealth(w, m);
                    if (mh.known()) {
                        valueBar(g, x + pad, ry + 15, width - 2 * pad, 11, mh.hp(), mh.hpMax(), HP_BAR);
                        valueBar(g, x + pad, ry + 28, width - 2 * pad, 11, mh.shield(), mh.shieldMax(), SHIELD_BAR);
                    }
                    ry += rowH;
                    if (!m.targetName().isEmpty() && !m.loggedOut()) {
                        // «→ target» on its line, its hull and shield under it, side by side
                        int indent = pad + 10;
                        g.setFont(font(Font.PLAIN, 11f));
                        g.setColor(MinimapPanel.ENEMY);
                        g.drawString(clip(g, "→ " + m.targetName(), width - indent - pad), x + indent, ry + 10);
                        Health th = m.targetHealth();
                        if (th.known()) {
                            int bx = x + indent;
                            int bw = (x + width - pad - bx - 4) / 2;
                            valueBar(g, bx, ry + 14, bw, 11, th.hp(), th.hpMax(), HP_BAR);
                            valueBar(g, bx + bw + 4, ry + 14, bw, 11, th.shield(), th.shieldMax(), SHIELD_BAR);
                        }
                        ry += targetH;
                    }
                }
            }
        };
    }

    private Card stats(WorldSnapshot w, WorldFeed feed) {
        List<String> lines = orbit.app.SessionSummary.lines(feed.sessionRewards(), feed.sessionStats(), feed.sessionHours());
        // today over all runs (the user, 2026-11-13: the session starts from nothing at each start)
        List<String> today = orbit.app.DailyTotals.of(feed.account()).lines();
        Font textFont = font(Font.PLAIN, 12f);
        return new Card() {
            @Override
            public int height(int width) {
                int rows = 0;
                for (String text : lines) {
                    rows += wrap(measure(textFont), text, width - 2 * pad).size();
                }
                int todayRows = 0;
                for (String text : today) {
                    todayRows += wrap(measure(textFont), text, width - 2 * pad).size();
                }
                return pad + head + rows * (line - 1) + 6 + line + todayRows * (line - 1) + pad - 4;
            }

            @Override
            public void paint(Graphics2D g, int x, int y, int width) {
                frame(g, x, y, width, height(width), new Color(0xa78bfa));
                heading(g, x, y, width, "Итоги сессии", "");
                g.setFont(textFont);
                int ty = y + pad + head + 11;
                for (int i = 0; i < lines.size(); i++) {
                    g.setColor(i < 4 ? TEXT : DIM);
                    for (String part : wrap(g.getFontMetrics(), lines.get(i), width - 2 * pad)) {
                        g.drawString(part, x + pad, ty);
                        ty += line - 1;
                    }
                }
                ty += 6;
                g.setFont(font(Font.PLAIN, 11f));
                g.setColor(DIM);
                g.drawString("За сегодня", x + pad, ty);
                ty += line;
                g.setFont(textFont);
                for (int i = 0; i < today.size(); i++) {
                    g.setColor(i < 2 ? TEXT : DIM);
                    for (String part : wrap(g.getFontMetrics(), today.get(i), width - 2 * pad)) {
                        g.drawString(part, x + pad, ty);
                        ty += line - 1;
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
        int width = Math.max(150, Math.min(maxWidth, 240));
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
            g.setFont(font(Font.BOLD, 11f));
            g.drawString(clip(g, MinimapPanel.label(t), inner), x + 10, ty + 12);
            ty += 16;
            g.setFont(font(Font.PLAIN, 10f));
            g.setColor(DIM);
            String kind = (h.attackTargetId() != 0 ? "атакую" : "выбрана") + String.format("  ·  %,.0f", t.distanceTo(h.x(), h.y()))
                    + (t.lockedByOther() ? "  ·  бьёт другой игрок" : "");
            g.drawString(clip(g, kind, inner), x + 10, ty + 10);
            ty += 14;
            if (bars) {
                valueBar(g, x + 10, ty + 2, inner, 9, t.health().hp(), t.health().hpMax(), HP_BAR);
                valueBar(g, x + 10, ty + 13, inner, 9, t.health().shield(), t.health().shieldMax(), SHIELD_BAR);
                ty += 2 * 9 + 6;
            }
        }
        if (attackers > 0) {
            g.setFont(font(Font.PLAIN, 10f));
            g.setColor(MinimapPanel.ENEMY);
            g.drawString("Атакуют меня: " + attackers, x + 10, ty + 11);
        }
    }

    // ---- a big map ----

    /** Over a big map, top left under the map's name: the target, then who attacks the ship (names, hull). */
    int fightPanel(Graphics2D g, int x, int y, int width, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int top = y;
        Card target = target(w);
        if (target != null) {
            translucent(g, x, y, width, target.height(width));
            target.paint(g, x, y, width);
            y += target.height(width) + 8;
        }
        Card attackers = attackers(w);
        if (attackers != null) {
            translucent(g, x, y, width, attackers.height(width));
            attackers.paint(g, x, y, width);
            y += attackers.height(width) + 8;
        }
        return y - top;
    }

    /**
     * Over a big map, bottom left: the ship as in the game — name, ship, level; hull, shield and nano hull;
     * configuration and speed. Drawn upwards from {@code bottom}.
     */
    void shipPanel(Graphics2D g, int x, int bottom, int width, WorldSnapshot.Hero h) {
        if (h.id() == 0) {
            return;
        }
        boolean nano = h.health().nanoHullMax() > 0;
        String name = h.name() + (h.shipType().isBlank() ? "" : "  " + shipName(h.shipType()));
        String right = (h.config() > 0 ? "конф. " + h.config() : "") + (h.speed() > 0 ? "  ·  " + h.speed() : "")
                + (h.level() > 0 ? "  ·  ур. " + h.level() : "");
        int rows = titleLines(name, right, width);
        int height = pad + rows * line + (nano ? 3 : 2) * (bar + 4) + pad - 4;
        int y = bottom - height;
        translucent(g, x, y, width, height);
        frame(g, x, y, width, height, MinimapPanel.HERO);
        titleRow(g, x, y, width, name, right, TEXT);
        int by = y + pad + rows * line;
        Health hp = h.health();
        bar(g, x + pad, by, width - 2 * pad, bar, hp.hp(), hp.hpMax(), HP_BAR, false);
        bar(g, x + pad, by + bar + 4, width - 2 * pad, bar, hp.shield(), hp.shieldMax(), SHIELD_BAR, false);
        if (nano) {
            bar(g, x + pad, by + 2 * (bar + 4), width - 2 * pad, bar, hp.nanoHull(), hp.nanoHullMax(), NANO_BAR, false);
        }
    }

    /** A shadow behind a card drawn over the map, so it reads on the bright parts of the picture. */
    private static void translucent(Graphics2D g, int x, int y, int w, int h) {
        g.setColor(new Color(0, 0, 0, 90));
        g.fill(new RoundRectangle2D.Double(x - 2, y - 1, w + 4, h + 4, 14, 14));
    }


    /** Over a small map: title and config on the left, status on the right, action, hull and shield. */
    static final int STRIP_HEIGHT = 46;

    void strip(Graphics2D g, int x, int y, int width, WorldFeed feed, String config) {
        int h = STRIP_HEIGHT;
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

    static final int TARGET_STRIP_HEIGHT = 40;

    /**
     * The target over the map's bottom, on the right, in the bottom strip's style (the user, 2026-11-16: as AstraBot and
     * DarkBot — the ship left, the target right): its name in red, what and how far, hull and shield. Nothing without one.
     *
     * @param right the strip's right edge; @param bottom its bottom
     */
    void targetStrip(Graphics2D g, int right, int bottom, int width, WorldSnapshot w) {
        WorldSnapshot.Hero h = w.hero();
        int id = h.attackTargetId() != 0 ? h.attackTargetId() : h.selectedId();
        Ship t = w.ships().get(id);
        if (t == null) {
            return;
        }
        int x = right - width;
        int top = bottom - TARGET_STRIP_HEIGHT;
        g.setColor(new Color(10, 12, 18, 150)); // the portals under it show through
        g.fillRect(x, top, width, TARGET_STRIP_HEIGHT);
        g.setColor(new Color(MinimapPanel.NPC.getRed(), MinimapPanel.NPC.getGreen(), MinimapPanel.NPC.getBlue(), 200));
        g.fillRect(x, top, width, 2);
        g.setFont(font(Font.BOLD, 12f));
        FontMetrics fm = g.getFontMetrics();
        String what = (h.attackTargetId() == id ? "атакую" : "выбрана") + String.format(" · %,.0f", t.distanceTo(h.x(), h.y()))
                .replace(',', ' ');
        Font small = font(Font.PLAIN, 11f);
        int ww = measure(small).stringWidth(what);
        g.setColor(MinimapPanel.shipColor(t, h, w.group()));
        g.drawString(clip(g, MinimapPanel.label(t), width - ww - 28), x + 10, top + 16);
        g.setFont(small);
        g.setColor(DIM);
        g.drawString(what, x + width - 10 - ww, top + 16);
        if (t.health().known()) {
            int bw = (width - 26) / 2;
            bar(g, x + 10, top + 24, bw, 6, t.health().hp(), t.health().hpMax(), HP_BAR, false);
            bar(g, x + 16 + bw, top + 24, bw, 6, t.health().shield(), t.health().shieldMax(), SHIELD_BAR, false);
        }
    }

    // ---- drawing helpers ----

    /** Corners of the cards and bars: small, a classic panel (the user, 2026-11-16: «более классическое, строгое»). */
    static final int CARD_ARC = 0;
    static final int BAR_ARC = 3;

    private void frame(Graphics2D g, int x, int y, int w, int h, Color accent) {
        // square corners (CARD_ARC 0): plain rectangles, which skip the slow anti-aliased shape path
        if (onMap) {
            g.setColor(new Color(10, 12, 18, 165)); // the map shows through, as under the strips
            g.fillRect(x, y, w, h);
        } else {
            g.setColor(CARD);
            g.fillRect(x, y, w, h);
            Object aa = g.getRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_OFF);
            g.setColor(CARD_EDGE);
            g.setStroke(new BasicStroke(1f));
            g.drawRect(x, y, w - 1, h - 1);
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, aa);
        }
        // the card's colour as a thin line along its top
        g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 200));
        g.fillRect(x, y, w, 2);
    }

    /**
     * A section's title as in a classic panel: small grey capitals spaced a little, its note on the right, a thin line
     * under them (the user, 2026-11-16). On the card's first row; the rows below start where they did.
     */
    private void heading(Graphics2D g, int x, int y, int width, String title, String right) {
        Font small = font(Font.PLAIN, 11f);
        g.setFont(small);
        int rw = right.isEmpty() ? 0 : g.getFontMetrics().stringWidth(right);
        if (rw > 0) {
            g.setColor(DIM);
            g.drawString(right, x + width - pad - rw, y + pad + 10);
        }
        g.setFont(headingFont());
        g.setColor(HEADING);
        g.drawString(clip(g, title.toUpperCase(java.util.Locale.ROOT), width - 2 * pad - rw - 8), x + pad, y + pad + 10);
        g.setColor(RULE);
        g.fillRect(x + pad, y + pad + line - 1, width - 2 * pad, 1);
    }

    static final Color HEADING = new Color(0x97a3b6);
    static final Color RULE = new Color(0x3a4659);

    /** Numbers in columns: figures of one width (a monospaced face), right-aligned at {@code right}. */
    private void value(Graphics2D g, String text, int right, int baseline, Color color) {
        g.setFont(valueFont());
        g.setColor(color);
        g.drawString(text, right - g.getFontMetrics().stringWidth(text), baseline);
    }

    private int valueWidth(String text) {
        return measure(valueFont()).stringWidth(text);
    }

    private Font valueFont() {
        return fonts.computeIfAbsent(-2L, k -> new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(11.5f * fontScale));
    }

    /** @return the rows taken: 1, or 2 when the note went under the title */
    private int titleRow(Graphics2D g, int x, int y, int width, String title, String right, Color color) {
        int rows = titleLines(title, right, width);
        g.setFont(font(Font.PLAIN, 11f));
        FontMetrics small = g.getFontMetrics();
        int rw = small.stringWidth(right);
        g.setColor(DIM);
        if (rows == 1) {
            g.drawString(right, x + width - pad - rw, y + pad + 11);
        } else {
            g.drawString(clip(g, right, width - 2 * pad), x + pad, y + pad + 11 + line);
        }
        g.setFont(font(Font.BOLD, 13f));
        g.setColor(color);
        g.drawString(clip(g, title, rows == 1 ? width - 2 * pad - rw - 8 : width - 2 * pad), x + pad, y + pad + 11);
        return rows;
    }

    private void bars(Graphics2D g, int x, int y, int w, Health h) {
        bar(g, x, y, w, bar, h.hp(), h.hpMax(), HP_BAR, false);
        bar(g, x, y + bar + 4, w, bar, h.shield(), h.shieldMax(), SHIELD_BAR, false);
    }

    private void bar(Graphics2D g, int x, int y, int w, int barHeight, double now, double max, Color color, boolean percent) {
        g.setColor(BAR_TRACK);
        g.fill(new RoundRectangle2D.Double(x, y, w, barHeight, BAR_ARC, BAR_ARC));
        if (max == 0 && color == SHIELD_BAR && barHeight >= 9) {
            noShield(g, x, y, w, barHeight); // a configuration with no shield generators
            return;
        }
        if (max <= 0 || now < 0) {
            return;
        }
        double share = Math.min(1, now / max);
        g.setColor(color);
        g.fill(new RoundRectangle2D.Double(x, y, Math.max(BAR_ARC, w * share), barHeight, BAR_ARC, BAR_ARC));
        if (barHeight < 9) {
            return;
        }
        g.setColor(Color.WHITE);
        g.setFont(font(Font.PLAIN, barHeight >= 13 ? 10f : 8f));
        String text = percent || w < 150 ? String.format("%.0f%%", 100 * share) : String.format("%,.0f / %,.0f", now, max);
        FontMetrics fm = g.getFontMetrics();
        if (fm.stringWidth(text) > w - 6) {
            text = String.format("%.0f%%", 100 * share); // big numbers in a narrow bar: the share fits
        }
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (barHeight + fm.getAscent() - fm.getDescent()) / 2);
    }

    /**
     * A hull or shield bar written as the user chose ({@link ViewSettings.Numbers}): numbers by default, shortened
     * («245к / 250к») when the bar is narrow, the share only as the last resort.
     */
    private void valueBar(Graphics2D g, int x, int y, int w, int barHeight, double now, double max, Color color) {
        g.setColor(BAR_TRACK);
        g.fill(new RoundRectangle2D.Double(x, y, w, barHeight, BAR_ARC, BAR_ARC));
        if (max == 0 && color == SHIELD_BAR && barHeight >= 9) {
            noShield(g, x, y, w, barHeight);
            return;
        }
        if (max <= 0 || now < 0) {
            return;
        }
        double share = Math.min(1, now / max);
        g.setColor(color);
        g.fill(new RoundRectangle2D.Double(x, y, Math.max(BAR_ARC, w * share), barHeight, BAR_ARC, BAR_ARC));
        if (barHeight < 9) {
            return;
        }
        g.setFont(font(Font.PLAIN, barHeight >= 13 ? 10f : barHeight >= 11 ? 9f : 8f));
        FontMetrics fm = g.getFontMetrics();
        String pct = String.format("%.0f%%", 100 * share);
        String full = num(now) + " / " + num(max);
        String shortened = shortNum(now) + " / " + shortNum(max);
        List<String> tries = switch (ViewSettings.shared().numbers()) {
            case PERCENT -> List.of(pct);
            case BOTH -> List.of(full + "  ·  " + pct, shortened + " · " + pct, shortened, shortNum(now), pct);
            case EXACT -> List.of(full, shortened, shortNum(now), pct);
        };
        String text = pct;
        for (String t : tries) {
            if (fm.stringWidth(t) <= w - 6) {
                text = t;
                break;
            }
        }
        g.setColor(Color.WHITE);
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (barHeight + fm.getAscent() - fm.getDescent()) / 2);
    }

    private void noShield(Graphics2D g, int x, int y, int w, int barHeight) {
        g.setFont(font(Font.PLAIN, barHeight >= 13 ? 10f : 8f));
        FontMetrics fm = g.getFontMetrics();
        String text = "нет щита";
        g.setColor(DIM);
        g.drawString(text, x + (w - fm.stringWidth(text)) / 2, y + (barHeight + fm.getAscent() - fm.getDescent()) / 2);
    }

    static String num(double v) {
        return String.format("%,.0f", v).replace(',', ' ').replace('\u00a0', ' ');
    }

    /** 245 300 → 245к, 1 250 000 → 1,25М. */
    static String shortNum(double v) {
        if (v >= 1_000_000) {
            return String.format("%.2fМ", v / 1_000_000).replace('.', ',').replace(",00М", "М");
        }
        if (v >= 10_000) {
            return String.format("%.0fк", v / 1_000);
        }
        if (v >= 1_000) {
            return String.format("%.1fк", v / 1_000).replace('.', ',').replace(",0к", "к");
        }
        return String.format("%.0f", v);
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
