package orbit.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import orbit.world.maps.MapCatalog;

/**
 * Checks a config before the bot starts with it: errors stop the start (with a clear message),
 * warnings are shown and logged.
 */
public final class ConfigCheck {

    public enum Level { ERROR, WARNING }

    public record Problem(Level level, String message) {

        @Override
        public String toString() {
            return (level == Level.ERROR ? "ошибка: " : "внимание: ") + message;
        }
    }

    /** Cubikons live on the x-6 maps. */
    static final Set<Integer> CUBIKON_MAPS = Set.of(18, 22, 26);

    private ConfigCheck() {
    }

    public static List<Problem> check(BotConfig c, MapCatalog maps) {
        List<Problem> out = new ArrayList<>();
        MapCatalog.MapInfo map = maps.info(c.general().workingMap());
        boolean gate = c.module() == BotConfig.Module.GALAXY_GATE;
        if (!map.known() && !gate) {
            out.add(error("рабочая карта " + c.general().workingMap() + " неизвестна"));
        }
        switch (c.module()) {
            case UNSUPPORTED -> out.add(error("модуль конфига пока не поддерживается ("
                    + c.extra().getOrDefault("darkbot.module", Json.MAPPER.getNodeFactory().textNode("?")).asText() + ")"));
            case KILL, KILL_COLLECT -> {
                long targets = c.npcs().values().stream().filter(n -> n.kill() && n.appliesOn(map.id())).count();
                if (targets == 0) {
                    out.add(error("на карте " + map.name() + " не выбрано ни одного NPC для атаки"));
                }
            }
            case CUBIKON -> {
                if (!CUBIKON_MAPS.contains(map.id())) {
                    out.add(warning("кубы появляются на 1-6, 2-6 и 3-6, а рабочая карта — " + map.name()));
                }
                BotConfig.NpcRule cube = c.npcs().get("-=[ Cubikon ]=-");
                if (cube != null && !cube.kill()) {
                    out.add(error("Cubikon в списке NPC отмечен «не бить»"));
                }
            }
            case GALAXY_GATE -> {
                if (!c.modules().containsKey(BotConfig.Module.GALAXY_GATE.key())) {
                    out.add(error("не указаны врата (настройки модуля врат пустые)"));
                }
            }
            default -> {
            }
        }
        if (c.module() != BotConfig.Module.COLLECT && c.loot().ammo() == null) {
            out.add(warning("патроны не указаны — бот стреляет теми, что выбраны в игре"));
        }
        BotConfig.Safety s = c.safety();
        if (s.repairHpMin() > s.repairHpMax()) {
            out.add(error("порог ухода на ремонт (" + pct(s.repairHpMin()) + ") выше порога «отремонтирован» ("
                    + pct(s.repairHpMax()) + ")"));
        }
        if (s.repairHpMin() <= 0.05) {
            out.add(warning("ремонт только при HP ниже " + pct(s.repairHpMin()) + " — корабль часто будет гибнуть"));
        }
        if (s.maxDeaths() <= 0) {
            out.add(warning("число смертей не ограничено — бот не остановится, сколько бы раз корабль ни погиб"));
        }
        if (c.pet().enabled() && c.pet().gear() == null) {
            out.add(warning("питомец включён, но режим не выбран — будет «Охрана»"));
        }
        return out;
    }

    public static boolean hasErrors(List<Problem> problems) {
        return problems.stream().anyMatch(p -> p.level() == Level.ERROR);
    }

    private static Problem error(String m) {
        return new Problem(Level.ERROR, m);
    }

    private static Problem warning(String m) {
        return new Problem(Level.WARNING, m);
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
