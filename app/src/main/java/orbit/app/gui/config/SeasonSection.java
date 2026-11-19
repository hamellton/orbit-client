package orbit.app.gui.config;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.ButtonGroup;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JRadioButton;
import orbit.config.BotConfig;

/**
 * «Сезон и квесты» (the user, 2026-11-19: «я даже не понимаю как мне выбрать, чтобы квесты работали»): one selector
 * for how this config deals with quests — off, alongside the module ({@code modules.quests.alongside}: the passes'
 * rewards, crafting for «collect built recipes», cargo boxes), or as the main module («Квесты»: the selector switches the
 * header's module) — and the passes' switches ({@code season}, {@code seasonReroll}, {@code battlepass}, {@code trek},
 * {@code diminishing}, {@code craft}; on by default). Shown with every module.
 */
final class SeasonSection implements Section {

    private static final String KEY = BotConfig.Module.QUESTS.key();

    private final GeneralSection general;
    private final JRadioButton off = new JRadioButton("Выключено");
    private final JRadioButton along = new JRadioButton("Попутно — модуль работает как обычно");
    private final JRadioButton main = new JRadioButton("Главный модуль — бот сам берёт и выполняет квесты");
    /** The passes' free buttons: setting key → checkbox (on by default). */
    private final Map<String, JCheckBox> passes = new LinkedHashMap<>();
    private final JComponent view;
    /** «Попутно» as last chosen, kept while the module is «Квесты» (that module does it all itself). */
    private boolean alongWanted;
    /** The module before «Главный модуль» switched it to «Квесты», to switch back to. */
    private BotConfig.Module before = BotConfig.Module.KILL_COLLECT;
    private boolean syncing;

    SeasonSection(GeneralSection general) {
        this.general = general;
        ButtonGroup g = new ButtonGroup();
        g.add(off);
        g.add(along);
        g.add(main);
        Form f = new Form().section("Квесты в этом конфиге");
        f.row("", off);
        f.row("", along);
        f.note("Модуль из шапки остаётся главным, к квестодателю и на другие карты ради квестов бот не летает. Попутно:"
                + " забирает награды пропусков (галочки ниже), крафтит для «собрать скрафченное», а «Убийство и сбор»,"
                + " «Убийство NPC» и фарм Мимесиса между эскортами подбирают грузовые ящики (и событийные: Candy, Turkey,"
                + " Prosperous), пока висит миссия на груз. Убийства и ящики модуля засчитываются сами. Миссии сезона"
                + " не перевыбираются, убывающие квесты не принимаются.");
        f.row("", main);
        f.note("Модуль в шапке переключается на «Квесты»: бот летает к квестодателю, берёт квесты и выполняет их по"
                + " одному условию — какие брать и что делать, в разделе «Квесты» группы «Модуль».");
        f.section("Пропуска и события");
        passes.put("season", new JCheckBox("Season Pass: забирать награды уровней (золотые — если пропуск куплен)"));
        passes.put("seasonReroll", new JCheckBox("Season Pass: бесплатно перевыбирать миссии, которые бот не умеет"));
        passes.put("battlepass", new JCheckBox("Battle Pass: забирать награды (если пропуск куплен)"));
        passes.put("trek", new JCheckBox("Galaxy Trek: забирать награды за секторы"));
        passes.put("diminishing", new JCheckBox("Принимать убывающие квесты, которые бот умеет"));
        passes.put("craft", new JCheckBox("Крафтить для «собрать скрафченное»: Precision Targeter (кредиты), RSB-75 BL,"
                + " Seprom BL"));
        passes.values().forEach(b -> f.row("", b));
        f.note("Работают в режимах «Попутно» и «Главный модуль»; в модуле «Мимесис» награды забираются всегда."
                + " Перевыбор и убывающие квесты — только в «Главном модуле». Никогда сам: покупка уровней и перевыбор"
                + " за уридий, поиск в Galaxy Trek (тратит FTL-ключи), обмен ключей Battle Pass. Миссии «выполнить N"
                + " миссий сезона» и «выполнить N заданий» не перевыбираются — они растут сами. Крафт: первый рецепт"
                + " из списка, на который хватает ресурсов (уридий тоже тратится: RSB-75 BL — 4 000 за партию,"
                + " Seprom BL — 10 000).");
        view = f.build(true);
        main.addActionListener(e -> {
            if (!syncing && general.selectedModule() != BotConfig.Module.QUESTS) {
                before = general.selectedModule();
                general.selectModule(BotConfig.Module.QUESTS);
            }
        });
        java.awt.event.ActionListener leaveQuests = e -> {
            alongWanted = along.isSelected();
            if (!syncing && general.selectedModule() == BotConfig.Module.QUESTS) {
                general.selectModule(before == BotConfig.Module.QUESTS ? BotConfig.Module.KILL_COLLECT : before);
            }
        };
        off.addActionListener(leaveQuests);
        along.addActionListener(leaveQuests);
        general.moduleBox().addActionListener(e -> sync());
    }

    /** The selector as the header's module says: «Квесты» — the main module; else as last chosen. */
    private void sync() {
        syncing = true;
        try {
            if (general.selectedModule() == BotConfig.Module.QUESTS) {
                main.setSelected(true);
            } else {
                before = general.selectedModule();
                (alongWanted ? along : off).setSelected(true);
            }
        } finally {
            syncing = false;
        }
    }

    @Override
    public Group group() {
        return Group.EXTRA;
    }

    @Override
    public Boolean on() {
        return !off.isSelected();
    }

    @Override
    public String title() {
        return "Сезон и квесты";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        alongWanted = m.path("alongside").asBoolean(false);
        passes.forEach((k, b) -> b.setSelected(m.path(k).asBoolean(true)));
        if (c.module() != BotConfig.Module.QUESTS) {
            before = c.module();
        }
        sync();
    }

    @Override
    public BotConfig apply(BotConfig c) {
        ObjectNode m = c.moduleSettings(KEY);
        boolean untouched = !c.modules().containsKey(KEY);
        boolean alongside = along.isSelected() || main.isSelected() && alongWanted;
        if (alongside) {
            m.put("alongside", true);
        } else {
            m.remove("alongside");
        }
        passes.forEach((k, b) -> {
            if (b.isSelected()) {
                m.remove(k); // on is the default
            } else {
                m.put(k, false);
            }
        });
        if (untouched && m.isEmpty()) {
            return c; // nothing set for a config that never had the section
        }
        return c.withModuleSettings(KEY, m);
    }
}
