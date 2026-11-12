package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/**
 * «Квесты» (QUESTS, plan 5б.9): which quest types the bot takes at the home base's quest giver, what kinds of
 * conditions it does, and how often it looks at the giver again ({@code modules.quests}: {@code types}, {@code do},
 * {@code revisitMinutes}).
 */
final class QuestsSection implements Section {

    private static final String KEY = BotConfig.Module.QUESTS.key();
    /** Types by setting key; the first four are on by default (the user's choice 2026-11-12). */
    static final Map<String, String> TYPES = new LinkedHashMap<>();
    /** Condition kinds by setting key, all on by default. */
    static final Map<String, String> KINDS = new LinkedHashMap<>();

    static {
        TYPES.put("DAILY", "Ежедневные");
        TYPES.put("WEEKLY", "Еженедельные");
        TYPES.put("EVENT", "Событийные");
        TYPES.put("MISSION", "Миссии (цепочки)");
        TYPES.put("CHALLENGE", "Испытания");
        KINDS.put("KILL", "Убить NPC");
        KINDS.put("DAMAGE", "Урон по NPC");
        KINDS.put("SALVAGE", "Подобрать груз с убитых NPC");
        KINDS.put("BOX", "Собрать бонусные ящики");
        KINDS.put("ORE", "Собрать руду");
        KINDS.put("SELL", "Продать руду");
        KINDS.put("MAP", "Побывать на карте, долететь до точки");
    }

    static final List<String> DEFAULT_TYPES = List.of("DAILY", "WEEKLY", "EVENT", "MISSION");

    private final Map<String, JCheckBox> types = new LinkedHashMap<>();
    private final Map<String, JCheckBox> kinds = new LinkedHashMap<>();
    private final JSpinner revisit = Form.number(30, 1_440, 10);
    private final JCheckBox locator = new JCheckBox("Искать NPC квеста пеленгатором питомца");
    private final Marks marks = new Marks();
    private final javax.swing.JTable table = new javax.swing.JTable(marks);
    private final JComponent view;

    QuestsSection() {
        Form f = new Form().section("Какие квесты брать");
        TYPES.forEach((k, label) -> {
            JCheckBox b = new JCheckBox(label);
            types.put(k, b);
            f.row("", b);
        });
        f.note("Квесты берутся у квестодателя своей базы (x-8, иначе x-1). Миссии — пока есть свободные места,"
                + " ежедневные и еженедельные места не занимают.");
        f.section("Что делать по квестам");
        KINDS.forEach((k, label) -> {
            JCheckBox b = new JCheckBox(label);
            kinds.put(k, b);
            f.row("", b);
        });
        f.note("Берутся только квесты, все условия которых бот умеет. Не делает никогда: убить игроков или урон по"
                + " ним, пройти врата. NPC — на ближайшей карте, где их видели; патроны, безопасность и ящики — из"
                + " этого конфига.");
        f.row("", locator);
        f.note("Пока бот бьёт NPC квеста, питомец в режиме «Пеленгатор врагов» на этого NPC, и бот летит туда, куда"
                + " он показывает. Потом питомец возвращается к режиму из вкладки «Питомец». Питомец должен быть там"
                + " включён; продажа через торговый модуль и камикадзе важнее пеленгатора.");
        f.section("Квесты по номерам");
        f.note("Все квесты, что бот видел у квестодателя или взял (local/quests-seen.json). «Первым» — взять и делать"
                + " раньше всех; «Не делать» — не брать, а взятый не трогать (прогресс сохраняется); «Отменить» — отменить"
                + " взятый (прогресс пропадёт) и не брать. Названий сервер не присылает — квест узнаётся по условиям.");
        table.setAutoCreateRowSorter(true);
        table.setFillsViewportHeight(true);
        table.getColumnModel().getColumn(5).setCellEditor(new javax.swing.DefaultCellEditor(
                new javax.swing.JComboBox<>(Marks.MARKS)));
        int[] widths = {70, 200, 90, 360, 240, 100};
        for (int i = 0; i < widths.length; i++) {
            table.getColumnModel().getColumn(i).setPreferredWidth(widths[i]);
        }
        javax.swing.JScrollPane scroll = new javax.swing.JScrollPane(table);
        scroll.setPreferredSize(new java.awt.Dimension(900, 260));
        f.wide(scroll, true);
        f.section("Квестодатель");
        f.row("Заглядывать снова раз в, мин", revisit);
        f.note("И после каждого сданного квеста — за следующим в цепочке; если делать нечего — раз в час.");
        view = f.build(true);
    }

    @Override
    public String title() {
        return "Квесты";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        List<String> on = strings(m.path("types"), DEFAULT_TYPES);
        types.forEach((k, b) -> b.setSelected(on.contains(k)));
        List<String> doing = strings(m.path("do"), List.copyOf(KINDS.keySet()));
        kinds.forEach((k, b) -> b.setSelected(doing.contains(k)));
        Form.set(revisit, m.path("revisitMinutes").asInt(360));
        locator.setSelected(m.path("locator").asBoolean(false));
        marks.load(orbit.world.QuestCatalog.shared().all(), ints(m.path("first")), ints(m.path("skip")), ints(m.path("cancel")));
    }

    private static List<Integer> ints(com.fasterxml.jackson.databind.JsonNode n) {
        List<Integer> out = new java.util.ArrayList<>();
        n.forEach(x -> {
            if (x.canConvertToInt()) {
                out.add(x.asInt());
            }
        });
        return out;
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        boolean untouched = c.module() != BotConfig.Module.QUESTS && m.isEmpty();
        ArrayNode t = m.putArray("types");
        types.forEach((k, b) -> {
            if (b.isSelected()) {
                t.add(k);
            }
        });
        ArrayNode d = m.putArray("do");
        kinds.forEach((k, b) -> {
            if (b.isSelected()) {
                d.add(k);
            }
        });
        m.put("revisitMinutes", Form.intOf(revisit));
        m.put("locator", locator.isSelected());
        for (String key : new String[] {"first", "skip", "cancel"}) {
            ArrayNode a = m.putArray(key);
            marks.marked(key).forEach(a::add);
            if (a.isEmpty()) {
                m.remove(key);
            }
        }
        if (untouched && strings(t, List.of()).equals(DEFAULT_TYPES) && d.size() == KINDS.size()
                && Form.intOf(revisit) == 360 && !locator.isSelected() && !m.has("first") && !m.has("skip")
                && !m.has("cancel")) {
            return c; // nothing set for a config of another module: do not add the section
        }
        return c.withModuleSettings(KEY, m);
    }

    private static List<String> strings(com.fasterxml.jackson.databind.JsonNode n, List<String> def) {
        if (!n.isArray()) {
            return def;
        }
        List<String> out = new java.util.ArrayList<>();
        n.forEach(x -> out.add(x.asText()));
        return out;
    }

    /** The seen quests with the user's mark: {id, type, conditions, rewards, mark}. */
    static final class Marks extends javax.swing.table.AbstractTableModel {

        static final String NONE = "—";
        static final String[] MARKS = {NONE, "Первым", "Не делать", "Отменить"};
        static final String[] KEYS = {null, "first", "skip", "cancel"};
        private static final String[] COLUMNS = {"№", "Название", "Тип", "Условия", "Награда", "Отметка"};
        private final List<Object[]> rows = new java.util.ArrayList<>();

        void load(Map<Integer, orbit.world.QuestCatalog.Entry> seen, List<Integer> first, List<Integer> skip,
                  List<Integer> cancel) {
            rows.clear();
            java.util.TreeSet<Integer> ids = new java.util.TreeSet<>(seen.keySet());
            ids.addAll(first);
            ids.addAll(skip);
            ids.addAll(cancel);
            for (int id : ids) {
                orbit.world.QuestCatalog.Entry e = seen.get(id);
                String mark = first.contains(id) ? MARKS[1] : skip.contains(id) ? MARKS[2] : cancel.contains(id) ? MARKS[3] : NONE;
                String title = orbit.world.QuestTitles.of(id);
                rows.add(new Object[] {id, title == null ? "" : title,
                    e == null ? "?" : e.type() + (e.minLevel() > 0 ? ", ур. " + e.minLevel() : ""),
                    e == null ? "(бот его ещё не видел)" : e.conditions(), e == null ? "" : e.rewards(), mark});
            }
            fireTableDataChanged();
        }

        /** The numbers marked with a setting key, in the table's order. */
        List<Integer> marked(String key) {
            List<Integer> out = new java.util.ArrayList<>();
            for (Object[] r : rows) {
                int i = java.util.Arrays.asList(MARKS).indexOf(String.valueOf(r[5]));
                if (i > 0 && key.equals(KEYS[i])) {
                    out.add((Integer) r[0]);
                }
            }
            return out;
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return COLUMNS.length;
        }

        @Override
        public String getColumnName(int c) {
            return COLUMNS[c];
        }

        @Override
        public Class<?> getColumnClass(int c) {
            return c == 0 ? Integer.class : String.class;
        }

        @Override
        public boolean isCellEditable(int r, int c) {
            return c == 5;
        }

        @Override
        public Object getValueAt(int r, int c) {
            return rows.get(r)[c];
        }

        @Override
        public void setValueAt(Object v, int r, int c) {
            rows.get(r)[c] = v;
            fireTableCellUpdated(r, c);
        }
    }
}
