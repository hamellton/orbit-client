package orbit.app.gui.config;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import orbit.config.BotConfig;
import orbit.config.ItemCatalog;

/** «Питомец» (PET). */
final class PetSection implements Section {

    private final JCheckBox enabled = new JCheckBox("Выпускать питомца");
    private final ItemBox gear = new ItemBox(ItemCatalog.PET_GEARS, true);
    private final JComponent view;

    PetSection() {
        enabled.addActionListener(e -> gear.setEnabled(enabled.isSelected()));
        view = new Form()
                .section("Питомец")
                .row("", enabled)
                .row("Режим", gear)
                .note("Камикадзе во вратах — во вкладке «Врата».")
                .build(true);
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
        enabled.setSelected(c.pet().enabled());
        gear.set(c.pet().gear());
        gear.setEnabled(c.pet().enabled());
    }

    @Override
    public BotConfig apply(BotConfig c) {
        return c.withPet(new BotConfig.Pet(enabled.isSelected(), gear.get()));
    }
}
