package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import orbit.secrets.MasterPassword;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;

/**
 * The master password in a window: opens the vault, or creates a new one (the password typed twice).
 * {@code ORBIT_MASTER_PASSWORD} opens it without asking. A forgotten password cannot be recovered:
 * «Новое хранилище…» keeps the old file aside and starts empty.
 */
public final class VaultDialog {

    private final Path file;
    private final JDialog dialog = new JDialog((java.awt.Frame) null, "Orbit — хранилище аккаунтов", true);
    private final JLabel intro = new JLabel();
    private final JPasswordField password = new JPasswordField(22);
    private final JPasswordField repeat = new JPasswordField(22);
    private final JLabel repeatLabel = new JLabel("Ещё раз");
    private final JLabel error = new JLabel(" ");
    private final JButton ok = new JButton();
    private final JButton fresh = new JButton("Новое хранилище…");
    private Vault vault;

    private VaultDialog(Path file) {
        this.file = file;
        error.setForeground(new java.awt.Color(0xff6b5b));
        JPanel form = new JPanel(new java.awt.GridBagLayout());
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.insets = new java.awt.Insets(4, 4, 4, 4);
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.gridwidth = 2;
        form.add(intro, c);
        c.gridwidth = 1;
        c.gridy = 1;
        form.add(new JLabel("Мастер-пароль"), c);
        c.gridx = 1;
        form.add(password, c);
        c.gridx = 0;
        c.gridy = 2;
        form.add(repeatLabel, c);
        c.gridx = 1;
        form.add(repeat, c);
        c.gridx = 0;
        c.gridy = 3;
        c.gridwidth = 2;
        form.add(error, c);

        JButton cancel = new JButton("Выход");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> submit());
        fresh.setToolTipText("Если мастер-пароль забыт: старое хранилище останется в файле рядом, аккаунты нужно будет добавить заново");
        fresh.addActionListener(e -> startOver());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.add(fresh, BorderLayout.WEST);
        JPanel right = new JPanel();
        right.add(cancel);
        right.add(ok);
        buttons.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        mode();
    }

    /**
     * Asks on the Swing thread and waits.
     *
     * @return the opened vault, or {@code null} when the user closed the window
     */
    public static Vault open(Path file) throws Exception {
        String env = System.getenv(MasterPassword.ENV);
        if (env != null && !env.isEmpty()) {
            try {
                return Vault.open(file, env.toCharArray());
            } catch (VaultException e) {
                // a stale variable: ask in the window
            }
        }
        AtomicReference<Vault> out = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Theme.setup();
            VaultDialog d = new VaultDialog(file);
            d.dialog.pack();
            d.dialog.setMinimumSize(new Dimension(460, d.dialog.getHeight()));
            d.dialog.setLocationRelativeTo(null);
            d.dialog.setVisible(true); // modal: returns when closed
            out.set(d.vault);
        });
        return out.get();
    }

    private boolean creating() {
        return !Files.exists(file);
    }

    private void mode() {
        boolean create = creating();
        intro.setText(create
                ? "<html>Хранилище аккаунтов ещё не создано.<br>Придумайте мастер-пароль: им шифруются логины, "
                        + "пароли и прокси.<br>Восстановить забытый мастер-пароль нельзя.</html>"
                : "<html>Введите мастер-пароль хранилища аккаунтов<br><span style='color:#7c8799'>" + file + "</span></html>");
        repeat.setVisible(create);
        repeatLabel.setVisible(create);
        fresh.setVisible(!create);
        ok.setText(create ? "Создать" : "Открыть");
        error.setText(" ");
        password.setText("");
        repeat.setText("");
        dialog.pack();
        password.requestFocusInWindow();
    }

    private void submit() {
        char[] p = password.getPassword();
        char[] r = repeat.getPassword();
        try {
            if (p.length == 0) {
                error.setText("Введите мастер-пароль");
                return;
            }
            if (creating() && !Arrays.equals(p, r)) {
                error.setText("Пароли не совпадают");
                return;
            }
            boolean create = creating();
            Vault v = Vault.open(file, p);
            if (create) {
                v.save(); // the file exists from now on, with this password
            }
            vault = v;
            dialog.dispose();
        } catch (VaultException e) {
            error.setText(Theme.wrapped(e.getMessage(), 360));
            dialog.pack();
            password.selectAll();
            password.requestFocusInWindow();
        } finally {
            Arrays.fill(p, '\0');
            Arrays.fill(r, '\0');
        }
    }

    /** The password is forgotten: move the old file aside (never delete it) and create a new vault. */
    private void startOver() {
        Path aside = file.resolveSibling(file.getFileName().toString().replaceFirst("\\.bin$", "")
                + ".old-" + java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
                + ".bin");
        int answer = JOptionPane.showConfirmDialog(dialog, "<html>Начать с новым хранилищем?<br>Старое останется в файле<br>"
                + aside + "<br>(откроется, если вспомните пароль). Аккаунты нужно будет добавить заново.</html>",
                "Новое хранилище", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer != JOptionPane.OK_OPTION) {
            return;
        }
        try {
            Files.move(file, aside);
        } catch (java.io.IOException e) {
            error.setText(Theme.wrapped("Не переименовать " + file + ": " + e.getMessage(), 360));
            dialog.pack();
            return;
        }
        mode();
    }
}
