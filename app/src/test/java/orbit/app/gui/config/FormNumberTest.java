package orbit.app.gui.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import javax.swing.JFormattedTextField;
import javax.swing.JSpinner;
import org.junit.jupiter.api.Test;

class FormNumberTest {

    @Test
    void aNumberTypedWithSpacesOrCommasIsReadWhole() throws Exception {
        // the user, 2026-11-17: «14 000» in the enrichment's load stayed 14 (the field shows a no-break space)
        for (String typed : new String[] {"14 000", "14 000", "14,000", "14000"}) {
            JSpinner s = Form.number(1, 10_000_000, 100);
            JFormattedTextField f = ((JSpinner.DefaultEditor) s.getEditor()).getTextField();
            f.setText(typed);
            f.commitEdit();
            assertEquals(14_000, Form.intOf(s), typed);
        }
    }
}
