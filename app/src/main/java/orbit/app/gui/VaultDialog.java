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
 * The new-vault button keeps the old file aside and starts empty.
 */
public final class VaultDialog {

    private final Path file;
    private final JDialog dialog = new JDialog((java.awt.Frame) null, "Orbit — хранилище", true);
    private final JLabel title = new JLabel();
    private final JLabel intro = new JLabel();
    private final JLabel where = new JLabel();
    private final JPasswordField password = new JPasswordField(24);
    private final JPasswordField repeat = new JPasswordField(24);
    private final JLabel passwordLabel = new JLabel("Мастер-пароль");
    private final JLabel repeatLabel = new JLabel("Повторите мастер-пароль");
    private final JLabel error = new JLabel(" ");
    private final JButton ok = new JButton();
    private final JButton fresh = new JButton("Забыл пароль…");
    private Vault vault;

    /**
     * The window (it was not clear it asks for the vault password — on macOS it came up black, only the field
     * and the exit button drawn): a title, what the password is for, the label over the field, a
     * reveal button, the main button on the right; colours set, a repaint once it is up.
     */
    private VaultDialog(Path file) {
        this.file = file;
        java.awt.Color bg = Theme.BACKGROUND;
        title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 17f));
        title.setForeground(Theme.TEXT);
        intro.setForeground(Theme.TEXT);
        where.setForeground(Theme.DIM);
        where.setFont(where.getFont().deriveFont(11f));
        passwordLabel.setForeground(Theme.TEXT);
        repeatLabel.setForeground(Theme.TEXT);
        error.setForeground(new java.awt.Color(0xff6b5b));
        for (JPasswordField f : new JPasswordField[] {password, repeat}) {
            f.putClientProperty("JPasswordField.showRevealButton", true); // FlatLaf: the eye to see what was typed
            f.putClientProperty("JTextField.placeholderText", "пароль");
            f.setPreferredSize(new Dimension(360, 32));
        }

        JPanel form = new JPanel(new java.awt.GridBagLayout());
        form.setBackground(bg);
        java.awt.GridBagConstraints c = new java.awt.GridBagConstraints();
        c.gridx = 0;
        c.fill = java.awt.GridBagConstraints.HORIZONTAL;
        c.anchor = java.awt.GridBagConstraints.WEST;
        c.weightx = 1;
        c.gridy = 0;
        c.insets = new java.awt.Insets(0, 0, 6, 0);
        form.add(title, c);
        c.gridy = 1;
        c.insets = new java.awt.Insets(0, 0, 2, 0);
        form.add(intro, c);
        c.gridy = 2;
        c.insets = new java.awt.Insets(0, 0, 14, 0);
        form.add(where, c);
        c.gridy = 3;
        c.insets = new java.awt.Insets(0, 0, 4, 0);
        form.add(passwordLabel, c);
        c.gridy = 4;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(password, c);
        c.gridy = 5;
        c.insets = new java.awt.Insets(0, 0, 4, 0);
        form.add(repeatLabel, c);
        c.gridy = 6;
        c.insets = new java.awt.Insets(0, 0, 10, 0);
        form.add(repeat, c);
        c.gridy = 7;
        c.insets = new java.awt.Insets(0, 0, 0, 0);
        form.add(error, c);

        JButton cancel = new JButton("Выход");
        cancel.addActionListener(e -> dialog.dispose());
        ok.addActionListener(e -> submit());
        ok.putClientProperty("JButton.buttonType", "default");
        ok.setPreferredSize(new Dimension(120, 32));
        cancel.setPreferredSize(new Dimension(90, 32));
        fresh.setToolTipText("Создать новое хранилище; старое не удаляется — сохранится рядом");
        fresh.putClientProperty("JButton.buttonType", "borderless");
        fresh.setForeground(Theme.DIM);
        fresh.addActionListener(e -> startOver());
        JPanel buttons = new JPanel(new BorderLayout());
        buttons.setBackground(bg);
        buttons.add(fresh, BorderLayout.WEST);
        JPanel right = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT, 8, 0));
        right.setBackground(bg);
        right.add(cancel);
        right.add(ok);
        buttons.add(right, BorderLayout.EAST);

        JPanel content = new JPanel(new BorderLayout(0, 16));
        content.setBackground(bg);
        content.setOpaque(true);
        content.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowOpened(java.awt.event.WindowEvent e) {
                // macOS drew the first frame black but for the focused field: everything once more when it is up
                content.revalidate();
                content.repaint();
                password.requestFocusInWindow();
            }
        });
        mode();
    }

    /**
     * Asks on the Swing thread and waits.
     *
     * @return the opened vault, or {@code null} when the window was closed
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
            d.dialog.setMinimumSize(new Dimension(520, d.dialog.getHeight()));
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
        title.setText(create ? "Новое хранилище Orbit" : "Хранилище Orbit");
        intro.setText(create
                ? "<html>Придумайте мастер-пароль: им будут зашифрованы логины, пароли и прокси аккаунтов.<br>"
                        + "Забытый мастер-пароль восстановить нельзя.</html>"
                : "<html>Введите мастер-пароль, чтобы открыть логины, пароли и прокси аккаунтов.</html>");
        where.setText("Файл: " + file.toAbsolutePath());
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
        int answer = JOptionPane.showConfirmDialog(dialog, "<html>Создать новое хранилище?<br>Старое сохранится в " + aside.getFileName() + ", аккаунты — добавить заново.</html>",
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
