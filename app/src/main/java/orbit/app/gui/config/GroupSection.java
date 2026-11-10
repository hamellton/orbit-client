package orbit.app.gui.config;

import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import orbit.config.BotConfig;

/** «Группа» (AstraBot's invitations): accepting, rejecting, inviting, leaving, kicking, the lead. */
final class GroupSection implements Section {

    private static final BotConfig.Accept[] ACCEPT = BotConfig.Accept.values();
    private final JComboBox<String> accept = new JComboBox<>(new String[] {"Не принимать",
            "Только от игроков из списка", "От всех"});
    private final JCheckBox reject = new JCheckBox("Отклонять остальные приглашения (иначе просто не отвечать)");
    private final JCheckBox invite = new JCheckBox("Приглашать игроков из списка, если их нет в группе");
    private final JTextArea players = new JTextArea(5, 40);
    private final JCheckBox leave = new JCheckBox("Выходить из группы, если в ней никого из списка");
    private final JCheckBox kick = new JCheckBox("Будучи лидером, исключать тех, кого нет в списке");
    private final JTextField leader = new JTextField(20);
    private final JComboBox<String> mode = new JComboBox<>(new String[] {"Не менять", "Только лидер", "Все участники"});
    private final JComponent view;

    GroupSection() {
        view = new Form()
                .section("Приглашения")
                .row("Принимать приглашения", accept)
                .row("", reject)
                .note("«Не принимать» вместе с «Отклонять остальные» — то же, что блокировать приглашения.")
                .section("Список игроков")
                .row("Игроки", new JScrollPane(players))
                .note("По одному в строке или через запятую: ник или клан в скобках, например [TAG] (клан узнаётся, "
                        + "когда игрок виден на карте). Список общий для приёма, приглашений и исключения.")
                .row("", invite)
                .section("Своя группа")
                .row("", leave)
                .row("", kick)
                .row("Передать лидерство", leader)
                .note("Ник игрока; бот передаст ему лидерство, как только тот окажется в группе. Пусто — не передавать.")
                .row("Кто может приглашать", mode)
                .note("Все действия — с паузой в несколько секунд, как у игрока.")
                .build(true);
    }

    @Override
    public String title() {
        return "Группа";
    }

    @Override
    public JComponent view() {
        return view;
    }

    @Override
    public void load(BotConfig c) {
        BotConfig.GroupRules g = c.group();
        accept.setSelectedIndex(g.accept().ordinal());
        reject.setSelected(g.rejectOthers());
        invite.setSelected(g.invite());
        players.setText(String.join("\n", g.players()));
        leave.setSelected(g.leaveWithoutListed());
        kick.setSelected(g.kickUnlisted());
        leader.setText(g.leader());
        mode.setSelectedIndex(g.openInvites() == null ? 0 : g.openInvites() ? 2 : 1);
    }

    @Override
    public BotConfig apply(BotConfig c) {
        Boolean open = mode.getSelectedIndex() == 0 ? null : mode.getSelectedIndex() == 2;
        BotConfig.GroupRules g = new BotConfig.GroupRules(ACCEPT[accept.getSelectedIndex()], reject.isSelected(),
                invite.isSelected(), SafetySection.names(players.getText()), leave.isSelected(), kick.isSelected(),
                leader.getText(), open);
        return g.equals(c.group()) ? c : c.withGroup(g);
    }
}
