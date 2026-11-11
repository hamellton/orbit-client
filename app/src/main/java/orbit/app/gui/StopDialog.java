package orbit.app.gui;

import java.awt.Component;
import java.util.List;
import javax.swing.JOptionPane;

/**
 * How to stop accounts that are in game: fly to a portal and log out (the game's way), or close at once
 * (an emergency). Accounts not in game yet are stopped at once without asking.
 */
final class StopDialog {

    enum Choice {
        PORTAL, NOW, CANCEL
    }

    private StopDialog() {
    }

    /**
     * @param inGame the names of the accounts in game now
     * @param action what follows, e.g. «и закрыть вкладку»; empty for a plain stop
     */
    static Choice ask(Component parent, String title, List<String> inGame, String action) {
        String who = inGame.size() == 1 ? "«" + inGame.getFirst() + "» в игре"
                : "В игре аккаунтов: " + inGame.size() + " (" + String.join(", ", inGame) + ")";
        String text = "<html>" + who + ". Как остановить" + (action.isEmpty() ? "" : " " + action) + "?<br><br>"
                + "<b>Выйти у портала</b> — долетит до портала и выйдет из игры, как игрок (до 2 минут).<br>"
                + "<b>Закрыть сразу</b> — без полёта и выхода: соединение закрывается немедленно,<br>"
                + "корабль может ещё некоторое время оставаться в космосе.</html>";
        Object[] options = {"Выйти у портала", "Закрыть сразу", "Отмена"};
        int r = JOptionPane.showOptionDialog(parent, text, title, JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE, null, options, options[0]);
        return r == 0 ? Choice.PORTAL : r == 1 ? Choice.NOW : Choice.CANCEL;
    }

    /**
     * Stops the accounts as asked: those not in game at once, those in game by the user's choice.
     *
     * @return false when the user cancelled
     */
    static boolean stop(Component parent, String title, List<ManagedAccount> accounts, String action) {
        List<ManagedAccount> running = accounts.stream().filter(a -> a.phase() != ManagedAccount.Phase.STOPPED).toList();
        List<String> inGame = running.stream().filter(ManagedAccount::inGame).map(ManagedAccount::name).toList();
        Choice choice = inGame.isEmpty() ? Choice.NOW : ask(parent, title, inGame, action);
        if (choice == Choice.CANCEL) {
            return false;
        }
        for (ManagedAccount a : running) {
            if (choice == Choice.PORTAL && a.inGame() && a.phase() == ManagedAccount.Phase.RUNNING) {
                a.stop();
            } else if (choice == Choice.NOW || a.phase() == ManagedAccount.Phase.RUNNING) {
                a.stopNow(); // not in game: nothing to fly or log out with
            }
            // a portal exit already under way is left to finish unless «Закрыть сразу» was chosen
        }
        return true;
    }
}
