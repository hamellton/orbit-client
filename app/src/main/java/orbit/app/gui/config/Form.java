package orbit.app.gui.config;

import java.awt.Component;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.SpinnerNumberModel;

/** A two-column form (label, field) with section headings, top-aligned, scrolling when long. */
final class Form {

    private final JPanel panel = new JPanel(new GridBagLayout());
    private int row;

    Form() {
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    Form section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        GridBagConstraints c = base(0);
        c.gridwidth = 2;
        c.insets = new Insets(row == 0 ? 0 : 14, 0, 4, 0);
        panel.add(l, c);
        row++;
        return this;
    }

    Form row(String label, Component field) {
        GridBagConstraints l = base(0);
        l.insets = new Insets(3, 0, 3, 12);
        panel.add(new JLabel(label), l);
        GridBagConstraints f = base(1);
        f.fill = GridBagConstraints.NONE;
        panel.add(field, f);
        row++;
        return this;
    }

    /** A field over the full width (tables, text areas); {@code grow} lets it take the spare height. */
    Form wide(Component field, boolean grow) {
        GridBagConstraints c = base(0);
        c.gridwidth = 2;
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1;
        c.weighty = grow ? 1 : 0;
        panel.add(field, c);
        row++;
        return this;
    }

    Form note(String text) {
        JLabel l = new JLabel("<html><div style='width:520px'>" + text + "</div></html>");
        l.setForeground(java.awt.Color.GRAY);
        return wide(l, false);
    }

    /** The finished form; without a growing field the rest of the height stays empty. */
    JComponent build(boolean scroll) {
        GridBagConstraints filler = base(0);
        filler.gridwidth = 2;
        filler.weighty = 0.0001;
        panel.add(new JPanel(), filler);
        if (!scroll) {
            return panel;
        }
        JScrollPane s = new JScrollPane(panel);
        s.setBorder(null);
        s.getVerticalScrollBar().setUnitIncrement(16);
        return s;
    }

    private GridBagConstraints base(int x) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = row;
        c.anchor = GridBagConstraints.WEST;
        c.weightx = x == 1 ? 1 : 0;
        return c;
    }

    static JSpinner number(int min, int max, int step) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(min, min, max, step));
        ((JSpinner.DefaultEditor) s.getEditor()).getTextField().setColumns(6);
        return s;
    }

    static int intOf(JSpinner s) {
        return ((Number) s.getValue()).intValue();
    }

    static void set(JSpinner s, int v) {
        SpinnerNumberModel m = (SpinnerNumberModel) s.getModel();
        int clamped = Math.max(((Number) m.getMinimum()).intValue(), Math.min(((Number) m.getMaximum()).intValue(), v));
        s.setValue(clamped);
    }

    /** Fraction 0..1 shown as 0..100 %. */
    static JSpinner percent() {
        return number(0, 100, 1);
    }

    static double fraction(JSpinner s) {
        return intOf(s) / 100.0;
    }

    static void setFraction(JSpinner s, double v) {
        set(s, (int) Math.round(v * 100));
    }

    static JComponent inline(JComponent... parts) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        for (JComponent c : parts) {
            p.add(c);
        }
        return p;
    }
}
