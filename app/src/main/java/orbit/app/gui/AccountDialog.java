package orbit.app.gui;

import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.Arrays;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingWorker;
import javax.swing.WindowConstants;
import orbit.auth.proxy.ProxyRoute;
import orbit.config.AccountSettings;
import orbit.secrets.Account;
import orbit.secrets.ProxySpec;
import orbit.secrets.Vault;
import orbit.secrets.VaultException;

/**
 * Adds or edits an account: the vault name, website login, password, server, proxy (with a check
 * through it) and the config it runs. Secrets go only into the vault; the password field left empty
 * keeps the saved password.
 */
final class AccountDialog {

    static final String[] SERVERS = {"int1", "int5", "int7", "int11", "int14", "ru1", "ru5", "de2", "de4", "gb1",
        "fr1", "es1", "pl3", "tr3", "us2", "mx1"};
    private static final String NO_CONFIG = "—";

    private final Vault vault;
    private final String editing;
    private final JDialog dialog;
    private final JTextField name = new JTextField(20);
    private final JTextField login = new JTextField(20);
    private final JPasswordField password = new JPasswordField(20);
    private final JComboBox<String> server = new JComboBox<>(SERVERS);
    private final JPasswordField proxy = new JPasswordField(28);
    private final JCheckBox showProxy = new JCheckBox("показать");
    private final JLabel proxyResult = new JLabel(" ");
    private final JComboBox<String> config = new JComboBox<>();
    private final JLabel error = new JLabel(" ");
    private final char echo;
    private boolean saved;

    private AccountDialog(java.awt.Component parent, Vault vault, String editing, boolean running) {
        this.vault = vault;
        this.editing = editing;
        dialog = new JDialog(javax.swing.SwingUtilities.getWindowAncestor(parent),
                editing == null ? "Новый аккаунт" : "Аккаунт «" + editing + "»", java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        echo = proxy.getEchoChar();
        server.setEditable(true);
        error.setForeground(new java.awt.Color(0xff6b5b));
        showProxy.addActionListener(e -> proxy.setEchoChar(showProxy.isSelected() ? (char) 0 : echo));
        config.addItem(NO_CONFIG);
        try {
            orbit.config.ConfigStore.openDefault().list().forEach(config::addItem);
        } catch (java.io.IOException ignored) {
            // no configs yet
        }

        if (editing != null) {
            Account a = vault.get(editing);
            name.setText(editing);
            name.setEditable(false);
            login.setText(a.username());
            server.setSelectedItem(a.home());
            proxy.setText(a.proxy() == null ? "" : a.proxy());
            password.putClientProperty("JTextField.placeholderText", a.password() != null ? "не менять" : "не задан");
            try {
                String c = AccountSettings.load(AccountSettings.DEFAULT_FILE).account(editing).config();
                config.setSelectedItem(c == null ? NO_CONFIG : c);
            } catch (java.io.IOException ignored) {
                // stays «—»
            }
        } else {
            server.setSelectedItem("int1");
            name.putClientProperty("JTextField.placeholderText", "например main");
        }
        proxy.putClientProperty("JTextField.placeholderText", "пусто — без прокси");

        JButton check = new JButton("Проверить прокси");
        check.addActionListener(e -> checkProxy(check));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = row(form, row, "Имя в боте", name, "Как аккаунт называется в боте: вкладка, журнал, расписание");
        row = row(form, row, "Логин на сайте", login, null);
        row = row(form, row, "Пароль", password, null);
        row = row(form, row, "Сервер", server, "Сервер пилота, например int1 (GE1) или ru1");
        JPanel proxyRow = new JPanel(new BorderLayout(6, 0));
        proxyRow.add(proxy, BorderLayout.CENTER);
        proxyRow.add(showProxy, BorderLayout.EAST);
        row = row(form, row, "Прокси", proxyRow, "socks5://логин:пароль@адрес:порт, http://…, адрес:порт:логин:пароль — "
                + "как дал продавец. Сайт и игра этого аккаунта пойдут только через него");
        JPanel checkRow = new JPanel(new BorderLayout(8, 0));
        checkRow.add(check, BorderLayout.WEST);
        checkRow.add(proxyResult, BorderLayout.CENTER);
        row = row(form, row, "", checkRow, null);
        row = row(form, row, "Конфиг", config, "Что бот делает в игре; расписание — в «Конфиги и расписание…»");
        if (running) {
            JLabel note = new JLabel("Аккаунт запущен: логин, пароль и прокси применятся при следующем запуске.");
            note.setForeground(Hud.DIM);
            row = row(form, row, "", note, null);
        }
        row(form, row, "", error, null);

        JButton cancel = new JButton("Отмена");
        cancel.addActionListener(e -> dialog.dispose());
        JButton ok = new JButton(editing == null ? "Добавить" : "Сохранить");
        ok.addActionListener(e -> save());
        JPanel buttons = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(ok);
        JPanel content = new JPanel(new BorderLayout(0, 8));
        content.setBorder(BorderFactory.createEmptyBorder(12, 14, 10, 14));
        content.add(form, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        dialog.pack();
        dialog.setLocationRelativeTo(parent);
    }

    /**
     * Shows the dialog and waits.
     *
     * @param editing the account to edit, {@code null} = a new one
     * @return the saved account's name, or {@code null} when cancelled
     */
    static String show(java.awt.Component parent, Vault vault, String editing, boolean running) {
        AccountDialog d = new AccountDialog(parent, vault, editing, running);
        d.dialog.setVisible(true);
        return d.saved ? d.name.getText().strip() : null;
    }

    private static int row(JPanel form, int row, String label, JComponent field, String tip) {
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.gridy = row;
        c.gridx = 0;
        c.anchor = GridBagConstraints.WEST;
        JLabel l = new JLabel(label);
        form.add(l, c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.weightx = 1;
        form.add(field, c);
        if (tip != null) {
            l.setToolTipText(tip);
            field.setToolTipText(tip);
        }
        return row + 1;
    }

    private String proxyText() {
        char[] p = proxy.getPassword();
        try {
            return new String(p).strip();
        } finally {
            Arrays.fill(p, '\0');
        }
    }

    /** Through the proxy to the game's site (TLS) and the public IP; no login. */
    private void checkProxy(JButton button) {
        ProxySpec spec;
        try {
            spec = ProxySpec.parse(proxyText());
        } catch (IllegalArgumentException e) {
            showResult(false, "не разбирается: " + e.getMessage());
            return;
        }
        if (spec.direct()) {
            showResult(true, "прокси не задан — аккаунт пойдёт напрямую");
            return;
        }
        button.setEnabled(false);
        proxyResult.setForeground(Hud.DIM);
        proxyResult.setText("проверяю " + spec.describe() + "…");
        new SwingWorker<String, Void>() {
            private boolean good;

            @Override
            protected String doInBackground() {
                try (ProxyRoute route = ProxyRoute.of(spec)) {
                    String text = route.check().describe();
                    good = true;
                    return text;
                } catch (java.io.IOException | RuntimeException e) {
                    return e.getMessage();
                }
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                try {
                    showResult(good, get());
                } catch (Exception e) {
                    showResult(false, String.valueOf(e.getMessage()));
                }
            }
        }.execute();
    }

    private void showResult(boolean good, String text) {
        proxyResult.setForeground(good ? new java.awt.Color(0x34d399) : new java.awt.Color(0xff6b5b));
        proxyResult.setText("<html>" + escape(text) + "</html>");
        dialog.pack();
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private void save() {
        String n = name.getText().strip();
        String user = login.getText().strip();
        String srv = String.valueOf(server.getSelectedItem()).strip().toLowerCase(java.util.Locale.ROOT);
        String proxyValue = proxyText();
        char[] pw = password.getPassword();
        try {
            if (!n.matches("[\\p{L}\\p{N}_.-]{1,64}")) {
                error.setText("Имя: буквы, цифры, «_», «.», «-», до 64 символов");
                return;
            }
            if (editing == null && vault.get(n) != null) {
                error.setText("Аккаунт «" + n + "» уже есть");
                return;
            }
            if (user.isEmpty()) {
                error.setText("Укажите логин на сайте");
                return;
            }
            if (!srv.matches("[a-z]{2,3}\\d{1,3}")) {
                error.setText("Сервер: например int1 или ru1");
                return;
            }
            if (editing == null && pw.length == 0) {
                error.setText("Укажите пароль");
                return;
            }
            ProxySpec spec;
            try {
                spec = ProxySpec.parse(proxyValue);
            } catch (IllegalArgumentException e) {
                error.setText("Прокси: " + e.getMessage());
                return;
            }
            String proxyStored = spec.direct() ? null : proxyValue;
            Account old = editing == null ? null : vault.get(editing);
            String pass = pw.length > 0 ? new String(pw) : old == null ? null : old.password();
            Account a;
            if (old == null || !old.username().equals(user)) {
                a = new Account(user, pass, srv, null, proxyStored); // another login: its sessions are not ours
            } else {
                a = new Account(user, pass, old.server(), old.dosid(), proxyStored, old.dosids());
                if (!a.home().equals(srv)) {
                    a = a.withServer(srv);
                }
            }
            vault.put(n, a);
            vault.save();
            saveConfig(n);
            saved = true;
            dialog.dispose();
        } catch (VaultException | IllegalArgumentException e) {
            error.setText(e.getMessage());
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    private void saveConfig(String n) {
        Object c = config.getSelectedItem();
        String cfg = c == null || NO_CONFIG.equals(c) ? null : c.toString();
        try {
            var file = AccountSettings.DEFAULT_FILE;
            var all = AccountSettings.load(file);
            var entry = all.account(n);
            if (!java.util.Objects.equals(entry.config(), cfg) || !all.accounts().containsKey(n)) {
                all.with(n, new AccountSettings.Entry(cfg, entry.schedule())).save(file);
            }
        } catch (java.io.IOException e) {
            error.setText("Аккаунт сохранён, но конфиг — нет: " + e.getMessage());
        }
    }
}
