package orbit.app.gui;

import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.Icon;
import orbit.app.WorldFeed;
import orbit.world.WorldSnapshot;

/** What an account is doing in one word and one colour: tab dots, header chips, overview cells. */
enum AccountStatus {
    FIGHT("В бою", new Color(0xff6b5b)),
    ONLINE("В игре", new Color(0x34d399)),
    DEAD("Уничтожен", new Color(0xef4444)),
    LINK_LOST("Нет связи", new Color(0xf59e0b)),
    OFFLINE("Не в игре", new Color(0x7c8799));

    final String label;
    final Color color;

    AccountStatus(String label, Color color) {
        this.label = label;
        this.color = color;
    }

    static AccountStatus of(WorldFeed feed) {
        WorldSnapshot.Hero h = feed.snapshot().hero();
        if (feed.linkLost()) {
            return LINK_LOST;
        }
        if (h.id() == 0 || feed.loggedOut()) {
            return OFFLINE;
        }
        if (!h.alive()) {
            return DEAD;
        }
        return h.attackTargetId() != 0 || !h.attackers().isEmpty() ? FIGHT : ONLINE;
    }

    /** A dot in the status colour, for tab titles. */
    static Icon dot(java.util.function.Supplier<AccountStatus> status) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(status.get().color);
                g2.fillOval(x + 1, y + 1, 8, 8);
                g2.dispose();
            }

            @Override
            public int getIconWidth() {
                return 10;
            }

            @Override
            public int getIconHeight() {
                return 10;
            }
        };
    }
}
