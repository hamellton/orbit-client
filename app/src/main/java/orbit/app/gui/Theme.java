package orbit.app.gui;

import com.formdev.flatlaf.FlatDarkLaf;
import com.formdev.flatlaf.FlatLaf;
import java.awt.Color;
import java.awt.Insets;
import java.util.Map;
import javax.swing.UIManager;

/**
 * One palette for every window (the bot window, the configs): deep blue-grey surfaces a step apart,
 * soft borders, light text, one cyan accent. Based on FlatLaf Dark with its theme variables replaced.
 */
public final class Theme {

    /** The window. */
    public static final Color BACKGROUND = new Color(0x0f141c);
    /** Headers, bars, side lists. */
    public static final Color SURFACE = new Color(0x131923);
    /** Fields, lists, tables. */
    public static final Color FIELD = new Color(0x18202b);
    /** Buttons. */
    public static final Color BUTTON = new Color(0x1d2633);
    public static final Color BORDER = new Color(0x263041);
    public static final Color TEXT = new Color(0xdfe6ee);
    public static final Color DIM = new Color(0x8b96a8);
    public static final Color ACCENT = new Color(0x38bdf8);
    public static final Color SELECTION = new Color(0x1e3a54);

    private static boolean done;

    private Theme() {
    }

    /** Once per program, before the first window. */
    /** Text for a label or a dialog that wraps at about {@code widthPx} instead of stretching the window. */
    public static String wrapped(String text, int widthPx) {
        String safe = String.valueOf(text).replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        return "<html><div style='width:" + widthPx + "px'>" + safe + "</div></html>";
    }

    public static synchronized void setup() {
        if (done) {
            return;
        }
        done = true;
        // macOS draws the title bar itself: a dark one to match
        System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
        FlatLaf.setGlobalExtraDefaults(Map.ofEntries(
                Map.entry("@background", hex(BACKGROUND)),
                Map.entry("@foreground", hex(TEXT)),
                Map.entry("@componentBackground", hex(FIELD)),
                Map.entry("@buttonBackground", hex(BUTTON)),
                Map.entry("@menuBackground", hex(SURFACE)),
                Map.entry("@accentColor", hex(ACCENT)),
                Map.entry("@accentBaseColor", hex(ACCENT)),
                Map.entry("@selectionBackground", hex(SELECTION)),
                Map.entry("@selectionForeground", hex(TEXT)),
                Map.entry("@disabledForeground", hex(DIM))));
        FlatDarkLaf.setup();
        UIManager.put("Component.arc", 10);
        UIManager.put("Button.arc", 10);
        UIManager.put("TextComponent.arc", 8);
        UIManager.put("Component.focusWidth", 1);
        UIManager.put("Component.borderColor", BORDER);
        UIManager.put("Component.disabledBorderColor", BORDER);
        UIManager.put("Button.borderColor", BORDER);
        UIManager.put("Button.hoverBackground", new Color(0x243042));
        UIManager.put("Button.pressedBackground", new Color(0x2b384c));
        UIManager.put("Button.default.background", new Color(0x0e4a66));
        UIManager.put("Button.default.foreground", TEXT);
        UIManager.put("Separator.foreground", BORDER);
        UIManager.put("ScrollBar.thumbArc", 999);
        UIManager.put("ScrollBar.thumbInsets", new Insets(2, 2, 2, 2));
        UIManager.put("ScrollBar.width", 10);
        UIManager.put("ScrollBar.track", BACKGROUND);
        UIManager.put("ScrollBar.thumb", new Color(0x2a3546));
        UIManager.put("ScrollBar.hoverThumbColor", new Color(0x35435a));
        UIManager.put("ScrollPane.background", BACKGROUND);
        UIManager.put("TabbedPane.tabHeight", 34);
        UIManager.put("TabbedPane.background", SURFACE);
        UIManager.put("TabbedPane.selectedBackground", BACKGROUND);
        UIManager.put("TabbedPane.hoverColor", new Color(0x1a2230));
        UIManager.put("TabbedPane.underlineColor", ACCENT);
        UIManager.put("TabbedPane.inactiveUnderlineColor", DIM);
        UIManager.put("TabbedPane.contentAreaColor", BORDER);
        UIManager.put("TabbedPane.tabSeparatorsFullHeight", true);
        UIManager.put("Panel.background", BACKGROUND);
        UIManager.put("SplitPane.background", BACKGROUND);
        UIManager.put("SplitPaneDivider.gripColor", BORDER);
        UIManager.put("SplitPane.dividerSize", 7);
        UIManager.put("List.background", FIELD);
        UIManager.put("Table.background", FIELD);
        UIManager.put("Table.gridColor", BORDER);
        UIManager.put("TableHeader.background", SURFACE);
        UIManager.put("TableHeader.separatorColor", BORDER);
        UIManager.put("TableHeader.bottomSeparatorColor", BORDER);
        UIManager.put("ToolTip.background", SURFACE);
        UIManager.put("ToolTip.border", javax.swing.BorderFactory.createLineBorder(BORDER));
        UIManager.put("TitlePane.background", SURFACE);
        UIManager.put("Label.disabledForeground", DIM);
    }

    private static String hex(Color c) {
        return String.format("#%06x", c.getRGB() & 0xffffff);
    }
}
