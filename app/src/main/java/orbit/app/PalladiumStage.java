package orbit.app;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import orbit.bot.level.PalladiumKit;
import orbit.bot.run.BotRunner;
import orbit.world.WorldSnapshot;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The palladium stage of the leveling plan (docs/LEVELING.md «План v2», ПД6–ПД7, the user's choices 2026-11-26), per account:
 * once everything is ready (the Nostromo kitted out, a Vengeance owned, the resource collector on the Nostromo's P.E.T.,
 * the uridium kept for its fuel) the main ship flies to 5-2's station, there the bot switches to the Vengeance (it stays
 * parked at the trader: the ships of a switch at a station appear there) and then to the Nostromo, the pair is written to
 * the account's settings (collect — the Nostromo, sell — the Vengeance) and the read-only «Leveling Palladium» config runs
 * till the stage's extra energy is collected. The phase and the count are kept in {@code local/palladium-stage-<account>.json}.
 */
public final class PalladiumStage {

    private static final Logger LOG = LoggerFactory.getLogger("orbit.level");

    /** NONE — not begun; TRAVEL — to 5-2's station; SWITCH_SELL/SWITCH_COLLECT — the two ship switches; RUN; DONE. */
    public enum Phase { NONE, TRAVEL, SWITCH_SELL, SWITCH_COLLECT, RUN, DONE }

    /** The stage's state: the ships' hangars, the extra energy counted, the session's last total seen. */
    record State(Phase phase, int collect, int sell, String sellShip, double energy, double seen) {
    }

    private static final Map<String, State> STATES = new ConcurrentHashMap<>();
    private static final Map<String, Thread> WATCHERS = new ConcurrentHashMap<>();
    /** A travel order's renewal (the runner drops an order after 3 min). */
    static final long ORDER_EVERY_MS = 150_000;
    static final long TICK_MS = 3_000;

    private PalladiumStage() {
    }

    static Path file(String account) {
        return Path.of("local", "palladium-stage-" + account.replaceAll("[^\\w.-]", "_") + ".json");
    }

    static synchronized State state(String account) {
        return STATES.computeIfAbsent(account, a -> {
            try {
                Path f = file(a);
                if (Files.exists(f)) {
                    var n = orbit.config.Json.MAPPER.readTree(f.toFile());
                    return new State(Phase.valueOf(n.path("phase").asText("NONE")), n.path("collect").asInt(),
                            n.path("sell").asInt(), n.path("sellShip").asText(""), n.path("energy").asDouble(),
                            n.path("seen").asDouble());
                }
            } catch (IOException | RuntimeException e) {
                LOG.warn("палладий: состояние этапа не прочитано ({}) — с начала", e.getMessage());
            }
            return new State(Phase.NONE, 0, 0, "", 0, 0);
        });
    }

    private static synchronized void save(String account, State s) {
        STATES.put(account, s);
        try {
            ObjectNode n = orbit.config.Json.MAPPER.createObjectNode();
            n.put("phase", s.phase().name());
            n.put("collect", s.collect());
            n.put("sell", s.sell());
            n.put("sellShip", s.sellShip());
            n.put("energy", s.energy());
            n.put("seen", s.seen());
            Files.createDirectories(file(account).toAbsolutePath().getParent());
            orbit.config.Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file(account).toFile(), n);
        } catch (IOException e) {
            LOG.warn("палладий: состояние этапа не сохранено ({})", e.getMessage());
        }
    }

    public static Phase phase(String account) {
        return state(account).phase();
    }

    /** The stage has the account now: no ship change to the «best» ship, no config chosen by the strategist. */
    public static boolean holds(String account) {
        Phase p = phase(account);
        return p != Phase.NONE && p != Phase.DONE;
    }

    /**
     * Begins the stage: the pair written to the account's settings, the travel to 5-2's station started.
     */
    static void start(String account, int collect, int sell, String sellShip, WorldFeed feed, IntConsumer requestShip,
                      Consumer<String> switchConfig) {
        if (holds(account) || phase(account) == Phase.DONE) {
            return;
        }
        LOG.info("палладий: всё готово — этап начинается: на основном корабле на станцию 5-2, там ССД Vengeance (ангар {}),"
                + " потом ССД Nostromo (ангар {})", sell, collect);
        writePair(account, collect, sell);
        save(account, new State(Phase.TRAVEL, collect, sell, sellShip, 0, 0));
        resume(account, feed, requestShip, switchConfig);
    }

    /** The collect and sell hangars in the account's settings: the palladium module and every login read them there. */
    static void writePair(String account, int collect, int sell) {
        try {
            var file = orbit.config.AccountSettings.DEFAULT_FILE;
            var all = orbit.config.AccountSettings.load(file);
            var entry = all.account(account);
            all.with(account, entry.withPalladium(new orbit.config.AccountSettings.PalladiumShips(collect, sell))).save(file);
        } catch (IOException | RuntimeException e) {
            LOG.warn("палладий: пару ангаров в настройки аккаунта не записать ({})", e.getMessage());
        }
    }

    /** The watcher of the travel and the switches (again after a restart of the program, from the saved phase). */
    static void resume(String account, WorldFeed feed, IntConsumer requestShip, Consumer<String> switchConfig) {
        Phase p = phase(account);
        if (p != Phase.TRAVEL && p != Phase.SWITCH_SELL && p != Phase.SWITCH_COLLECT) {
            return;
        }
        Thread running = WATCHERS.get(account);
        if (running != null && running.isAlive()) {
            return;
        }
        Map<String, String> ctx = org.slf4j.MDC.getCopyOfContextMap();
        Thread t = Thread.ofPlatform().daemon().name("palladium-" + account).start(() -> {
            if (ctx != null) {
                org.slf4j.MDC.setContextMap(ctx);
            }
            watch(account, feed, requestShip, switchConfig);
        });
        WATCHERS.put(account, t);
    }

    private static void watch(String account, WorldFeed feed, IntConsumer requestShip, Consumer<String> switchConfig) {
        PalladiumKit.Kit kit = PalladiumKit.bundled();
        long orderAt = 0;
        long phaseAt = System.currentTimeMillis();
        Phase was = null;
        try {
            while (true) {
                State s = state(account);
                if (s.phase() != was) {
                    was = s.phase();
                    phaseAt = System.currentTimeMillis();
                    orderAt = 0;
                }
                if (s.phase() != Phase.TRAVEL && s.phase() != Phase.SWITCH_SELL && s.phase() != Phase.SWITCH_COLLECT) {
                    return;
                }
                Thread.sleep(TICK_MS);
                BotRunner runner = feed.runner();
                WorldSnapshot w = feed.snapshot();
                if (runner == null || w == null || w.hero() == null || w.hero().id() == 0) {
                    continue; // logging in, or between the logout and the login of a switch
                }
                WorldSnapshot.Hero h = w.hero();
                long now = System.currentTimeMillis();
                switch (s.phase()) {
                    case TRAVEL -> {
                        // paused on the way: the module would take the ship back between two orders (the orders, the
                        // flight from enemies and the repair go on — BotRunner runs them before the pause)
                        if (!runner.paused()) {
                            runner.setPaused(true);
                        }
                        if (h.mapId() != kit.sellMap()) {
                            if (now - orderAt > ORDER_EVERY_MS) {
                                orderAt = now;
                                LOG.info("палладий: лечу на 5-2 (сейчас карта {})", h.mapId());
                                runner.order(BotRunner.Order.jump(kit.sellMap()));
                            }
                        } else if (h.beacon() != null && h.beacon().inStation()) {
                            LOG.info("палладий: в станции 5-2 — пауза, ССД на Vengeance (ангар {})", s.sell());
                            runner.setPaused(true);
                            runner.requestShipHere(s.sell());
                            save(account, new State(Phase.SWITCH_SELL, s.collect(), s.sell(), s.sellShip(), s.energy(), s.seen()));
                        } else {
                            var base = orbit.bot.run.SafePlaces.bases(w).stream()
                                    .min(java.util.Comparator.comparingDouble(b -> b.distance(h))).orElse(null);
                            if (base != null && now - orderAt > 30_000) {
                                orderAt = now;
                                LOG.info("палладий: на 5-2 — к станции {}/{}", base.x() / 100, base.y() / 100);
                                runner.order(BotRunner.Order.move(base.x(), base.y()));
                            }
                        }
                    }
                    case SWITCH_SELL -> {
                        if (h.shipType().equals(s.sellShip())) {
                            if (h.mapId() != kit.sellMap()) {
                                LOG.warn("палладий: Vengeance появился не на 5-2 (карта {}) — пусть так, модуль палладия"
                                        + " довезёт его к торговцу сам", h.mapId());
                            }
                            LOG.info("палладий: на Vengeance — ССД на Nostromo (ангар {})", s.collect());
                            runner.setPaused(true);
                            runner.requestShipHere(s.collect());
                            save(account, new State(Phase.SWITCH_COLLECT, s.collect(), s.sell(), s.sellShip(), s.energy(), s.seen()));
                        } else if (now - phaseAt > 20 * 60_000L) {
                            LOG.warn("палладий: смена на Vengeance не вышла за 20 мин — прошу снова");
                            phaseAt = now;
                            runner.requestShipHere(s.sell());
                        }
                    }
                    case SWITCH_COLLECT -> {
                        if (h.shipType().equals(kit.ship())) {
                            LOG.info("палладий: на Nostromo — конфиг «{}», сбор палладия", kit.config());
                            switchConfig.accept(kit.config());
                            runner.setPaused(false);
                            save(account, new State(Phase.RUN, s.collect(), s.sell(), s.sellShip(), s.energy(), s.seen()));
                        } else if (now - phaseAt > 20 * 60_000L) {
                            LOG.warn("палладий: смена на Nostromo не вышла за 20 мин — прошу снова");
                            phaseAt = now;
                            runner.requestShipHere(s.collect());
                        }
                    }
                    default -> {
                        return;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            LOG.warn("палладий: наблюдение за переездом упало ({}) — продолжу со следующего обхода", e.toString());
        }
    }

    /**
     * The stage's count, each round in RUN: the extra energy the session got since the last look added up (15 palladium
     * = 1 at 5-2's trader); at the target the stage is over (later it is to be split into rounds: Zeta on Sundays).
     */
    static void count(String account, WorldFeed feed) {
        State s = state(account);
        if (s.phase() != Phase.RUN) {
            return;
        }
        WorldSnapshot w = feed.snapshot();
        double total = w == null || w.rewards() == null ? 0 : w.rewards().items().getOrDefault("extra_energy", 0.0);
        double gained = total >= s.seen() ? total - s.seen() : total; // a new world (restart) counts from 0
        double energy = s.energy() + gained;
        long target = PalladiumKit.bundled().energyTarget();
        boolean done = target > 0 && energy >= target;
        save(account, new State(done ? Phase.DONE : Phase.RUN, s.collect(), s.sell(), s.sellShip(), energy, total));
        LOG.info("палладий: доп. энергии за этап {} из {} (≈ {} палладия){}", Math.round(energy), target,
                Math.round(energy * 15), done ? " — этап окончен" : "");
    }
}
