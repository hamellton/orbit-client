package orbit.app.gui;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import orbit.config.AccountSettings;
import orbit.config.BotConfig;
import orbit.config.ConfigStore;

/**
 * The LoW group window (docs/LOW.md §11, a place to set these ticks easily): the leader account and
 * his helper accounts; written into the leader's config ({@code modules.low.helpers}), the same ticks as in the config's
 * LoW tab. Only the leader is started; he starts his free helpers himself.
 */
final class LowGroupDialog {

    private static final String KEY = BotConfig.Module.LOW.key();

    private LowGroupDialog() {
    }

    static void show(JFrame owner, List<String> accounts, String selected) {
        if (accounts.isEmpty()) {
            JOptionPane.showMessageDialog(owner, "Аккаунтов нет — сначала добавьте их.", "Группа LoW",
                    JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        JDialog d = new JDialog(owner, "Группа LoW", true);
        JComboBox<String> leader = new JComboBox<>(accounts.toArray(String[]::new));
        if (selected != null && accounts.contains(selected)) {
            leader.setSelectedItem(selected);
        }
        JLabel config = new JLabel(" ");
        config.setForeground(Hud.DIM);
        JPanel boxes = new JPanel(new WrapLayout(FlowLayout.LEFT, 10, 2));
        List<JCheckBox> ticks = new ArrayList<>();
        Runnable fill = () -> {
            String lead = (String) leader.getSelectedItem();
            String cfgName = configOf(lead);
            Set<String> chosen = new LinkedHashSet<>();
            String text;
            if (cfgName == null) {
                text = "У аккаунта не выбран конфиг — выберите его в «Изменить…»";
            } else {
                try {
                    BotConfig c = ConfigStore.openDefault().load(cfgName);
                    c.moduleSettings(KEY).path("helpers").forEach(x -> chosen.add(x.asText()));
                    text = "Конфиг лидера: «" + cfgName + "»" + (c.module() == BotConfig.Module.LOW ? ""
                            : " — модуль в нём не LoW, при сохранении будет поставлен LoW");
                } catch (java.io.IOException | RuntimeException e) {
                    text = "Конфиг «" + cfgName + "» не читается: " + e.getMessage();
                }
            }
            config.setText(Theme.wrapped(text, 460));
            boxes.removeAll();
            ticks.clear();
            for (String a : accounts) {
                if (a.equals(lead)) {
                    continue;
                }
                JCheckBox b = new JCheckBox(a, chosen.stream().anyMatch(a::equalsIgnoreCase));
                ticks.add(b);
                boxes.add(b);
            }
            boxes.revalidate();
            boxes.repaint();
            d.pack();
        };
        leader.addActionListener(e -> fill.run());

        JPanel form = new JPanel(new GridBagLayout());
        form.setBorder(BorderFactory.createEmptyBorder(12, 14, 6, 14));
        GridBagConstraints g = new GridBagConstraints();
        g.gridx = 0;
        g.gridy = 0;
        g.anchor = GridBagConstraints.WEST;
        g.fill = GridBagConstraints.HORIZONTAL;
        g.weightx = 1;
        g.insets = new Insets(4, 0, 4, 0);
        JLabel about = new JLabel(Theme.wrapped("Запускайте только лидера: свободных помощников он запустит сам и поведёт"
                + " в LoW. Аккаунт, который уже в работе, не берётся. У помощника остаются свой конфиг и свой прокси."
                + " Остановили лидера — помощники тоже остановятся. Помощники записываются в конфиг лидера: если этот"
                + " конфиг стоит и у других аккаунтов, они тоже станут лидерами с этими помощниками.", 460));
        form.add(about, g);
        g.gridy++;
        form.add(new JLabel("Лидер"), g);
        g.gridy++;
        form.add(leader, g);
        g.gridy++;
        form.add(config, g);
        g.gridy++;
        form.add(new JLabel("Помощники"), g);
        g.gridy++;
        form.add(boxes, g);

        JButton save = new JButton("Сохранить");
        JButton cancel = new JButton("Отмена");
        save.addActionListener(e -> {
            List<String> chosen = ticks.stream().filter(JCheckBox::isSelected).map(JCheckBox::getText).toList();
            String why = save((String) leader.getSelectedItem(), chosen);
            if (why != null) {
                JOptionPane.showMessageDialog(d, Theme.wrapped(why, 420), "Группа LoW", JOptionPane.WARNING_MESSAGE);
                return;
            }
            d.dispose();
        });
        cancel.addActionListener(e -> d.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(cancel);
        buttons.add(save);
        d.getContentPane().add(form, BorderLayout.CENTER);
        d.getContentPane().add(buttons, BorderLayout.SOUTH);
        fill.run();
        d.setLocationRelativeTo(owner);
        d.setVisible(true);
    }

    private static String configOf(String account) {
        try {
            return AccountSettings.load(AccountSettings.DEFAULT_FILE).account(account).config();
        } catch (java.io.IOException e) {
            return null;
        }
    }

    /** @return {@code null} when saved, otherwise why not */
    static String save(String leader, List<String> helpers) {
        String cfgName = configOf(leader);
        if (cfgName == null) {
            return "У «" + leader + "» не выбран конфиг — выберите его в «Изменить…».";
        }
        ConfigStore store = ConfigStore.openDefault();
        if (store.isDefault(cfgName)) {
            return "Конфиг «" + cfgName + "» из папки «Default» только для чтения — скопируйте его в окне конфигов и"
                    + " выберите копию у лидера.";
        }
        try {
            BotConfig c = store.load(cfgName);
            ObjectNode s = c.moduleSettings(KEY);
            ArrayNode a = s.putArray("helpers");
            helpers.forEach(a::add);
            if (!s.has("between")) {
                s.put("between", "base");
            }
            c = c.withModuleSettings(KEY, s);
            if (c.module() != BotConfig.Module.LOW && !helpers.isEmpty()) {
                c = c.withModule(BotConfig.Module.LOW);
            }
            store.save(cfgName, c);
            return null;
        } catch (java.io.IOException | RuntimeException e) {
            return "Не сохранить конфиг «" + cfgName + "»: " + e.getMessage();
        }
    }
}
