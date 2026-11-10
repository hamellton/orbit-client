package orbit.app.gui.config;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JSpinner;
import orbit.config.BotConfig;

/** «Безопасность»: repair thresholds, healing, deaths. */
final class SafetySection implements Section {

    private static final String[] REVIVE = {"BASE", "PORTAL", "SPOT"};
    private static final String[] REVIVE_NAMES = {"На базе", "У ближайшего портала", "На месте гибели"};

    private final JSpinner repairMin = Form.percent();
    private final JSpinner repairMax = Form.percent();
    private final JSpinner repairNoNpc = Form.percent();
    private final JSpinner repairShield = Form.percent();
    private final GeneralSection.ModeRow repairMode = new GeneralSection.ModeRow();
    private final Threshold healHp = new Threshold("Лечение, когда HP ниже");
    private final Threshold healShield = new Threshold("Щит (Aegis, Hammerclaw), когда щит ниже");
    private final Threshold healPod = new Threshold("Ремонтная капсула (Aegis, Hammerclaw), когда HP ниже");
    private final JSpinner maxDeaths = Form.number(0, 100_000, 1);
    private final JComboBox<String> revive = new JComboBox<>(REVIVE_NAMES);
    private final JSpinner waitBefore = Form.number(0, 3600, 5);
    private final JSpinner waitAfter = Form.number(0, 3600, 5);
    private static final BotConfig.FleeMode[] FLEE_MODES = BotConfig.FleeMode.values();
    private final JComboBox<String> fleeMode = new JComboBox<>(new String[] {"Не убегать — игнорировать игроков",
            "Только от тех, кто атакует меня", "От всех врагов поблизости", "Только от игроков из списка"});
    private final javax.swing.JTextArea fleePlayers = new javax.swing.JTextArea(3, 40);
    private final javax.swing.JTextArea fleeNever = new javax.swing.JTextArea(3, 40);
    private final JSpinner fleeSight = Form.number(300, 10_000, 100);
    private final JSpinner fleeRemember = Form.number(5, 3_600, 5);
    private final JCheckBox fleeJump = new JCheckBox("Прыгать в портал, если игрок догоняет или стреляет у портала");
    private final JSpinner fleeWait = Form.number(0, 3_600, 10);
    private final JComponent view;

    SafetySection() {
        view = new Form()
                .section("Ремонт")
                .row("Уходить на ремонт, когда HP ниже, %", repairMin)
                .row("Ремонтироваться до HP, %", repairMax)
                .row("Без целей рядом — когда HP ниже, %", repairNoNpc)
                .row("И ждать щит не меньше, %", repairShield)
                .row("Конфигурация при ремонте", repairMode.view())
                .section("Лечение на ходу")
                .row("", healHp.view())
                .row("", healShield.view())
                .row("", healPod.view())
                .note("Лечение — способность корабля (Solace, Solace Plus, Aegis и Hammerclaw, Orcus), работает и в "
                        + "бою; вне боя ещё и ремонтный робот. Бот нажимает то, что есть на корабле.")
                .section("Бегство от игроков")
                .row("От кого убегать", fleeMode)
                .row("Список (для «из списка»)", new javax.swing.JScrollPane(fleePlayers))
                .row("Никогда не убегать от", new javax.swing.JScrollPane(fleeNever))
                .note("По одному в строке или через запятую: ник игрока или клан в скобках, например [TAG]. Группа и "
                        + "союзные кланы врагами не считаются.")
                .row("«Поблизости» — ближе чем", fleeSight)
                .row("Помнить игрока, с", fleeRemember)
                .row("", fleeJump)
                .row("После прыжка ждать, с", fleeWait)
                .note("Бот летит в конфигурации бегства к порталу, до которого доберётся раньше игрока; после прыжка "
                        + "ждёт на той стороне и возвращается на рабочую карту.")
                .section("Гибель")
                .row("Остановить бота после смертей", maxDeaths)
                .note("0 — без ограничения.")
                .row("Где возрождаться", revive)
                .note("Выбранный вариант бот берёт, сколько бы он ни стоил; если не хватает средств — ремонт на "
                        + "базе; если игра предлагает один вариант — его.")
                .row("Ждать перед возрождением, с", waitBefore)
                .row("Ждать после возрождения, с", waitAfter)
                .build(true);
    }

    @Override
    public String title() {
        return "Безопасность";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        BotConfig.Safety s = c.safety();
        Form.setFraction(repairMin, s.repairHpMin());
        Form.setFraction(repairMax, s.repairHpMax());
        Form.setFraction(repairNoNpc, s.repairHpNoNpc());
        Form.setFraction(repairShield, s.repairToShield());
        repairMode.set(s.repairMode());
        healHp.set(s.heal().hpBelow());
        healShield.set(s.heal().shieldBelow());
        healPod.set(s.heal().hpPodBelow());
        Form.set(maxDeaths, Math.max(0, s.maxDeaths()));
        int r = java.util.Arrays.asList(REVIVE).indexOf(s.revive());
        revive.setSelectedIndex(r < 0 ? 1 : r);
        Form.set(waitBefore, s.waitBeforeReviveSec());
        Form.set(waitAfter, s.waitAfterReviveSec());
        BotConfig.Flee f = c.flee();
        fleeMode.setSelectedIndex(f.mode().ordinal());
        fleePlayers.setText(String.join("\n", f.players()));
        fleeNever.setText(String.join("\n", f.never()));
        Form.set(fleeSight, f.sight());
        Form.set(fleeRemember, f.rememberSec());
        fleeJump.setSelected(f.jump());
        Form.set(fleeWait, f.waitSec());
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (Form.intOf(repairMin) > Form.intOf(repairMax)) {
            throw new IllegalArgumentException("Безопасность: порог ухода на ремонт выше, чем «ремонтироваться до»");
        }
        BotConfig.Flee f = new BotConfig.Flee(FLEE_MODES[fleeMode.getSelectedIndex()], names(fleePlayers.getText()),
                names(fleeNever.getText()), Form.intOf(fleeSight), Form.intOf(fleeRemember), fleeJump.isSelected(),
                Form.intOf(fleeWait));
        if (!f.equals(c.flee())) {
            c = c.withFlee(f);
        }
        return c.withSafety(new BotConfig.Safety(Form.fraction(repairMin), Form.fraction(repairMax),
                Form.fraction(repairNoNpc), Form.fraction(repairShield), repairMode.get(),
                new BotConfig.Heal(healHp.get(), healShield.get(), healPod.get()), Form.intOf(maxDeaths),
                REVIVE[revive.getSelectedIndex()], Form.intOf(waitBefore), Form.intOf(waitAfter)));
    }

    static java.util.Set<String> names(String text) {
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (String part : text.split("[,\n]+")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    /** A checkbox and a percentage; off = -1. */
    private static final class Threshold {

        private final JCheckBox on;
        private final JSpinner value = Form.percent();

        Threshold(String label) {
            on = new JCheckBox(label);
            on.addActionListener(e -> value.setEnabled(on.isSelected()));
        }

        JComponent view() {
            return Form.inline(on, value, new JLabel("%"));
        }

        void set(double v) {
            on.setSelected(v >= 0);
            Form.setFraction(value, v >= 0 ? v : 0.5);
            value.setEnabled(v >= 0);
        }

        double get() {
            return on.isSelected() ? Form.fraction(value) : -1;
        }
    }
}
