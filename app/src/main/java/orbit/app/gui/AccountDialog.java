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
    private final javax.swing.JSpinner clickHold = new javax.swing.JSpinner(
            new javax.swing.SpinnerNumberModel(1.0, 0.0, 30.0, 0.5));
    private final JLabel error = new JLabel(" ");
    private final JComboBox<ShipItem> collectShip = new JComboBox<>();
    private final JComboBox<ShipItem> sellShip = new JComboBox<>();
    private final JLabel shipsResult = new JLabel(" ");
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
                AccountSettings.Entry entry = AccountSettings.load(AccountSettings.DEFAULT_FILE).account(editing);
                String c = entry.config();
                config.setSelectedItem(c == null ? NO_CONFIG : c);
                fillShips(entry.ships(), entry.palladium());
                clickHold.setValue(entry.clickHold() / 1000.0);
            } catch (java.io.IOException ignored) {
                // stays «—»
                fillShips(java.util.List.of(), null);
            }
        } else {
            fillShips(java.util.List.of(), null);
            server.setSelectedItem("int1");
            name.putClientProperty("JTextField.placeholderText", "напр. main");
        }
        proxy.putClientProperty("JTextField.placeholderText", "пусто — напрямую");

        JButton check = new JButton("Проверить");
        check.addActionListener(e -> checkProxy(check));

        JPanel form = new JPanel(new GridBagLayout());
        int row = 0;
        row = row(form, row, "Имя", name, "Имя в боте: вкладка, журнал");
        row = row(form, row, "Логин", login, null);
        row = row(form, row, "Пароль", password, null);
        row = row(form, row, "Сервер", server, "Напр. int1 (GE1) или ru1");
        JPanel proxyRow = new JPanel(new BorderLayout(6, 0));
        proxyRow.add(proxy, BorderLayout.CENTER);
        proxyRow.add(showProxy, BorderLayout.EAST);
        row = row(form, row, "Прокси", proxyRow, "socks5://логин:пароль@адрес:порт или адрес:порт:логин:пароль");
        JPanel checkRow = new JPanel(new BorderLayout(8, 0));
        checkRow.add(check, BorderLayout.WEST);
        checkRow.add(proxyResult, BorderLayout.CENTER);
        row = row(form, row, "", checkRow, null);
        row = row(form, row, "Конфиг", config, "Что бот делает в игре");
        row = row(form, row, "После клика по карте ждать, с", clickHold,
                "Корабль долетел, куда вы кликнули, — столько стоит, потом бот продолжает (по умолчанию 1 с)");
        JLabel palladium = new JLabel("Палладий, два корабля");
        palladium.setFont(palladium.getFont().deriveFont(java.awt.Font.BOLD));
        row = row(form, row, "", palladium, null);
        row = row(form, row, "Корабль сбора", collectShip, "Собирает палладий на 5-3");
        row = row(form, row, "Корабль продажи", sellShip, "Стоит на 5-2 у торговца и продаёт палладий");
        JButton refresh = new JButton("Обновить с сайта");
        refresh.setEnabled(editing != null && !running); // a running account refreshes the list at each login
        refresh.addActionListener(e -> refreshShips(refresh));
        JPanel shipsRow = new JPanel(new BorderLayout(8, 0));
        shipsRow.add(refresh, BorderLayout.WEST);
        shipsRow.add(shipsResult, BorderLayout.CENTER);
        row = row(form, row, "", shipsRow, running ? "Аккаунт запущен: список обновляется при каждом входе бота"
                : "Вход на сайт (без игры), как у бота: сохранённый вход, иначе пароль; через прокси аккаунта");
        if (running) {
            JLabel note = new JLabel("Логин, пароль и прокси — со следующего запуска");
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
            showResult(false, "Ошибка: " + e.getMessage());
            return;
        }
        if (spec.direct()) {
            showResult(true, "Без прокси — напрямую");
            return;
        }
        button.setEnabled(false);
        proxyResult.setForeground(Hud.DIM);
        proxyResult.setText("Проверяю…");
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
        proxyResult.setText(Theme.wrapped(text, 340));
        dialog.pack();
    }

    private void showError(String text) {
        error.setText(text.isBlank() ? " " : Theme.wrapped(text, 380));
        dialog.pack();
    }

    private void save() {
        String n = name.getText().strip();
        String user = login.getText().strip();
        String srv = String.valueOf(server.getSelectedItem()).strip().toLowerCase(java.util.Locale.ROOT);
        String proxyValue = proxyText();
        char[] pw = password.getPassword();
        try {
            if (!n.matches("[\\p{L}\\p{N}_.-]{1,64}")) {
                showError("Имя: буквы, цифры, _ . - (до 64)");
                return;
            }
            if (editing == null && vault.get(n) != null) {
                showError("Аккаунт «" + n + "» уже есть");
                return;
            }
            if (user.isEmpty()) {
                showError("Укажите логин");
                return;
            }
            if (!srv.matches("[a-z]{2,3}\\d{1,3}")) {
                showError("Сервер: напр. int1 или ru1");
                return;
            }
            if (editing == null && pw.length == 0) {
                showError("Укажите пароль");
                return;
            }
            ProxySpec spec;
            try {
                spec = ProxySpec.parse(proxyValue);
            } catch (IllegalArgumentException e) {
                showError("Прокси: " + e.getMessage());
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
            AccountSettings.PalladiumShips pair = chosenShips();
            vault.put(n, a);
            vault.save();
            saveConfig(n, pair);
            saved = true;
            dialog.dispose();
        } catch (VaultException | IllegalArgumentException e) {
            showError(e.getMessage());
        } finally {
            Arrays.fill(pw, '\0');
        }
    }

    private void saveConfig(String n, AccountSettings.PalladiumShips pair) {
        Object c = config.getSelectedItem();
        String cfg = c == null || NO_CONFIG.equals(c) ? null : c.toString();
        try {
            var file = AccountSettings.DEFAULT_FILE;
            var all = AccountSettings.load(file);
            var entry = all.account(n);
            var next = entry.withConfig(cfg).withPalladium(pair)
                    .withClickHold((int) Math.round(((Number) clickHold.getValue()).doubleValue() * 1000));
            if (!next.equals(entry) || !all.accounts().containsKey(n)) {
                all.with(n, next).save(file);
            }
        } catch (java.io.IOException e) {
            showError("Конфиг не сохранён: " + e.getMessage());
        }
    }

    /** A ship in the lists: «Ангар 2: Goliath (Venom)»; the site's id stays behind it. */
    private record ShipItem(int id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private static final ShipItem NO_SHIP = new ShipItem(0, "— не выбран");

    /** The lists from the known ships; a chosen id the list does not have yet is kept as «ангар id». */
    private void fillShips(java.util.List<AccountSettings.Ship> ships, AccountSettings.PalladiumShips chosen) {
        int collect = chosen != null ? chosen.collect() : idOf(collectShip);
        int sell = chosen != null ? chosen.sell() : idOf(sellShip);
        java.util.List<ShipItem> items = new java.util.ArrayList<>();
        items.add(NO_SHIP);
        for (int i = 0; i < ships.size(); i++) {
            items.add(new ShipItem(ships.get(i).id(), "Ангар " + (i + 1) + ": " + ships.get(i).label()));
        }
        for (int want : new int[] {collect, sell}) {
            if (want > 0 && items.stream().noneMatch(s -> s.id() == want)) {
                items.add(new ShipItem(want, "ангар " + want + " (нет в списке с сайта)"));
            }
        }
        for (JComboBox<ShipItem> box : java.util.List.of(collectShip, sellShip)) {
            box.removeAllItems();
            items.forEach(box::addItem);
        }
        select(collectShip, collect);
        select(sellShip, sell);
        if (ships.isEmpty()) {
            shipsResult.setForeground(Hud.DIM);
            shipsResult.setText(editing == null ? "Сохраните аккаунт, потом откройте снова" : "Нажмите «Обновить с сайта»");
        }
    }

    private static int idOf(JComboBox<ShipItem> box) {
        ShipItem s = (ShipItem) box.getSelectedItem();
        return s == null ? 0 : s.id();
    }

    private static void select(JComboBox<ShipItem> box, int id) {
        for (int i = 0; i < box.getItemCount(); i++) {
            if (box.getItemAt(i).id() == id) {
                box.setSelectedIndex(i);
                return;
            }
        }
        box.setSelectedIndex(0);
    }

    /** The chosen pair, {@code null} for none; both or neither, and two different ships. */
    private AccountSettings.PalladiumShips chosenShips() {
        int c = idOf(collectShip);
        int s = idOf(sellShip);
        if (c == 0 && s == 0) {
            return null;
        }
        if (c == 0 || s == 0) {
            throw new IllegalArgumentException("Палладий: выберите оба корабля — сбора и продажи (или ни одного)");
        }
        if (c == s) {
            throw new IllegalArgumentException("Палладий: корабль сбора и корабль продажи должны быть разными");
        }
        return new AccountSettings.PalladiumShips(c, s);
    }

    /** The ships from the site by the saved login (no password, through the account's proxy). */
    private void refreshShips(JButton button) {
        button.setEnabled(false);
        shipsResult.setForeground(Hud.DIM);
        shipsResult.setText("Загружаю…");
        new SwingWorker<java.util.List<AccountSettings.Ship>, Void>() {
            private String failure;

            @Override
            protected java.util.List<AccountSettings.Ship> doInBackground() {
                try {
                    return orbit.app.OrbitMain.siteShips(vault, editing);
                } catch (java.io.IOException | RuntimeException e) {
                    failure = e.getMessage();
                    return null;
                }
            }

            @Override
            protected void done() {
                button.setEnabled(true);
                java.util.List<AccountSettings.Ship> ships = null;
                try {
                    ships = get();
                } catch (Exception e) {
                    failure = String.valueOf(e.getMessage());
                }
                if (ships == null) {
                    shipsResult.setForeground(new java.awt.Color(0xff6b5b));
                    shipsResult.setText(Theme.wrapped("Не загрузить: " + failure, 300));
                } else {
                    fillShips(ships, null);
                    shipsResult.setForeground(new java.awt.Color(0x34d399));
                    shipsResult.setText("Кораблей: " + ships.size());
                }
                dialog.pack();
            }
        }.execute();
    }
}
