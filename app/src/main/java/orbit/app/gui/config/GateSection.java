package orbit.app.gui.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.table.AbstractTableModel;
import orbit.config.BotConfig;
import orbit.config.GateCatalog;

/**
 * «Врата»: the gate module's settings as a form (the user, 2026-11-14: «давай сделаем юзер френдли, как в даркботе в
 * модуле ГГ») — the gates in their order with on/off and the lives needed, the builder (DarkBot SharedPlugin
 * {@code GateBuilder} and AstraBot {@code GALAXY_GATES}), lives in stock, boxes, Trinity. Keys the form does not know are
 * kept as they were.
 */
final class GateSection implements Section {

    static final String KEY = BotConfig.Module.GALAXY_GATE.key();
    /** The gates the module knows, the first test's first (docs/GALAXY-GATES.md). */
    static final List<String> ALL = List.of("alpha", "beta", "gamma", "zeta", "kappa", "trinity", "delta", "epsilon",
            "lambda", "kronos", "hades", "kuiper");
    static final String[] SPINS = {"1", "5", "10", "100", "По прогрессу (как DarkBot)"};
    static final String[] MULTI = {"Не использовать", "с x2", "с x3", "с x4", "с x5", "с x6"};
    static final String[] TRINITY = {"Hellfire", "Genome"};
    static final String[] DIFFICULTY = {"1 (I)", "2 (II)", "3 (III)", "4 (IV)", "5 (V)"};

    /** One row of the gate list. */
    static final class Row {
        boolean on;
        final String key;
        int minLives;

        Row(boolean on, String key, int minLives) {
            this.on = on;
            this.key = key;
            this.minLives = minLives;
        }
    }

    private final List<Row> rows = new ArrayList<>();
    private final GatesModel model = new GatesModel();
    private final JTable table = new JTable(model);

    private final JCheckBox place = new JCheckBox("Ставить врата, когда все части собраны («подготовить прыжок»)");
    private final JCheckBox build = new JCheckBox("Строить врата: спинить, пока частей не хватает");
    private final JCheckBox energyOnly = new JCheckBox("Только за бесплатную энергию");
    private final JSpinner maxSpinCost = Form.number(0, 10_000, 5);
    private final JSpinner minUridium = Form.number(0, 1_000_000_000, 100_000);
    private final JComboBox<String> spinMode = new JComboBox<>(SPINS);
    private final JSpinner spins100 = Form.percent();
    private final JSpinner spins10 = Form.percent();
    private final JSpinner spins5 = Form.percent();
    private final JSpinner spinDelay = Form.number(100, 5_000, 50);
    private final JComboBox<String> multiplier = new JComboBox<>(MULTI);

    private final JCheckBox buyLives = new JCheckBox("Докупать жизни за уридий");
    private final JSpinner keepLives = Form.number(1, 20, 1);
    private final JSpinner lifeMinUridium = Form.number(0, 1_000_000_000, 100_000);
    private final JSpinner maxLivesToBuy = Form.number(0, 100, 1);

    private final JSpinner boxRadius = Form.number(0, 10_000, 100);
    private final JSpinner hpLinkBelow = Form.number(0, 100, 5);
    private final JCheckBox kamikaze = new JCheckBox("Камикадзе питомца как в DarkBot: собрать NPC с флагом KAMIKAZE в кучу,"
            + " встать и взорвать");
    private final JSpinner kamikazeMin = Form.number(1, 20, 1);
    private final JComboBox<String> kamikazeMode = new JComboBox<>(new String[] {"Бегства (как в «Общем»)", "Боя"});
    private final JComboBox<String> kamikazeLevel = new JComboBox<>(new String[] {"1 (120 с)", "2 (60 с)", "3 (30 с)"});
    private final JCheckBox fightModeInGate = new JCheckBox("К NPC во вратах лететь в конфигурации боя (без режима бегства)");
    /** ABG's NPCs, whose formation may be chosen (AstraBot «Galaxy - Gate»). */
    static final List<String> FORMATION_NPCS = List.of("streuner", "lordakia", "mordon", "saimon", "devolarium",
            "kristallin", "sibelon", "sibelonit", "kristallon", "protegit");
    private final List<ItemBox> npcFormation = new ArrayList<>();
    private final JCheckBox endGate = new JCheckBox("Последний этап врат — своя конфигурация и формация");
    private final JComboBox<String> endGateConfig = new JComboBox<>(new String[] {"Конфигурация 1", "Конфигурация 2"});
    private final ItemBox endGateFormation = new ItemBox(orbit.config.ItemCatalog.FORMATIONS, true);
    private final JSpinner endGateWaves = Form.number(1, 20, 1);
    private final JComboBox<String> trinityAttribute = new JComboBox<>(TRINITY);
    private final JComboBox<String> trinityDifficulty = new JComboBox<>(DIFFICULTY);

    private final JComponent view;

    GateSection() {
        table.setRowHeight(22);
        table.getColumnModel().getColumn(0).setMaxWidth(60);
        table.getColumnModel().getColumn(2).setMaxWidth(130);
        table.setFillsViewportHeight(true);
        JScrollPane list = new JScrollPane(table);
        list.setPreferredSize(new java.awt.Dimension(420, 22 * 13 + 26));
        JButton up = new JButton("↑ Выше");
        JButton down = new JButton("↓ Ниже");
        up.addActionListener(e -> move(-1));
        down.addActionListener(e -> move(1));
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        buttons.add(up);
        buttons.add(down);

        build.addActionListener(e -> sync());
        energyOnly.addActionListener(e -> sync());
        spinMode.addActionListener(e -> sync());
        buyLives.addActionListener(e -> sync());

        place.setToolTipText("Как «Prepare jump» в окне врат: все части есть — врата ставятся, на x-1 появляется портал");
        build.setToolTipText("Как GG Builder DarkBot и spin AstraBot: спины за энергию (и уридий, если разрешено) для первых"
                + " по списку врат, у которых не хватает частей");
        energyOnly.setToolTipText("DarkBot «useExtraEnergyOnly»: спинить только за бесплатную энергию, уридий не тратить");
        maxSpinCost.setToolTipText("DarkBot «maxSpinCost»: спин за уридий, только если он стоит не больше (уридия за спин)");
        minUridium.setToolTipText("DarkBot «minUriBalance», AstraBot «keepMinUriSpin»: не спинить за уридий ниже этого");
        spinMode.setToolTipText("AstraBot «spinAmount» — всегда столько; «по прогрессу» — как DarkBot spins100/10/5");
        spins100.setToolTipText("Пока собрано меньше этой доли частей — спинить по 100 (для ABG — по меньшей из трёх)");
        spins10.setToolTipText("Пока собрано меньше этой доли — по 10");
        spins5.setToolTipText("Пока собрано меньше этой доли — по 5; выше — по одному");
        spinDelay.setToolTipText("AstraBot «spinDelay»: пауза между спинами");
        multiplier.setToolTipText("DarkBot «useMultiAt», AstraBot «spinMultiplier»: включать множитель, когда у врат он такой"
                + " или больше");
        buyLives.setToolTipText("AstraBot «buyLife»: у поставленных врат жизней меньше нужного — докупать по одной");
        keepLives.setToolTipText("Держать не меньше стольких жизней (осталось меньше — купить ещё одну, и так дальше)");
        lifeMinUridium.setToolTipText("Не покупать жизнь, если после покупки уридия останется меньше");
        maxLivesToBuy.setToolTipText("AstraBot «maxLifeToBuy»: не больше стольких покупок на одни врата; 0 — без лимита");
        boxRadius.setToolTipText("AstraBot «collectBoxRadius»: между волнами собирать ящики в этом радиусе от корабля");
        trinityAttribute.setToolTipText("AstraBot TRINITY «attributeType»: Hellfire (атрибут 1) или Genome (атрибут 2)");
        trinityDifficulty.setToolTipText("AstraBot TRINITY «difficulty»: номер карты I–V; выше открытой — берётся открытая");

        for (int i = 0; i < FORMATION_NPCS.size(); i++) {
            npcFormation.add(new ItemBox(orbit.config.ItemCatalog.FORMATIONS, true));
        }
        endGate.addActionListener(e -> sync());
        fightModeInGate.setToolTipText("Иначе к началу каждого этапа бот подлетает в режиме бегства (конфиг «полёт в"
                + " режиме бегства») и переключает конфигурации туда-обратно");
        endGate.setToolTipText("CARNAGE END_GATE_MODE, AstraBot useEndGateConfig: на последних волнах — эта конфигурация"
                + " и формация (формации нет на панели — остаётся текущая)");
        endGateWaves.setToolTipText("Сколько последних волн считать концом врат (ABG — последний этап из 4)");
        Form f = new Form();
        f.section("Врата по порядку")
                .note("Бот идёт в первые включённые врата, у которых есть жизни; не поставлены — ставит или строит их."
                        + " Порядок — кнопками ↑ ↓.")
                .wide(list, false)
                .wide(buttons, false)
                .row("", place)
                .section("Постройка (спины)")
                .row("", build)
                .row("", energyOnly)
                .row("Цена спина за уридий не больше", maxSpinCost)
                .row("Уридий не ниже", minUridium)
                .row("Спинов за раз", spinMode)
                .row("По 100, пока собрано меньше, %", spins100)
                .row("По 10, пока собрано меньше, %", spins10)
                .row("По 5, пока собрано меньше, %", spins5)
                .row("Пауза между спинами, мс", spinDelay)
                .row("Множитель", multiplier)
                .section("Жизни")
                .row("", buyLives)
                .row("Держать жизней не меньше", keepLives)
                .row("Уридий после покупки не ниже", lifeMinUridium)
                .row("Покупок на одни врата (0 — без лимита)", maxLivesToBuy)
                .section("Во вратах")
                .row("Ящики между волнами в радиусе", boxRadius)
                .row("Питомец в HP Link, пока HP корабля ниже, % (0 — нет)", hpLinkBelow)
                .row("", kamikaze)
                .row("Камикадзе: NPC с флагом не меньше", kamikazeMin)
                .row("Камикадзе: уровень (перезарядка)", kamikazeLevel)
                .row("Камикадзе: конфигурация на сборе и взрыве", kamikazeMode)
                .note("SharedPlugin: NPC с флагом KAMIKAZE (в ABG — Kristallin, Saimon, Sibelonit) стреляют по нам у точки"
                        + " сбора — питомец пассивный, корабль кружит на 1 500, они сбиваются; все ближе 300 друг к другу и"
                        + " HP/щит от 95 % — корабль стоит, питомец «Камикадзе».")
                .row("", fightModeInGate)
                .row("", endGate)
                .row("Конец врат: конфигурация", endGateConfig)
                .row("Конец врат: формация", endGateFormation)
                .row("Конец врат: последние волны", endGateWaves)
                .note("NPC врат, которых нет в таблице NPC, бот бьёт с радиусом из профилей DarkBot/AstraBot;"
                        + " свои правила (радиус, патроны) — во вкладке NPC.")
                .section("Trinity Trials")
                .row("Атрибут", trinityAttribute)
                .row("Сложность", trinityDifficulty)
                .section("Формации по NPC во вратах (как AstraBot)")
                .note("Не выбрана — формация боя из конфига. На последнем этапе — формация конца врат.");
        for (int i = 0; i < FORMATION_NPCS.size(); i++) {
            String n = FORMATION_NPCS.get(i);
            f.row(Character.toUpperCase(n.charAt(0)) + n.substring(1), npcFormation.get(i));
        }
        view = f.build(true);
    }

    private void move(int by) {
        int i = table.getSelectedRow();
        int j = i + by;
        if (i < 0 || j < 0 || j >= rows.size()) {
            return;
        }
        Row r = rows.remove(i);
        rows.add(j, r);
        model.fireTableDataChanged();
        table.setRowSelectionInterval(j, j);
    }

    private void sync() {
        boolean b = build.isSelected();
        energyOnly.setEnabled(b);
        maxSpinCost.setEnabled(b && !energyOnly.isSelected());
        minUridium.setEnabled(b && !energyOnly.isSelected());
        spinMode.setEnabled(b);
        boolean progress = spinMode.getSelectedIndex() == SPINS.length - 1;
        spins100.setEnabled(b && progress);
        spins10.setEnabled(b && progress);
        spins5.setEnabled(b && progress);
        spinDelay.setEnabled(b);
        multiplier.setEnabled(b);
        boolean l = buyLives.isSelected();
        keepLives.setEnabled(l);
        lifeMinUridium.setEnabled(l);
        maxLivesToBuy.setEnabled(l);
        boolean e = endGate.isSelected();
        endGateConfig.setEnabled(e);
        endGateFormation.setEnabled(e);
        endGateWaves.setEnabled(e);
    }

    @Override
    public String title() {
        return "Врата";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        rows.clear();
        List<String> seen = new ArrayList<>();
        JsonNode list = m.path("gates");
        if (list.isArray()) {
            for (JsonNode e : list) {
                String key = e.isTextual() ? e.asText() : e.path("gate").asText("");
                GateCatalog.Gate g = GateCatalog.byKey(key);
                if (g != null && !seen.contains(g.key())) {
                    seen.add(g.key());
                    rows.add(new Row(e.isTextual() || e.path("on").asBoolean(true), g.key(),
                            e.isTextual() ? 1 : Math.max(1, e.path("minLives").asInt(1))));
                }
            }
        } else {
            // DarkBot's one gate (its map id, as the import keeps it), else α, β, γ — as the module reads it
            int id = m.path("gateId").asInt(0);
            GateCatalog.Gate one = id == 0 ? null
                    : GateCatalog.byMap(id) != null ? GateCatalog.byMap(id) : GateCatalog.byBuilderId(id);
            for (String k : one != null ? List.of(one.key()) : List.of("alpha", "beta", "gamma")) {
                seen.add(k);
                rows.add(new Row(true, k, 1));
            }
        }
        for (String k : ALL) {
            if (!seen.contains(k)) {
                rows.add(new Row(false, k, 1));
            }
        }
        model.fireTableDataChanged();
        place.setSelected(m.path("place").asBoolean(true));
        build.setSelected(m.path("build").asBoolean(false));
        energyOnly.setSelected(m.path("extraEnergyOnly").asBoolean(m.path("energyOnly").asBoolean(true)));
        Form.set(maxSpinCost, m.path("maxSpinCost").asInt(100));
        Form.set(minUridium, m.path("minUridium").asInt(m.path("keepUridium").asInt(1_000_000)));
        boolean progress = m.path("spins100").asDouble(0) > 0 || m.path("spins10").asDouble(0) > 0
                || m.path("spins5").asDouble(0) > 0;
        int amount = m.path("spinAmount").asInt(1);
        spinMode.setSelectedIndex(progress ? SPINS.length - 1 : amount >= 100 ? 3 : amount >= 10 ? 2 : amount >= 5 ? 1 : 0);
        Form.setFraction(spins100, m.path("spins100").asDouble(0));
        Form.setFraction(spins10, m.path("spins10").asDouble(0));
        Form.setFraction(spins5, m.path("spins5").asDouble(0));
        Form.set(spinDelay, m.path("spinDelay").asInt(500));
        int at = m.path("useMultiAt").asInt(m.path("spinMultiplier").asInt(0));
        multiplier.setSelectedIndex(at >= 2 && at <= 6 ? at - 1 : 0);
        buyLives.setSelected(m.path("buyLives").asBoolean(false));
        Form.set(keepLives, m.path("keepLives").asInt(3));
        Form.set(lifeMinUridium, m.path("lifeMinUridium").asInt(0));
        Form.set(maxLivesToBuy, m.path("maxLivesToBuy").asInt(0));
        Form.set(boxRadius, m.path("boxRadius").asInt(1_500));
        Form.set(hpLinkBelow, m.path("hpLinkBelow").asInt(0));
        kamikaze.setSelected(m.path("kamikaze").path("enabled").asBoolean(false));
        Form.set(kamikazeMin, m.path("kamikaze").path("minNpcs").asInt(5));
        kamikazeMode.setSelectedIndex("fight".equals(m.path("kamikaze").path("mode").asText("run")) ? 1 : 0);
        kamikazeLevel.setSelectedIndex(Math.max(0, Math.min(2, m.path("kamikaze").path("level").asInt(3) - 1)));
        trinityAttribute.setSelectedIndex(m.path("trinityAttribute").asInt(1) == 2 ? 1 : 0);
        trinityDifficulty.setSelectedIndex(Math.max(0, Math.min(4, m.path("trinityDifficulty").asInt(1) - 1)));
        fightModeInGate.setSelected(m.path("fightModeInGate").asBoolean(true));
        endGate.setSelected(m.path("useEndGateConfig").asBoolean(false));
        endGateConfig.setSelectedIndex(m.path("endGateConfig").asInt(2) == 1 ? 0 : 1);
        String ef = m.path("endGateFormation").asText("drone_formation_f-3d-vt");
        endGateFormation.set(ef.isBlank() ? null : ef);
        Form.set(endGateWaves, m.path("endGateWaves").asInt(4));
        for (int i = 0; i < FORMATION_NPCS.size(); i++) {
            String v = m.path("npcFormations").path(FORMATION_NPCS.get(i)).asText("");
            npcFormation.get(i).set(v.isBlank() ? null : v);
        }
        sync();
        loaded = form(m.deepCopy());
    }

    /** The form's settings right after {@link #load}: nothing changed since — the config stays as it was. */
    private ObjectNode loaded;

    @Override
    public BotConfig apply(BotConfig c) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
        ObjectNode m = c.moduleSettings(KEY);
        if (c.module() != BotConfig.Module.GALAXY_GATE && m.isEmpty()) {
            return c; // a config of another module: no gate section added
        }
        ObjectNode out = form(m);
        if (out.equals(loaded)) {
            return c; // untouched: the imported DarkBot settings stay as they are
        }
        return c.withModuleSettings(KEY, out);
    }

    /** {@code m} with the form's settings written over it (keys the form does not know kept). */
    private ObjectNode form(ObjectNode m) {
        ArrayNode gates = m.putArray("gates");
        for (Row r : rows) {
            if (r.on || r.minLives > 1) {
                ObjectNode e = gates.addObject();
                e.put("gate", r.key);
                e.put("on", r.on);
                e.put("minLives", r.minLives);
            }
        }
        m.remove("gateId"); // DarkBot's single gate gives way to the list
        m.remove("energyOnly");
        m.remove("keepUridium");
        m.remove("spinMultiplier");
        m.put("place", place.isSelected());
        m.put("build", build.isSelected());
        m.put("extraEnergyOnly", energyOnly.isSelected());
        m.put("maxSpinCost", Form.intOf(maxSpinCost));
        m.put("minUridium", Form.intOf(minUridium));
        boolean progress = spinMode.getSelectedIndex() == SPINS.length - 1;
        m.put("spinAmount", progress ? 1 : Integer.parseInt(SPINS[spinMode.getSelectedIndex()]));
        m.put("spins100", progress ? Form.fraction(spins100) : 0);
        m.put("spins10", progress ? Form.fraction(spins10) : 0);
        m.put("spins5", progress ? Form.fraction(spins5) : 0);
        m.put("spinDelay", Form.intOf(spinDelay));
        m.put("useMultiAt", multiplier.getSelectedIndex() == 0 ? 0 : multiplier.getSelectedIndex() + 1);
        m.put("buyLives", buyLives.isSelected());
        m.put("keepLives", Form.intOf(keepLives));
        m.put("lifeMinUridium", Form.intOf(lifeMinUridium));
        m.put("maxLivesToBuy", Form.intOf(maxLivesToBuy));
        m.put("boxRadius", Form.intOf(boxRadius));
        m.put("hpLinkBelow", Form.intOf(hpLinkBelow));
        ObjectNode kz = m.putObject("kamikaze");
        kz.put("enabled", kamikaze.isSelected());
        kz.put("minNpcs", Form.intOf(kamikazeMin));
        kz.put("level", kamikazeLevel.getSelectedIndex() + 1);
        kz.put("mode", kamikazeMode.getSelectedIndex() == 1 ? "fight" : "run");
        m.put("trinityAttribute", trinityAttribute.getSelectedIndex() + 1);
        m.put("trinityDifficulty", trinityDifficulty.getSelectedIndex() + 1);
        m.put("fightModeInGate", fightModeInGate.isSelected());
        m.put("useEndGateConfig", endGate.isSelected());
        m.put("endGateConfig", endGateConfig.getSelectedIndex() + 1);
        m.put("endGateFormation", endGateFormation.get() != null ? endGateFormation.get() : "");
        m.put("endGateWaves", Form.intOf(endGateWaves));
        ObjectNode nf = m.putObject("npcFormations");
        for (int i = 0; i < FORMATION_NPCS.size(); i++) {
            String v = npcFormation.get(i).get();
            if (v != null) {
                nf.put(FORMATION_NPCS.get(i), v);
            }
        }
        return m;
    }

    /** The gate list: on, the gate, the lives it needs to be entered. */
    private final class GatesModel extends AbstractTableModel {

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int col) {
            return switch (col) {
                case 0 -> "Вкл";
                case 1 -> "Врата";
                default -> "Входить от жизней";
            };
        }

        @Override
        public Class<?> getColumnClass(int col) {
            return switch (col) {
                case 0 -> Boolean.class;
                case 1 -> String.class;
                default -> Integer.class;
            };
        }

        @Override
        public boolean isCellEditable(int row, int col) {
            return col != 1;
        }

        @Override
        public Object getValueAt(int row, int col) {
            Row r = rows.get(row);
            return switch (col) {
                case 0 -> r.on;
                case 1 -> GateCatalog.byKey(r.key).name();
                default -> r.minLives;
            };
        }

        @Override
        public void setValueAt(Object v, int row, int col) {
            Row r = rows.get(row);
            if (col == 0) {
                r.on = Boolean.TRUE.equals(v);
            } else if (col == 2 && v instanceof Integer n) {
                r.minLives = Math.max(1, Math.min(20, n));
            }
            fireTableCellUpdated(row, col);
        }
    }
}
