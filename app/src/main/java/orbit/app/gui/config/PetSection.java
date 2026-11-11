package orbit.app.gui.config;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JSpinner;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;

/** «Питомец» (PET). */
final class PetSection implements Section {

    /**
     * The enemy locator's targets: every NPC of the game by name with its number («Cubikon (13)»), from the
     * client's own table; Cubikon first, the bot's usual target.
     */
    static String[] locatorTargets() {
        java.util.List<String> out = new java.util.ArrayList<>();
        java.util.List<orbit.world.maps.NpcCatalog.Npc> all = orbit.world.maps.NpcCatalog.bundled().all();
        all.stream().filter(n -> n.id() == BotConfig.Pet.CUBIKON_LOCATOR).forEach(n -> out.add(n.label()));
        all.stream().filter(n -> n.id() != BotConfig.Pet.CUBIKON_LOCATOR).forEach(n -> out.add(n.label()));
        return out.toArray(String[]::new);
    }

    private final JCheckBox enabled = new JCheckBox("Выпускать питомца");
    private final ItemBox gear = new ItemBox(ItemCatalog.PET_GEARS, true);
    /** The locator's target: a button with the chosen NPC; a click opens the list with a search field. */
    private final javax.swing.JButton locator = new javax.swing.JButton();
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
        locator.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        locator.setToolTipText("Кого ищет пеленгатор — откроется список с поиском");
        locator.addActionListener(e -> {
            String chosen = NpcPicker.pick(locator, "Цель пеленгатора", java.util.List.of(locatorTargets()),
                    locator.getText().replaceFirst(" ▾$", ""), false);
            if (chosen != null) {
                locator.setText(chosen + " ▾");
            }
        });
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
                .note("Хватит одного условия. 0 мин — по кулдауну игры. Потом — основной режим; питомца бот чинит.")
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
        String name = orbit.world.maps.NpcCatalog.bundled().name(p.locatorTarget());
        String shown = (name != null ? name : "NPC") + " (" + p.locatorTarget() + ")";
        locator.setText(shown + " ▾");
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
        String text = locator.getText().replaceFirst(" ▾$", "");
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\((\\d+)\\)$").matcher(text);
        if (!m.find()) {
            throw new IllegalArgumentException("Питомец: выберите цель пеленгатора из списка");
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
