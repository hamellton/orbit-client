package orbit.app.gui.config;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;

/** «Питомец» (PET). */
final class PetSection implements Section {

    /**
     * The enemy locator's targets: the game's NPC numbers. 13 = Cubikon is known (the locator's points led to the
     * cube, 2026-11-11); the others are the numbers the server offered on 3-6, named once they are known.
     */
    static final String[] LOCATOR_TARGETS = {"13 — Cubikon", "9", "10", "12", "22", "161"};

    private final JCheckBox enabled = new JCheckBox("Выпускать питомца");
    private final ItemBox gear = new ItemBox(ItemCatalog.PET_GEARS, true);
    private final JComboBox<String> locator = new JComboBox<>(LOCATOR_TARGETS);
    private final JCheckBox repair = new JCheckBox("Чинить уничтоженного");
    private final JCheckBox combo = new JCheckBox("Комбо-ремонт при HP ниже");
    private final JSpinner comboBelow = Form.percent();
    private final JCheckBox kamikaze = new JCheckBox("Камикадзе, когда рядом враги");
    private final JSpinner kamikazeNpcs = Form.number(0, 100, 1);
    private final JCheckBox kamikazeFlagged = new JCheckBox("NPC с флагом KAMIKAZE (список NPC)");
    private final JSpinner kamikazeRadius = Form.number(100, 3_000, 50);
    private final JSpinner kamikazeEvery = Form.number(0, 120, 1);
    private final JComponent view;

    PetSection() {
        locator.setEditable(true);
        locator.setPrototypeDisplayValue("13 — Cubikon (999)"); // the whole name shows
        locator.setToolTipText("Номер NPC в игре; 13 — Cubikon. Другой номер — впишите");
        enabled.addActionListener(e -> sync());
        gear.addActionListener(e -> sync());
        combo.addActionListener(e -> sync());
        kamikaze.addActionListener(e -> sync());
        kamikazeNpcs.setToolTipText("0 — не считать NPC; удобно во вратах");
        combo.setToolTipText("Сервер чинит, только если по кораблю ~11 с не стреляли; потом бот вернёт режим");
        view = new Form()
                .section("Питомец")
                .row("", enabled)
                .row("Режим", gear)
                .row("Цель локатора", locator)
                .row("", repair)
                .row("", Form.inline(combo, comboBelow, new javax.swing.JLabel("%")))
                .note("Комбо-ремонт — когда по кораблю ~11 с не стреляли.")
                .section("Камикадзе")
                .row("", kamikaze)
                .row("NPC рядом, не меньше", kamikazeNpcs)
                .row("", kamikazeFlagged)
                .row("Радиус вокруг питомца", kamikazeRadius)
                .row("Не чаще раза в, мин", kamikazeEvery)
                .note("Хватит одного условия. Потом — основной режим; взорвавшегося питомца бот чинит.")
                .build(true);
    }

    private void sync() {
        boolean on = enabled.isSelected();
        gear.setEnabled(on);
        locator.setEnabled(on && "ENEMY_LOCATOR".equals(gear.get()));
        repair.setEnabled(on);
        combo.setEnabled(on);
        comboBelow.setEnabled(on && combo.isSelected());
        kamikaze.setEnabled(on);
        boolean k = on && kamikaze.isSelected();
        kamikazeNpcs.setEnabled(k);
        kamikazeFlagged.setEnabled(k);
        kamikazeRadius.setEnabled(k);
        kamikazeEvery.setEnabled(k);
    }

    @Override
    public String title() {
        return "Питомец";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        BotConfig.Pet p = c.pet();
        enabled.setSelected(p.enabled());
        gear.set(p.gear());
        String t = String.valueOf(p.locatorTarget());
        String shown = t;
        for (String s : LOCATOR_TARGETS) {
            if (s.equals(t) || s.startsWith(t + " ")) {
                shown = s;
            }
        }
        locator.setSelectedItem(shown);
        repair.setSelected(!p.noRepair());
        combo.setSelected(p.comboRepairBelow() > 0);
        Form.setFraction(comboBelow, p.comboRepairBelow() > 0 ? p.comboRepairBelow() : 0.5);
        BotConfig.Kamikaze k = p.kamikaze();
        kamikaze.setSelected(k.enabled());
        Form.set(kamikazeNpcs, k.minNpcs());
        kamikazeFlagged.setSelected(k.flagged());
        Form.set(kamikazeRadius, k.radius());
        Form.set(kamikazeEvery, k.everyMinutes());
        sync();
    }

    @Override
    public BotConfig apply(BotConfig c) {
        String text = String.valueOf(locator.getEditor().getItem()).strip();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d+)").matcher(text);
        if (!m.find()) {
            throw new IllegalArgumentException("Питомец: цель локатора — номер NPC (13 — Cubikon)");
        }
        BotConfig.Kamikaze k = new BotConfig.Kamikaze(kamikaze.isSelected(), Form.intOf(kamikazeNpcs),
                kamikazeFlagged.isSelected(), Form.intOf(kamikazeRadius), Form.intOf(kamikazeEvery));
        if (k.enabled() && k.minNpcs() == 0 && !k.flagged()) {
            throw new IllegalArgumentException("Питомец: для камикадзе задайте число NPC или флаг KAMIKAZE");
        }
        return c.withPet(new BotConfig.Pet(enabled.isSelected(), gear.get(), Integer.parseInt(m.group(1)),
                !repair.isSelected(), combo.isSelected() ? Form.fraction(comboBelow) : 0, k));
    }
}
