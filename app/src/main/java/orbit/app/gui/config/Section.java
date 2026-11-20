package orbit.app.gui.config;

import java.util.Set;
import javax.swing.JComponent;
import orbit.config.BotConfig;

/** One part of the config window: shows a part of a config and writes the edits back. */
interface Section {

    /**
     * Where a part sits in the window's list (kept simple so anyone can tell what is where):
     * the chosen module's own settings, the fight and flight every module uses, the extras that run beside any module
     * (alongside: with whatever module the header chose).
     */
    enum Group {
        MODULE("Модуль"),
        BASE("Бой и полёт"),
        EXTRA("Попутно");

        final String label;

        Group(String label) {
            this.label = label;
        }
    }

    String title();

    JComponent view();

    void load(BotConfig c);

    /** The config with this tab's fields applied; {@link IllegalArgumentException} with a message for bad input. */
    BotConfig apply(BotConfig c);

    default Group group() {
        return Group.BASE;
    }

    /** {@link Group#MODULE}: the main modules this part is shown for (others' parts are hidden). */
    default Set<BotConfig.Module> modules() {
        return Set.of();
    }

    /** {@link Group#EXTRA}: on or off as the form shows it now (the list's dot), {@code null} for no dot. */
    default Boolean on() {
        return null;
    }
}
