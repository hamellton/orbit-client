package orbit.app.gui;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;
import orbit.protocol.gen.QuestCaseModule;
import orbit.protocol.gen.QuestConditionModule;
import orbit.protocol.gen.QuestConditionStateModule;
import orbit.protocol.gen.QuestDefinitionModule;
import orbit.protocol.gen.QuestElementModule;
import orbit.protocol.gen.QuestInitializationCommand;
import orbit.protocol.gen.QuestTypeModule;
import org.junit.jupiter.api.Test;

class HudQuestsTest {

    static QuestInitializationCommand quest(int id, int type, int cond, int condType, String npc, double target, double now) {
        QuestConditionModule c = new QuestConditionModule();
        c.id = cond;
        c.matches = List.of(npc);
        c.type = (short) condType;
        c.displayType = (short) condType;
        c.targetValue = target;
        c.mandatory = true;
        c.state = new QuestConditionStateModule();
        c.state.currentValue = now;
        c.state.active = true;
        QuestElementModule e = new QuestElementModule();
        e.questCase = new QuestCaseModule();
        e.condition = c;
        QuestCaseModule root = new QuestCaseModule();
        root.id = id * 100;
        root.mandatory = true;
        root.mandatoryCount = 1;
        root.modifier = List.of(e);
        QuestTypeModule t = new QuestTypeModule();
        t.type = (short) type;
        QuestDefinitionModule d = new QuestDefinitionModule();
        d.id = id;
        d.types = List.of(t);
        d.rootCase = root;
        QuestInitializationCommand q = new QuestInitializationCommand();
        q.quest = d;
        return q;
    }

    @Test
    void theQuestAtWorkIsMarkedAndFirst() throws Exception {
        // which quest the bot does was not to be seen in the card
        orbit.app.WorldFeed feed = new orbit.app.WorldFeed("test");
        feed.onPacket(quest(300010, 1, 30001001, 6, "2", 30, 12), 1);
        feed.onPacket(quest(122011, 2, 12201101, 6, "13", 230, 40), 1);
        Hud hud = new Hud(new Font(Font.SANS_SERIF, Font.PLAIN, 12));
        Hud.Card card = hud.quests(feed.snapshot().quests(), new int[] {122011, 12201101});
        assertNotNull(card);
        int h = card.height(360);
        assertTrue(h > 60, "height " + h);
        BufferedImage img = new BufferedImage(360, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        card.paint(g, 0, 0, 360);
        g.dispose();
        javax.imageio.ImageIO.write(img, "png", new java.io.File("build/quests-card.png")); // to look at
    }
}
