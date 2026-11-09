package orbit.app.gui.config;

import javax.swing.JComponent;
import orbit.config.BotConfig;

/** One tab of the config window: shows a part of a config and writes the edits back. */
interface Section {

    String title();

    JComponent view();

    void load(BotConfig c);

    /** The config with this tab's fields applied; {@link IllegalArgumentException} with a message for bad input. */
    BotConfig apply(BotConfig c);
}
