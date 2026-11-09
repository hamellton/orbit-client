package orbit.app.gui.config;

import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** A document listener for "the text changed". */
final class DocumentChange implements DocumentListener {

    private final Runnable action;

    private DocumentChange(Runnable action) {
        this.action = action;
    }

    static DocumentChange of(Runnable action) {
        return new DocumentChange(action);
    }

    @Override
    public void insertUpdate(DocumentEvent e) {
        action.run();
    }

    @Override
    public void removeUpdate(DocumentEvent e) {
        action.run();
    }

    @Override
    public void changedUpdate(DocumentEvent e) {
        action.run();
    }
}
