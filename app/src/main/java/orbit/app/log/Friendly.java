package orbit.app.log;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The window's journal in plain words (the user, 2026-11-16: «краткими, информативными, юзер френдли, без спама, но
 * добычи пишутся каждая отдельно… под капотом записывать можем полные»): each event one short line, ships and quests by
 * name, big numbers shortened, a sign in front (▶ start, ■ stop, ✖ death, ⚠ problem, ✔ solved). The full lines stay in
 * the files and on «Всё». Keeps a little state per account: a death and its repair become one line, a lost link and its
 * return one pair with the time between, a start one line.
 */
public final class Friendly {

    /**
     * A journal line: its text, a problem or not, whether it may fold with the same line (×N); with {@code sum} set,
     * lines of that kind within a while add up in one row ({@code body} the amounts, {@code head} the row's words).
     */
    public record Line(String text, boolean problem, boolean folds, String sum, String head, String body,
                       String replace, boolean live) {
        Line(String text, boolean problem, boolean folds) {
            this(text, problem, folds, null, null, null, null, false);
        }

        Line(String text, boolean problem, boolean folds, String sum, String head, String body) {
            this(text, problem, folds, sum, head, body, null, false);
        }
    }

    /** A row that keeps one place and changes its words (a countdown, the wave, the parts): {@code key} names it. */
    static Out replace(String key, String text, boolean live) {
        return new Out(new Line(text, false, false, null, null, null, key, live), null);
    }

    /** What to do with a line: add {@code add} (may be null), or put {@code amend} at the end of the last death row. */
    public record Out(Line add, String amend) {
        static final Out NONE = new Out(null, null);

        static Out add(String text, boolean problem) {
            return new Out(new Line(text, problem, true), null);
        }

        static Out once(String text) {
            return new Out(new Line(text, false, false), null);
        }
    }

    private static final class State {
        long startAt;
        boolean starting;
        String stopReason;
        long lostAt;
        boolean planned;
        long cubeKilledAt = Long.MIN_VALUE / 2;
        String target;
        String gate;
        int visit;
        int stage;
        long logoutAt;
        String killed;
        long killedAt = Long.MIN_VALUE / 2;
    }

    private final Map<String, State> states = new HashMap<>();
    private final BiFunction<String, Integer, String> shipName;

    /** @param shipName an account's hangar id → its ship's name, {@code null} when not known */
    public Friendly(BiFunction<String, Integer, String> shipName) {
        this.shipName = shipName;
    }

    private static Pattern p(String regex) {
        return Pattern.compile(regex);
    }

    private static final Pattern TARGET = p("^Цель: (.+?) в \\d");
    private static final Pattern FIGHT_WON = p("^Бой с (.+?): \\d+ с, [^;]*убит;");
    private static final Pattern START = p("^Конфиг «(.+)»: модуль (\\w+), карта (.+)$");
    private static final Pattern DEATH = p("^Корабль уничтожен \\((.+)\\) — смертей (\\d+)$");
    private static final Pattern DEATH_PLAIN = p("^Корабль уничтожен — смертей (\\d+)$");
    private static final Pattern REPAIR = p("^Ремонт: (.+?) — (.+)$");
    private static final Pattern QUEST = p("^Квест (\\d+)(?: «([^»]*)»)? (.+)$");
    private static final Pattern JUMP = p("^Прыжок на карту (.+)$");
    private static final Pattern TO_WORK = p("^Лечу на рабочую карту (.+)$");
    private static final Pattern CONFIG_SWITCH = p("^Конфиг сменён: «(.+)» → «(.+)»$");
    private static final Pattern CONFIG_SAVED = p("^Конфиг «(.+)» обновлён$");
    private static final Pattern SHIP_CHANGE = p("^Меняю корабль: ангар (\\d+) → (\\d+)(?: \\((.+)\\))?$");
    private static final Pattern VISIT_GO = p("на корабль для Скайлаба \\(ангар (\\d+)\\)");
    private static final Pattern VISIT_SUM = p("^Обслуживание: итог — за визит: рейсов (\\d+), (.+)$");
    private static final Pattern VISIT_BACK = p("^Обслуживание: снова на своём корабле \\(ангар (\\d+)\\)$");
    private static final Pattern VISIT_RESUME = p("^Обслуживание: вошёл на корабле для Скайлаба .* обратно на (\\d+)$");
    private static final Pattern ENRICH = p("^Обогащение: (\\S+) ← (\\S+) (\\d+) \\(было (\\d+)\\)$");
    private static final Pattern LOOT_PART = p("^\\+([\\d\\s\\u00a0.,]*\\d)\\s+(.+)$");
    private static final Pattern NUM = p("[\\d\\u00a0 ]*\\d");

    /** Lines that never reach the journal: the routine and the machinery. */
    private static final Pattern HIDDEN = p(String.join("|",
            "^Аккаунт «.+» (запущен|остановлен)$", "^Модуль:", "^Бот завершил работу", "^Снова в игре",
            "^Корабль сменён на сайте", "^Смена корабля — сессия продолжается", "^Обрыв связи",
            "^Запомнил обидчика", "^Формация .*(не ответил|называет её)", "^Сервер поправил позицию",
            "^Обслуживание: (рейс \\d|всё обогащено|привезённое|.* ← )",
            "^Обогащение: .* в трюме нет", "^Сессии завершены", "^Корабль уничтожен$", "\\. Варианты: ",
            "^Состояние:", "жду выбора ремонта", "^Диагностика", "^Нет пути к", "сервер не ответил за"));

    /** The journal's line(s) for a log line, or {@link Out#NONE}. */
    public synchronized Out of(String account, boolean warn, String logger, String message, long time) {
        String m = message == null ? "" : message.strip();
        State st = states.computeIfAbsent(account == null ? "" : account, k -> new State());
        // the state first: these change it whether they are shown or not
        if (m.matches("^Аккаунт «.+» запущен.*")) {
            st.starting = true;
            st.startAt = time;
            st.stopReason = null;
            return Out.NONE;
        }
        if (m.startsWith("Остановка: ")) {
            String r = m.substring("Остановка: ".length()).replaceFirst(" — сначала заканчиваю бой$", "");
            if (!r.startsWith("смена корабля")) {
                st.stopReason = r;
            }
            return Out.NONE;
        }
        if (m.startsWith("Плановый перевход")) {
            st.planned = true;
            return Out.NONE;
        }
        if (m.startsWith("Связь с игрой потеряна")) {
            st.lostAt = time;
            return st.planned ? Out.NONE : new Out(new Line("⚠ Нет связи — переподключаюсь", true, false), null);
        }
        if (m.startsWith("Связь восстановлена")) {
            boolean planned = st.planned;
            st.planned = false;
            long gone = st.lostAt == 0 ? 0 : time - st.lostAt;
            st.lostAt = 0;
            return planned ? Out.NONE : Out.once("✔ Связь восстановлена" + (gone >= 60_000
                    ? " (без связи " + duration(gone) + ")" : ""));
        }
        if (logger.endsWith("Attacker")) {
            Matcher t = TARGET.matcher(m);
            if (t.find()) {
                st.target = t.group(1);
            } else if (m.startsWith("Цель уничтожена") && st.target != null) {
                st.killed = st.target;
                st.killedAt = time;
            }
        }
        if (logger.endsWith("FightLog")) {
            Matcher f = FIGHT_WON.matcher(m);
            if (f.find()) {
                st.killed = f.group(1);
                st.killedAt = time;
            }
            return Out.NONE;
        }
        if (logger.endsWith("CubikonModule") && m.matches("^Cubikon у .* уничтожен.*")) {
            st.cubeKilledAt = time;
            return Out.NONE;
        }
        Out g = gate(st, m, time);
        if (g != null) {
            return g;
        }
        if (logger.endsWith("BotRunner") && m.equals("Выход из игры")) {
            st.logoutAt = time;
            return replace("logout", "⏱ Выход из игры", true);
        }
        if (m.equals("Сервер завершил сессию (выход)") && st.logoutAt != 0) {
            long took = time - st.logoutAt;
            st.logoutAt = 0;
            return replace("logout", "Вышел из игры за " + Math.max(1, Math.round(took / 1000.0)) + " с", false);
        }
        if (HIDDEN.matcher(m).find()) {
            return Out.NONE;
        }
        Matcher x;
        if ((x = START.matcher(m)).matches()) {
            if (!st.starting) {
                return Out.NONE; // a new login of the same session (a ship change, a reconnect)
            }
            st.starting = false;
            return Out.add("▶ Запущен: " + x.group(1) + " — " + module(x.group(2)) + " на " + x.group(3), false);
        }
        if (m.startsWith("Итог сессии:")) {
            String reason = st.stopReason == null ? "" : " (" + st.stopReason + ")";
            String took = st.startAt > 0 ? " За " + duration(time - st.startAt) + ":" : "";
            st.startAt = 0;
            st.stopReason = null;
            return Out.add("■ Остановлен" + reason + "." + took + " " + totals(m.substring("Итог сессии:".length())), false);
        }
        if (m.startsWith("Сводка ")) {
            return Out.add(hour(m), false);
        }
        if (m.startsWith("Добыча:")) {
            boolean cube = time - st.cubeKilledAt < 8_000;
            boolean npc = !cube && st.killed != null && time - st.killedAt < 8_000;
            String head = cube ? "Куб убит: " : npc ? bare(st.killed) + " убит: " : "Добыча: ";
            st.cubeKilledAt = Long.MIN_VALUE / 2;
            st.killed = null;
            String body = m.substring("Добыча:".length());
            if (!cube && !npc && body.matches(".*[−-]\\s*\\d.*") && !body.contains("+")) {
                // spent (spins, lives, repairs): one row adding up
                String spent = body.replace("−", "+").replace("-", "+");
                return new Out(new Line("Потрачено: " + loot(spent).replace("+", ""), false, true, "spent", "Потрачено: ",
                        spent), null);
            }
            if (!cube && !npc && !body.matches(".*(уридия|кредитов|опыта|чести).*")) {
                // a box's ammunition and ore: one row adding up, not a line per box
                return new Out(new Line("Ящики: " + loot(body), false, true, "boxes", "Ящики: ", body), null);
            }
            return Out.once(head + loot(body));
        }
        if ((x = DEATH.matcher(m)).matches()) {
            String who = x.group(1);
            return Out.add("✖ Убит " + (npc(who) ? "NPC " + bare(who) : "игроком " + who) + " (смерть " + x.group(2) + ")",
                    true);
        }
        if ((x = DEATH_PLAIN.matcher(m)).matches()) {
            return Out.add("✖ Корабль уничтожен (смерть " + x.group(1) + ")", true);
        }
        if ((x = REPAIR.matcher(m)).matches()) {
            String price = x.group(2).equals("бесплатно") ? "бесплатно" : "за " + x.group(2);
            return new Out(null, " — ремонт " + x.group(1) + " " + price);
        }
        if ((x = QUEST.matcher(m)).matches()) {
            String rest = x.group(3);
            String title = x.group(2) == null ? "" : " «" + x.group(2) + "»";
            if (rest.startsWith("выполнен")) {
                return Out.add("✔ Квест выполнен" + title + rest.substring("выполнен".length()), false);
            }
            return Out.add("Квест" + title + " " + rest, warn);
        }
        if ((x = JUMP.matcher(m)).matches()) {
            return Out.add("→ " + x.group(1), false);
        }
        if ((x = TO_WORK.matcher(m)).matches()) {
            return Out.add("Лечу на " + x.group(1), false);
        }
        if ((x = CONFIG_SWITCH.matcher(m)).matches()) {
            return Out.add("Конфиг: «" + x.group(2) + "»", false);
        }
        if ((x = CONFIG_SAVED.matcher(m)).matches()) {
            return Out.add("Конфиг «" + x.group(1) + "» изменён", false);
        }
        if ((x = SHIP_CHANGE.matcher(m)).matches()) {
            String why = x.group(3) == null ? "" : x.group(3);
            if (why.startsWith("обслуживание")) {
                return Out.NONE; // the Skylab's own lines tell it
            }
            boolean machinery = why.isEmpty() || why.startsWith("выход из игры");
            return Out.add("Пересаживаюсь на " + ship(account, x.group(2)) + (machinery ? "" : " (" + why + ")"), false);
        }
        if (m.startsWith("Обслуживание: ")) {
            if ((x = VISIT_SUM.matcher(m)).matches()) {
                return Out.add("Скайлаб: привёз " + visit(x.group(2), Integer.parseInt(x.group(1))), false);
            }
            if ((x = VISIT_BACK.matcher(m)).matches()) {
                return Out.add("Скайлаб: вернулся на " + ship(account, x.group(1)), false);
            }
            if ((x = VISIT_RESUME.matcher(m)).matches()) {
                return Out.add("Скайлаб: визит был прерван — возвращаюсь на " + ship(account, x.group(1)), false);
            }
            if ((x = VISIT_GO.matcher(m)).find()) {
                return Out.add("Скайлаб: пересаживаюсь на " + ship(account, x.group(1)) + " за рудой", false);
            }
            String rest = m.substring("Обслуживание: ".length());
            return warn ? Out.add("⚠ Скайлаб: " + rest, true) : Out.NONE;
        }
        if (m.startsWith("Трюм полон, а продавать нечего")) {
            return Out.add("Трюм полон, продавать нечего — груз больше не беру", false);
        }
        if ((x = ENRICH.matcher(m)).matches()) {
            return Out.add("Обогащение: " + x.group(1) + " ← " + number(Long.parseLong(x.group(3))) + " " + x.group(2),
                    false);
        }
        if (!LogRules.of(logger, warn, m).important()) {
            return Out.NONE;
        }
        return Out.add(warn ? "⚠ " + m : m, warn);
    }

    private static final Pattern G_IN = p("^Во вратах (\\S+)[^(]*\\(.+?\\)(?:, жизней (\\d+))?$");
    private static final Pattern G_PARTS = p("^Врата (\\S+)[^:]*: спины по \\d+, частей (\\d+) из (\\d+)$");
    private static final Pattern G_SET = p("^Врата (\\S+)[^:]*: все части есть");
    private static final Pattern G_LAST = p("^Врата (\\S+)[^:]*: последний этап");
    private static final Pattern G_LIFE = p("^Врата (\\S+)[^:]*: жизней \\d+ \\(держу \\d+\\) — покупаю жизнь за (\\d+) уридия");
    private static final Pattern G_DONE = p("^Врата (\\S+)[^ ]* ?\\S* пройдены за (\\d+) мин$");
    private static final Pattern G_OUT = p("^Из врат (\\S+)[^,—]* вышел(?:, жизней (\\d+) — вернусь| — пройдены)?$");
    private static final Pattern S_COUNT = p("^Сервер: «-=-\\s*(\\d+)\\s*-=-»$");
    private static final Pattern S_WAVE = p("(?i)^Сервер: «wave\\s+(\\d+)\\.?»$");
    private static final Pattern S_LIVES = p("(?i)^Сервер: «(\\d+)\\s+lives?\\s+left\\.?»$");

    /** A galaxy gate's lines: parts, set up, in, the countdown, the wave, lives, done, out; {@code null} for others. */
    private Out gate(State st, String m, long time) {
        Matcher x;
        if ((x = G_PARTS.matcher(m)).matches()) {
            return replace("parts|" + x.group(1), "Врата " + x.group(1) + ": части " + x.group(2) + " из " + x.group(3),
                    false);
        }
        if (m.matches("^Врата \\S+[^:]*: ставлю$")) {
            return Out.NONE;
        }
        if ((x = G_SET.matcher(m)).find()) {
            return Out.add("Врата " + x.group(1) + ": собраны и поставлены", false);
        }
        if ((x = G_IN.matcher(m)).matches()) {
            st.gate = x.group(1);
            if (x.group(2) == null) {
                return Out.NONE; // in again after a new login: nothing new
            }
            st.visit++;
            return Out.add("Вошёл во Врата " + x.group(1) + " (жизней " + x.group(2) + ")", false);
        }
        if ((x = G_LAST.matcher(m)).find()) {
            return Out.add("Врата " + x.group(1) + ": последний этап", false);
        }
        if ((x = G_LIFE.matcher(m)).find()) {
            return Out.add("Врата " + x.group(1) + ": покупаю жизнь за " + number(Long.parseLong(x.group(2))) + " уридия",
                    false);
        }
        if ((x = G_DONE.matcher(m)).matches()) {
            return Out.add("✔ Врата " + x.group(1) + " пройдены за " + x.group(2) + " мин", false);
        }
        if ((x = G_OUT.matcher(m)).matches()) {
            st.gate = null;
            if (m.endsWith("— пройдены")) {
                return Out.NONE;
            }
            return Out.add("Вышел из Врат " + x.group(1) + (x.group(2) == null ? "" : " (жизней " + x.group(2)
                    + ") — вернусь"), false);
        }
        String gate = st.gate == null ? "" : " " + st.gate;
        if ((x = S_COUNT.matcher(m)).matches()) {
            int n = Integer.parseInt(x.group(1));
            String key = "count|" + st.stage;
            if (n == 0) {
                st.stage++;
                return replace(key, "⏱ Врата" + gate + ": волна пошла", false);
            }
            return replace(key, "⏱ Врата" + gate + ": волна через " + n + " с", false);
        }
        if ((x = S_WAVE.matcher(m)).matches()) {
            String of = GATE_WAVES.getOrDefault(st.gate == null ? "" : st.gate, "");
            return replace("wave|" + st.visit, "Врата" + gate + ": волна " + x.group(1) + of, false);
        }
        if ((x = S_LIVES.matcher(m)).matches()) {
            return Out.add("Врата" + gate + ": осталось жизней " + x.group(1), false);
        }
        if (m.matches("(?i)^Сервер: «gate\\s+completed\\.?»$")) {
            return Out.NONE; // «пройдены за N мин» tells it
        }
        return null;
    }

    /** Waves of a gate by its sign (the client's numbers, docs/GALAXY-GATES.md). */
    private static final Map<String, String> GATE_WAVES = Map.of("α", " из 40", "β", " из 40", "γ", " из 40",
            "δ", " из 29", "ε", " из 30", "ζ", " из 46", "κ", " из 29", "λ", " из 21");

    // ---------------------------------------------------------------------------------------------- the wording

    private String ship(String account, String id) {
        try {
            String n = shipName == null ? null : shipName.apply(account, Integer.parseInt(id));
            return n == null || n.isBlank() ? "корабль " + id : n;
        } catch (RuntimeException e) {
            return "корабль " + id;
        }
    }

    static String module(String m) {
        return switch (m) {
            case "CUBIKON" -> "кубы";
            case "PALLADIUM" -> "палладий";
            case "GALAXY_GATE" -> "Врата";
            case "BEHEMOTH" -> "бегемот";
            case "KILL_COLLECT" -> "NPC и ящики";
            case "QUESTS" -> "квесты";
            default -> m.toLowerCase(Locale.ROOT);
        };
    }

    /** An NPC's decorated name: «-=[ Kristallin ]=-», «..::{ Boss … }::..», «\\ Abide I //». */
    static boolean npc(String name) {
        return name.matches("^(-=\\[|\\.\\.::\\{|\\\\\\\\|<=<|\\[).*");
    }

    private static final Pattern DECOR = p("^(?:-=\\[\\s*(.+?)\\s*]=-|\\.\\.::\\{\\s*(.+?)\\s*}::\\.\\.|\\\\\\\\\\s*(.+?)"
            + "\\s*//|<=<\\s*(.+?)\\s*>=>)");

    /** «-=[ Sibelonit ]=- α29» → «Sibelonit», «..::{ Boss Kristallin }::..» → «Boss Kristallin». */
    static String bare(String name) {
        Matcher m = DECOR.matcher(name.strip());
        if (m.find()) {
            for (int g = 1; g <= m.groupCount(); g++) {
                if (m.group(g) != null) {
                    return m.group(g);
                }
            }
        }
        return name.strip();
    }

    /** Two bodies of amounts added up («+20 MCB-50 · +4 ACM-01» and «+40 MCB-50») — for the rows that sum. */
    public static String add(String a, String b) {
        return a + " · " + b;
    }

    /** 1 024; 40 380; 614 тыс.; 1,6 млн. */
    static String number(double v) {
        double a = Math.abs(v);
        if (a >= 1_000_000) {
            return String.format(Locale.ROOT, "%.1f", v / 1_000_000).replace('.', ',').replace(",0", "") + " млн";
        }
        if (a >= 100_000) {
            return Math.round(v / 1_000) + " тыс.";
        }
        return String.format(Locale.ROOT, "%,.0f", v).replace(',', ' ');
    }

    private static double parse(String digits) {
        return Double.parseDouble(digits.replaceAll("[\\s\\u00a0]", "").replace(',', '.'));
    }

    /** «+614 400 опыта · +1 024 уридия» → «+1 024 уридия, +614 тыс. опыта» (uridium, credits, experience, honour first). */
    static String loot(String body) {
        Map<String, Double> sum = new LinkedHashMap<>();
        List<String> other = new ArrayList<>();
        for (String part : body.split("·")) {
            String t = part.strip();
            if (t.isEmpty()) {
                continue;
            }
            Matcher m = LOOT_PART.matcher(t);
            if (m.matches()) {
                try {
                    sum.merge(m.group(2).strip(), parse(m.group(1)), Double::sum);
                    continue;
                } catch (NumberFormatException ignored) {
                    // as words
                }
            }
            other.add(t);
        }
        List<String> out = new ArrayList<>();
        for (String first : List.of("уридия", "кредитов", "опыта", "чести")) {
            Double v = sum.remove(first);
            if (v != null) {
                out.add("+" + number(v) + " " + first);
            }
        }
        sum.forEach((k, v) -> out.add("+" + number(v) + " " + k));
        out.addAll(other);
        return String.join(", ", out);
    }

    private static String grab(String text, String regex) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1).strip() : null;
    }

    /** The session's results in a few words: uridium, credits, cubes or NPC, palladium, deaths. */
    static String totals(String s) {
        List<String> out = new ArrayList<>();
        add(out, grab(s, "Уридий: ([\\d\\u00a0 ]*\\d)"), " уридия");
        add(out, grab(s, "Кредиты: ([\\d\\u00a0 ]*\\d)"), " кредитов");
        String cubes = grab(s, "добито (\\d+)");
        if (cubes != null && !cubes.equals("0")) {
            out.add(cubes + " " + plural(Integer.parseInt(cubes), "куб", "куба", "кубов"));
        } else {
            String npc = grab(s, "Убито NPC (\\d+)");
            if (npc != null && !npc.equals("0")) {
                out.add("NPC " + npc);
            }
        }
        add(out, grab(s, "палладий ([\\d\\u00a0 ]*\\d)"), " палладия");
        String deaths = grab(s, "смертей (\\d+)");
        out.add("смертей " + (deaths == null ? "0" : deaths));
        return String.join(", ", out);
    }

    /** «Сводка 11:34–12:00 · в игре все 26 мин · уридий +6 523 · …» → «Час 11:34–12:00: 6 523 уридия · …». */
    static String hour(String s) {
        String span = grab(s, "^Сводка (\\S+)");
        List<String> out = new ArrayList<>();
        String inGame = grab(s, "· (в игре \\d+ из \\d+ мин|не в игре) ·");
        if (inGame != null) {
            out.add(inGame);
        }
        add(out, grab(s, "уридий \\+([\\d\\u00a0 ]*\\d)"), " уридия");
        add(out, grab(s, "кредиты \\+([\\d\\u00a0 ]*\\d)"), " кредитов");
        String cubes = grab(s, "кубов (\\d+)");
        if (cubes != null) {
            out.add(cubes + " " + plural(Integer.parseInt(cubes), "куб", "куба", "кубов"));
        } else {
            String npc = grab(s, "· NPC (\\d+)");
            if (npc != null) {
                out.add("NPC " + npc);
            }
        }
        String pal = grab(s, "палладий \\+?([\\d\\u00a0 ]*\\d)");
        add(out, pal, " палладия");
        String deaths = grab(s, "смертей (\\d+)");
        if (deaths != null) {
            out.add("смертей " + deaths);
        }
        return "Час " + span + ": " + String.join(" · ", out);
    }

    private static void add(List<String> out, String digits, String name) {
        if (digits == null) {
            return;
        }
        try {
            double v = parse(digits);
            if (v != 0) {
                out.add(number(v) + name);
            }
        } catch (NumberFormatException ignored) {
            // not a number
        }
    }

    /** «promerium 14 056; лазеры 49 440 → 190 000» → «14 056 Promerium за 2 рейса, лазеры 49 440 → 190 000». */
    static String visit(String rest, int trips) {
        String[] parts = rest.split("; ");
        List<String> ores = new ArrayList<>();
        List<String> items = new ArrayList<>();
        for (String part : parts) {
            Matcher m = Pattern.compile("^(\\p{L}+) ([\\d\\u00a0 ]*\\d)$").matcher(part.strip());
            if (m.matches()) {
                String ore = m.group(1);
                ores.add(m.group(2) + " " + Character.toUpperCase(ore.charAt(0)) + ore.substring(1));
            } else if (!part.isBlank()) {
                items.add(part.strip());
            }
        }
        String head = String.join(", ", ores);
        return (head.isEmpty() ? "руду" : head) + " за " + trips + " " + plural(trips, "рейс", "рейса", "рейсов")
                + (items.isEmpty() ? "" : ", " + String.join(", ", items));
    }

    static String plural(int n, String one, String few, String many) {
        int a = Math.abs(n) % 100;
        int b = a % 10;
        return a > 10 && a < 20 ? many : b == 1 ? one : b >= 2 && b <= 4 ? few : many;
    }

    static String duration(long ms) {
        long min = Math.max(1, Math.round(ms / 60_000.0));
        return min < 60 ? min + " мин" : min / 60 + " ч" + (min % 60 == 0 ? "" : " " + min % 60 + " мин");
    }
}
