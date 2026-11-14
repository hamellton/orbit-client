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
            case UNSUPPORTED -> out.add(error("модуль "
                    + c.extra().getOrDefault("darkbot.module", Json.MAPPER.getNodeFactory().textNode("?")).asText() + " не поддерживается"));
            case KILL, KILL_COLLECT -> {
                long targets = c.npcs().values().stream().filter(n -> n.kill() && n.appliesOn(map.id())).count();
                if (targets == 0) {
                    out.add(error("на " + map.name() + " не выбрано ни одного NPC"));
                }
            }
            case CUBIKON -> {
                com.fasterxml.jackson.databind.JsonNode cs = c.modules().get(BotConfig.Module.CUBIKON.key());
                boolean mapsChosen = cs != null && cs.path("maps").isArray() && !cs.path("maps").isEmpty();
                if (!mapsChosen && !CUBIKON_MAPS.contains(map.id())) {
                    out.add(warning("кубы появляются на 1-6, 2-6, 3-6, а карта — " + map.name()));
                }
                BotConfig.NpcRule cube = c.npcs().get("-=[ Cubikon ]=-");
                if (cube != null && !cube.kill()) {
                    out.add(error("Cubikon в списке NPC отмечен «не бить»"));
                }
            }
            case BEHEMOTH -> {
                BotConfig.NpcRule b = c.npcs().get("\\\\ Mindfire Behemoth //");
                if (b != null && !b.kill()) {
                    out.add(error("Mindfire Behemoth в списке NPC отмечен «не бить»"));
                }
                behemothTrain(c, out);
            }
            case GALAXY_GATE -> {
                if (!c.modules().containsKey(BotConfig.Module.GALAXY_GATE.key())) {
                    out.add(error("не указаны врата"));
                }
            }
            default -> {
            }
        }
        if (c.module() != BotConfig.Module.COLLECT && c.loot().ammo() == null) {
            out.add(warning("патроны не указаны — будут те, что в игре"));
        }
        BotConfig.Safety s = c.safety();
        if (s.repairHpMin() > s.repairHpMax()) {
            out.add(error("порог ухода на ремонт " + pct(s.repairHpMin()) + " выше «отремонтирован» "
                    + pct(s.repairHpMax())));
        }
        if (s.repairHpMin() <= 0.05) {
            out.add(warning("ремонт при HP ниже " + pct(s.repairHpMin()) + " — корабль будет гибнуть"));
        }
        if (s.maxDeaths() <= 0) {
            out.add(warning("смерти не ограничены — бот не остановится"));
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

    /** The train settings differing from the tested ones ({@link BehemothProven}) — said, not forbidden. */
    private static void behemothTrain(BotConfig c, List<Problem> out) {
        com.fasterxml.jackson.databind.JsonNode m = c.modules().get(BotConfig.Module.BEHEMOTH.key());
        if (m == null || !m.path("trainMode").asBoolean(false)) {
            return;
        }
        String fix = " (как надо — кнопка «Поставить как в CARNAGE» во вкладке «Бегемот»)";
        int limit = m.path("trainEnemyLimit").asInt(BehemothProven.TRAIN_LIMIT);
        if (limit == 0) {
            out.add(warning("бегемот среди поезда: от толпы не уходит — при 13–15 врагах смерть без награды" + fix));
        } else if (limit != BehemothProven.TRAIN_LIMIT) {
            out.add(warning("бегемот среди поезда: уходит при врагах больше " + limit + ", проверено "
                    + BehemothProven.TRAIN_LIMIT + fix));
        }
        if (m.path("waitInZone").asBoolean(false)) {
            java.util.List<Integer> maps = new java.util.ArrayList<>();
            m.path("maps").forEach(x -> maps.add(x.asInt()));
            if (maps.isEmpty()) {
                maps.addAll(java.util.List.of(306, 307, 308));
            }
            for (int id : maps) {
                BotConfig.Zones z = c.zones().get(id);
                if (z == null || z.preferred() == null || z.preferred().isEmpty()) {
                    out.add(warning("бегемот: на карте " + id + " нет предпочитаемой зоны — ждать будет у точки по"
                            + " умолчанию (вкладка «Зоны»)"));
                }
            }
        }
        if (!m.path("trainAtLocator").asBoolean(true)) {
            out.add(warning("бегемот среди поезда: толпа у точки пеленгатора не считается" + fix));
        }
        if (!m.path("leaveSkipped").asBoolean(true)) {
            out.add(warning("бегемот среди поезда: пропущенная карта не покидается сразу" + fix));
        }
        if (m.path("useEmp").asBoolean(true) && !m.path("empOnlyPlayers").asBoolean(true)) {
            out.add(warning("бегемот: ЭМИ и тогда, когда стреляют одни NPC — пропадёт зря" + fix));
        }
    }

    private static String pct(double v) {
        return Math.round(v * 100) + "%";
    }
}
