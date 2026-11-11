package orbit.app.gui.config;

import java.awt.Component;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.SpinnerNumberModel;

/**
 * A form of rows «label — field» with section headings and notes that fits any width: a row whose label
 * and field do not fit side by side puts the label above the field, notes wrap to the width, groups of
 * fields wrap onto more lines. As a whole tab it fills a big window and scrolls in a small one ({@link Fit}).
 */
final class Form {

    private enum Kind { SECTION, ROW, WIDE, NOTE }

    private record Row(Kind kind, JComponent label, Component field, boolean grow) {
    }

    static final int LABEL_GAP = 12;
    private static final int ROW_GAP = 6;
    private static final int SECTION_GAP = 14;
    private static final int COLUMN_GAP = 36;

    private final List<Row> rows = new ArrayList<>();
    private final JPanel panel = new JPanel(new Layout());

    Form() {
        panel.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
    }

    Form section(String title) {
        JLabel l = new JLabel(title);
        l.setFont(l.getFont().deriveFont(Font.BOLD));
        return add(new Row(Kind.SECTION, l, null, false));
    }

    Form row(String label, Component field) {
        return add(new Row(Kind.ROW, label.isEmpty() ? null : new JLabel(label), field, false));
    }

    /** A field over the full width (tables, text areas); {@code grow} lets it take the spare height. */
    Form wide(Component field, boolean grow) {
        return add(new Row(Kind.WIDE, null, field, grow));
    }

    /** A grey explanation that wraps to the form's width. */
    Form note(String text) {
        JTextArea t = new JTextArea(text);
        t.setLineWrap(true);
        t.setWrapStyleWord(true);
        t.setEditable(false);
        t.setFocusable(false);
        t.setOpaque(false);
        t.setBorder(null);
        t.setForeground(java.awt.Color.GRAY);
        t.setFont(new JLabel().getFont());
        return add(new Row(Kind.NOTE, null, t, false));
    }

    private Form add(Row r) {
        rows.add(r);
        if (r.label() != null) {
            panel.add(r.label());
        }
        if (r.field() != null) {
            panel.add(r.field());
        }
        return this;
    }

    /**
     * The finished form.
     *
     * @param scroll as a whole tab: fits any window (fills it when there is room, scrolls when there is not);
     *               false for a part of a bigger page
     */
    JComponent build(boolean scroll) {
        return scroll ? Fit.of(panel) : panel;
    }

    /** Lays the rows out for the width it is given; the height follows. */
    private final class Layout implements LayoutManager {

        private int labelWidth(List<Row> part) {
            int w = 0;
            for (Row r : part) {
                if (r.kind() == Kind.ROW && r.label() != null) {
                    w = Math.max(w, r.label().getPreferredSize().width);
                }
            }
            return w;
        }

        /** The narrowest a field can be: a group of fields wraps down to its widest member. */
        private int minWidth(Component c) {
            if (c instanceof JTextArea) {
                return 120;
            }
            if (c instanceof Container k && k.getLayout() instanceof orbit.app.gui.WrapLayout) {
                int w = 0;
                for (Component m : k.getComponents()) {
                    w = Math.max(w, m.getPreferredSize().width);
                }
                Insets in = k.getInsets();
                return w + in.left + in.right + 12;
            }
            return Math.min(c.getPreferredSize().width, Math.max(c.getMinimumSize().width, 60));
        }

        /** The width a component wants: a group of fields — all of them in one line, whatever its size now. */
        private int prefWidth(Component c) {
            if (c instanceof Container k && k.getLayout() instanceof java.awt.FlowLayout fl) {
                int w = 0;
                int n = 0;
                for (Component m : k.getComponents()) {
                    if (m.isVisible()) {
                        w += m.getPreferredSize().width;
                        n++;
                    }
                }
                Insets in = k.getInsets();
                return w + fl.getHgap() * (n + 1) + in.left + in.right;
            }
            return c.getPreferredSize().width;
        }

        /** The height of a component at a width (wrapping text and field groups grow taller when narrow). */
        private int heightAt(Component c, int width) {
            c.setSize(width, Math.max(1, c.getHeight()));
            return c.getPreferredSize().height;
        }

        /** The width the whole form wants in one column, without the border. */
        private int naturalWidth() {
            int lw = labelWidth(rows);
            int widest = 0;
            for (Row r : rows) {
                if (r.kind() == Kind.ROW) {
                    widest = Math.max(widest, prefWidth(r.field()) + (r.label() == null && lw == 0 ? 0 : lw + LABEL_GAP));
                } else if (r.kind() == Kind.WIDE) {
                    widest = Math.max(widest, prefWidth(r.field()));
                } else if (r.kind() == Kind.SECTION) {
                    widest = Math.max(widest, r.label().getPreferredSize().width);
                }
            }
            return Math.max(widest, 320);
        }

        /**
         * A wide window shows a long form in up to three columns, split between sections, so it needs no
         * scrolling; forms with a growing table or map keep one column.
         */
        private List<List<Row>> columns(int w) {
            boolean grows = rows.stream().anyMatch(r -> r.kind() == Kind.WIDE && r.grow());
            List<List<Row>> blocks = new ArrayList<>();
            for (Row r : rows) {
                if (blocks.isEmpty() || r.kind() == Kind.SECTION) {
                    blocks.add(new ArrayList<>());
                }
                blocks.getLast().add(r);
            }
            int cols = grows ? 1 : Math.max(1, Math.min(Math.min(3, blocks.size()),
                    (w + COLUMN_GAP) / (naturalWidth() + COLUMN_GAP)));
            if (cols == 1) {
                return List.of(rows);
            }
            int colWidth = (w - (cols - 1) * COLUMN_GAP) / cols;
            int[] bh = new int[blocks.size()];
            int total = 0;
            for (int i = 0; i < blocks.size(); i++) {
                bh[i] = layRows(blocks.get(i), 0, 0, colWidth, 0, false, false);
                total += bh[i];
            }
            if (total < 500) {
                return List.of(rows); // short enough for one column: keep it simple
            }
            // fill the columns in order, each up to an even share of the height
            List<List<Row>> out = new ArrayList<>();
            int target = total / cols;
            int used = 0;
            for (int i = 0; i < blocks.size(); i++) {
                boolean full = !out.isEmpty() && used > 0 && used + bh[i] / 2 > target && out.size() < cols;
                if (out.isEmpty() || full) {
                    out.add(new ArrayList<>());
                    used = 0;
                }
                out.getLast().addAll(blocks.get(i));
                used += bh[i] + SECTION_GAP;
            }
            return out;
        }

        /** Measures (and with {@code place} lays out) some rows in a column; returns the height used. */
        private int layRows(List<Row> part, int x0, int y0, int w, int height, boolean place, boolean minimum) {
            int lw = labelWidth(part);
            int[] h = new int[part.size()];
            boolean[] stacked = new boolean[part.size()];
            int total = 0;
            int growing = 0;
            for (int i = 0; i < part.size(); i++) {
                Row r = part.get(i);
                int gap = i == 0 ? 0 : r.kind() == Kind.SECTION ? SECTION_GAP : ROW_GAP;
                switch (r.kind()) {
                    case SECTION -> h[i] = r.label().getPreferredSize().height;
                    case NOTE -> h[i] = heightAt(r.field(), w);
                    case WIDE -> {
                        h[i] = minimum && r.grow() ? r.field().getMinimumSize().height : heightAt(r.field(), w);
                        if (r.grow()) {
                            growing++;
                        }
                    }
                    case ROW -> {
                        int fw = prefWidth(r.field());
                        int room = x0 + w - fieldX(r, x0, lw);
                        stacked[i] = r.label() != null && fw > room;
                        int fieldWidth = Math.max(1, Math.min(fw, stacked[i] ? w : room));
                        int fh = heightAt(r.field(), fieldWidth);
                        h[i] = stacked[i] ? r.label().getPreferredSize().height + 2 + fh
                                : Math.max(fh, r.label() == null ? 0 : r.label().getPreferredSize().height);
                    }
                }
                total += gap + h[i];
            }
            if (!place) {
                return total;
            }
            // spare height goes to the growing rows; short of height, they give it back down to their minimum
            int spare = height - total;
            int y = y0;
            for (int i = 0; i < part.size(); i++) {
                Row r = part.get(i);
                y += i == 0 ? 0 : r.kind() == Kind.SECTION ? SECTION_GAP : ROW_GAP;
                int rh = h[i];
                if (r.kind() == Kind.WIDE && r.grow() && growing > 0) {
                    rh = Math.max(Math.min(h[i], r.field().getMinimumSize().height), h[i] + spare / growing);
                }
                switch (r.kind()) {
                    case SECTION -> r.label().setBounds(x0, y, w, rh);
                    case NOTE, WIDE -> r.field().setBounds(x0, y, w, rh);
                    case ROW -> {
                        Component f = r.field();
                        int fw = prefWidth(f);
                        if (stacked[i]) {
                            int lh = r.label().getPreferredSize().height;
                            r.label().setBounds(x0, y, w, lh);
                            int fwidth = Math.min(fw, w);
                            f.setBounds(x0, y + lh + 2, fwidth, heightAt(f, fwidth));
                        } else {
                            int fx = fieldX(r, x0, lw);
                            int fwidth = Math.max(1, Math.min(fw, x0 + w - fx));
                            int fh = heightAt(f, fwidth);
                            if (r.label() != null) {
                                int lh = r.label().getPreferredSize().height;
                                r.label().setBounds(x0, y + Math.max(0, (Math.min(fh, 32) - lh) / 2), lw, lh);
                            }
                            f.setBounds(fx, y, fwidth, fh);
                        }
                    }
                }
                y += rh;
            }
            return y - y0;
        }

        /** The field's x in a row that is not stacked. */
        private int fieldX(Row r, int x0, int lw) {
            return r.label() == null && lw == 0 ? x0 : x0 + lw + LABEL_GAP;
        }

        /** Measures (and with {@code place} lays out) the form; returns its height. */
        private int lay(Container parent, int width, int height, boolean place, boolean minimum) {
            Insets in = parent.getInsets();
            int w = Math.max(1, width - in.left - in.right);
            List<List<Row>> cols = columns(w);
            int colWidth = cols.size() == 1 ? w : Math.min(naturalWidth() + 80, (w - (cols.size() - 1) * COLUMN_GAP) / cols.size());
            int tallest = 0;
            for (int c = 0; c < cols.size(); c++) {
                int x = in.left + c * (colWidth + COLUMN_GAP);
                tallest = Math.max(tallest, layRows(cols.get(c), x, in.top, colWidth,
                        height - in.top - in.bottom, place, minimum));
            }
            return tallest + in.top + in.bottom;
        }

        @Override
        public void layoutContainer(Container parent) {
            lay(parent, parent.getWidth(), parent.getHeight(), true, false);
        }

        @Override
        public Dimension preferredLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int width = naturalWidth() + in.left + in.right;
            int at = parent.getWidth() > 0 ? parent.getWidth() : width;
            return new Dimension(width, lay(parent, at, 0, false, false));
        }

        @Override
        public Dimension minimumLayoutSize(Container parent) {
            Insets in = parent.getInsets();
            int w = 0;
            for (Row r : rows) {
                if (r.label() != null) {
                    w = Math.max(w, Math.min(r.label().getPreferredSize().width, 220));
                }
                if (r.field() != null) {
                    w = Math.max(w, minWidth(r.field()));
                }
            }
            w += in.left + in.right;
            int at = Math.max(w, parent.getWidth());
            // the height at the width it will get: more columns on a wide window, fewer rows to scroll
            return new Dimension(w, lay(parent, at, 0, false, true));
        }

        @Override
        public void addLayoutComponent(String name, Component comp) {
        }

        @Override
        public void removeLayoutComponent(Component comp) {
        }
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

    /** A toolbar: buttons in one line that never wrap (outside a form's rows). */
    static JComponent bar(JComponent... parts) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        for (JComponent c : parts) {
            p.add(c);
        }
        return p;
    }

    /** Fields in one line that wrap onto more lines when the form is narrow. */
    static JComponent inline(JComponent... parts) {
        JPanel p = new JPanel(new orbit.app.gui.WrapLayout(java.awt.FlowLayout.LEFT, 6, 0));
        for (JComponent c : parts) {
            p.add(c);
        }
        return p;
    }
}
