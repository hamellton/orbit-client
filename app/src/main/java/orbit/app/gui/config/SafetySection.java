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
    private final Threshold healHp = new Threshold("Ремонтный робот / лечение, когда HP ниже");
    private final Threshold healShield = new Threshold("Восстановление щита, когда щит ниже");
    private final Threshold healPod = new Threshold("HP-капсула, когда HP ниже");
    private final JSpinner maxDeaths = Form.number(0, 100_000, 1);
    private final JComboBox<String> revive = new JComboBox<>(REVIVE_NAMES);
    private final JSpinner waitBefore = Form.number(0, 3600, 5);
    private final JSpinner waitAfter = Form.number(0, 3600, 5);
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
                .section("Гибель")
                .row("Остановить бота после смертей", maxDeaths)
                .note("0 — без ограничения.")
                .row("Где возрождаться", revive)
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
    }

    @Override
    public BotConfig apply(BotConfig c) {
        if (Form.intOf(repairMin) > Form.intOf(repairMax)) {
            throw new IllegalArgumentException("Безопасность: порог ухода на ремонт выше, чем «ремонтироваться до»");
        }
        return c.withSafety(new BotConfig.Safety(Form.fraction(repairMin), Form.fraction(repairMax),
                Form.fraction(repairNoNpc), Form.fraction(repairShield), repairMode.get(),
                new BotConfig.Heal(healHp.get(), healShield.get(), healPod.get()), Form.intOf(maxDeaths),
                REVIVE[revive.getSelectedIndex()], Form.intOf(waitBefore), Form.intOf(waitAfter)));
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
