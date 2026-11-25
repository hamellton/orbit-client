#!/usr/bin/env python3
"""The quest reference docs/QUESTS.md: every quest the client names (resource_quest.xml titles), from number 0 up, with
its type, level, tasks, rewards and where to do it; then the NPCs (where the bot saw them, who comes out of whom, what
drops) and the special cases.

What a quest asks comes from two places: the server's own details, which it sends only for a quest offered or taken
(local/quests-seen.json of each machine — exact, they win), and research from the wiki and the forum, kept by hand in
reference/quests/research-ru.json. The NPC notes are reference/quests/npc-notes-ru.json; what drops where comes from the
craft sources (reference/craft/sources-ru.json).

    tools/quest_doc.py [extra quests-seen.json ...]
"""
import json
import os
import re
import sys
from collections import defaultdict

OUT = "docs/QUESTS.md"
SERIES_DIR = "docs/quests"
SERIES = [
    (0, 3000, "Квесты 0–2 999: старая система, обучение, ежедневные"),
    (3000, 105000, "Квесты 3 000–104 999"),
    (105000, 125000, "Миссии 105 000–124 999 (Mission Control, по уровням)"),
    (125000, 500000, "Квесты 125 000–499 999: ежедневные, еженедельные и прочие"),
    (500000, 510000, "События 500 000–509 999"),
    (510000, 700000, "Квесты 510 000–699 999: поздние события и серии"),
    (700000, 10 ** 9, "Season Pass и прочие 700 000+"),
]
CONF = {"high": "", "medium": "", "low": " (не точно)", "none": ""}


def load(path, default=None):
    try:
        return json.load(open(path, encoding="utf-8"))
    except FileNotFoundError:
        return default if default is not None else {}


def plain(npc):
    """«-=[ Streuner ]=-», «..::{ Boss Lordakia }::..» → «Streuner», «Boss Lordakia»."""
    return re.sub(r"^[^A-Za-z0-9]+|[^A-Za-z0-9]+$", "", npc).strip()


NAMES = {}


def cell(s):
    s = re.sub(r"\b(?:resource|currency|ammunition|equipment|ship|drone|booster|pet)_[\w\-]+",
               lambda m: NAMES.get(m.group(0), m.group(0)), s or "")
    return s.replace("|", "\\|").replace("\n", " ").strip()


def link(source):
    url = next((u.strip() for u in (source or "").replace(",", ";").split(";") if u.strip().startswith("http")), None)
    if url:
        return f"[вики/форум]({url})"
    if source == "server":
        return "сервер"
    return cell(source)[:40] if source else ""


def main(*extra):
    ru = {k: v for k, v in load("reference/maps/quest-titles-ru.json").items()}
    en = load("reference/maps/quest-titles-en.json")
    seen = {}
    for path in ("local/quests-seen.json",) + extra:
        for k, v in load(path).items():
            if k.isdigit():
                seen.setdefault(k, v)
    research = load("reference/quests/research-ru.json")
    NAMES.update(load("reference/maps/item-names-ru.json"))
    NAMES.update({"currency_eventprogresspoints": "очки события", "currency_credits": "кредиты",
                  "currency_uridium": "уридий"})
    notes = load("reference/quests/npc-notes-ru.json")
    sources = load("reference/craft/sources-ru.json")
    maps = {m["id"]: m["name"] for m in load("reference/maps/maps.json").get("maps", [])}
    npc_maps = load("reference/maps/npc-maps.json")

    def title(i, d):
        return d.get(i) or d.get(next((k for k in d if k.split("_")[0] == i), ""), "")

    ids = sorted({int(k.split("_")[0]) for k in list(ru) + list(en) if k.split("_")[0].isdigit()}
                 | {int(k) for k in seen} | {int(k) for k in research if k.isdigit()})

    rows = defaultdict(list)
    stats = defaultdict(int)
    for i in ids:
        k = str(i)
        s = seen.get(k, {})
        r = research.get(k, {})
        name = title(k, ru) or title(k, en)
        if r.get("type") == "служебный" or re.search(r"placeholder|\btest\b|\bTBD\b", title(k, en) or "", re.I):
            stats["служебные"] += 1
            continue
        tasks = s.get("conditions", "")
        if not tasks or "условие " in tasks:  # the server's bare codes: the research's words are better
            tasks = r.get("tasks") or tasks
        rewards = s.get("rewards") or r.get("rewards", "")
        level = s.get("minLevel") or r.get("level") or ""
        typ = s.get("type") or r.get("type", "")
        where = r.get("where", "")
        if r.get("event"):
            where = (f"**{r['event']}.** " + where).strip()
        if s:
            src, stats["от сервера"] = "сервер", stats["от сервера"] + 1
        elif r.get("confidence") in ("high", "medium", "low") and (r.get("tasks") or r.get("rewards")):
            src = link(r.get("source")) + CONF[r["confidence"]]
            stats["из вики/форума"] += 1
        else:
            src = "нет данных"
            stats["нет данных"] += 1
        sec = next(t for a, b, t in SERIES if a <= i < b)
        en_t = title(k, en)
        label = cell(name) + (f" <sub>{cell(en_t)}</sub>" if en_t and en_t != name else "")
        rows[sec].append((i, label, typ, str(level or ""), tasks or "—", rewards or "—", where or "—", src))

    # NPCs: where the bot saw them, who they come out of, what drops from them
    seen_on = defaultdict(set)
    for mid, names in npc_maps.items():
        if not mid.isdigit():
            continue
        for n in names:
            seen_on[plain(n)].add(maps.get(int(mid), mid))
    npc_notes = notes.get("npcs", {})

    def note_of(n):
        """The note for an NPC: by its name, else by a note's name it starts with («Attend IX» → «Attend»)."""
        base = n.replace("Boss ", "")
        return npc_notes.get(n) or npc_notes.get(base) or next(
            (v for k, v in npc_notes.items() if base.startswith(k + " ") or base.lower().startswith(k.lower())), {})

    # a note's NPC the bot never saw gets its own row; one it saw under a longer name joins that row
    all_npcs = sorted(set(seen_on) | {k for k in npc_notes if not any(
        x.replace("Boss ", "").lower().startswith(k.lower()) for x in seen_on)}, key=str.lower)

    def drops(npc):
        base = re.sub(r"^(Boss |Uber ?)", "", npc).strip()  # the bot writes «UberKristallin», the sources «Uber Kristallin»
        out = []
        for rid, src in sources.items():
            text = src.get("where", "")
            for m in re.finditer(r"\b" + re.escape(base) + r"\b", text):
                before = re.search(r"([A-Z][a-z]+) $", text[:m.start()])
                # «Chaos Protegit» is not Protegit; «Boss …», «Uber …» are named as such in the NPC list
                after = re.match(r" [A-Z][a-z]+", text[m.end():])  # «Protegit Pandemonium» is an event
                kind = "Boss" if npc.startswith("Boss ") else "Uber" if npc.startswith("Uber") else None
                if (before.group(1) == kind if before else kind is None) and not after:
                    out.append(rid)
                    break
        return out

    names = NAMES
    w = []
    a = w.append
    a("# Квесты: что нужно, награды, где брать")
    a("")
    a("Сгенерировано `tools/quest_doc.py` (`scripts/update-quest-doc.sh`). Список — все квесты, которые называет клиент")
    a("(`resource_quest.xml`), по номеру с 0. Что квест требует, игра присылает только для предложенного или взятого квеста:")
    a("такие данные — «сервер» (точные, из `local/quests-seen.json` всех машин). Остальное — из вики и форума")
    a("(`reference/quests/research-ru.json`, ведётся руками; «не точно» — слабый источник), иначе «нет данных» — заполнится,")
    a("когда бот увидит квест. Правки — в генераторе и в файлах данных, не здесь.")
    a("")
    a(f"Квестов в списке: {sum(len(v) for v in rows.values())} — от сервера {stats['от сервера']}, из вики/форума "
      f"{stats['из вики/форума']}, нет данных {stats['нет данных']}; служебных (заглушки, тесты) не показано: "
      f"{stats['служебные']}.")
    a("")
    a("Как читать: **Ур.** — с какого уровня даёт квестодатель; **Что сделать** — задачи (через «;», «по порядку» — одна")
    a("за другой); **Где и как** — карты, откуда NPC и ресурсы, особые случаи (подробно — разделы ниже). Ресурсы для")
    a("сборки и где их брать — `docs/CRAFT.md`.")
    a("")
    a("## Разделы")
    a("")
    for lo, hi, t in SERIES:
        if rows.get(t):
            a(f"- [{t}](quests/{slug(lo, hi)}.md) — {len(rows[t])}")
    a(f"- [Особые случаи](#{anchor('Особые случаи')})")
    a(f"- [NPC: где живут, из кого появляются, что падает](#{anchor('NPC: где живут, из кого появляются, что падает')})")
    a("")
    os.makedirs(SERIES_DIR, exist_ok=True)
    for lo, hi, t in SERIES:
        if not rows.get(t):
            continue
        f = [f"# {t}", "", "Часть справочника [квестов](../QUESTS.md) (там же — как читать, особые случаи и NPC). "
             "Сгенерировано `tools/quest_doc.py`, правки — не здесь.", ""]
        if t.startswith("Квесты 0–2 999"):
            f += ["Большая часть условий здесь — со старой системы квестов (фан-вики, 2011–2012): многие из них игра "
                  "сейчас не выдаёт; точные — только «сервер».", ""]
        f += ["| № | Название | Тип | Ур. | Что сделать | Награда | Где и как | Откуда данные |",
              "|---|---|---|---|---|---|---|---|"]
        for row in rows[t]:
            f.append("| " + " | ".join([str(row[0])] + [cell(x) if j != 0 else x for j, x in enumerate(row[1:])]) + " |")
        f.append("")
        open(os.path.join(SERIES_DIR, slug(lo, hi) + ".md"), "w", encoding="utf-8").write("\n".join(f))
    a("## Особые случаи")
    a("")
    for c in notes.get("cases", []):
        a(f"**{c['title']}.** {c['text']}" + (f" <sub>({c['source']})</sub>" if c.get("source") else ""))
        a("")
    a("## NPC: где живут, из кого появляются, что падает")
    a("")
    a("«Где видел бот» — карты, на которых бот встречал NPC (`reference/maps/npc-maps.json`); «Что падает» — ресурсы сборки,")
    a("для которых этот NPC назван источником (`docs/CRAFT.md`).")
    a("")
    a("| NPC | Где видел бот | Откуда берётся | Что падает | Примечание |")
    a("|---|---|---|---|---|")
    for n in all_npcs:
        note = note_of(n)
        where = ", ".join(sorted(seen_on.get(n, set()), key=map_order)) or note.get("maps", "") or "—"
        if seen_on.get(n) and note.get("maps"):
            where += "; " + note["maps"]
        d = [names.get(x, x) for x in drops(n)]
        if note.get("drops"):
            d.insert(0, note["drops"])
        a("| " + " | ".join(cell(x) for x in (n, where, note.get("spawn", "свой спавн на карте"), "; ".join(d) or "—",
                                               note.get("source", ""))) + " |")
    a("")
    open(OUT, "w", encoding="utf-8").write("\n".join(w))
    print(f"{sum(len(v) for v in rows.values())} quests ({dict(stats)}), {len(all_npcs)} NPCs → {OUT}")


def slug(lo, hi):
    return f"{lo}-plus" if hi >= 10 ** 9 else f"{lo}-{hi - 1}"


def map_order(name):
    m = re.match(r"(\d+)-(\d+)", name)
    return (int(m.group(1)), int(m.group(2)), name) if m else (99, 0, name)


def anchor(s):
    s = s.lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


if __name__ == "__main__":
    main(*sys.argv[1:])
