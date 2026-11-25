#!/usr/bin/env python3
"""The craft reference docs/CRAFT.md: every Assembly recipe the client knows (its ItemDataProvider.as, decompiled),
what it gives, what one build costs, how long it takes and how often it may be built; then every ingredient with where
it comes from. Names and short effects come from the game's texts (resource_items.xml on the CDN); where an ingredient
comes from is kept by hand in reference/craft/sources-ru.json (the game's texts mostly do not say).

    tools/craft_doc.py [ItemDataProvider.as] [lang] [resource_items.xml]
"""
import glob
import json
import re
import sys
import urllib.request
from collections import defaultdict

CDN = "https://darkorbit-22.bpsecure.com/spacemap/templates/%s/resource_items.xml"
SOURCES = "reference/craft/sources-ru.json"
OUT = "docs/CRAFT.md"

USING = {
    "ALWAYS_USABLE": "",
    "USABLE_ONCE": "один раз",
    "USABLE_ONCE_PER_SCHEDULE": "раз за событие",
    "USABLE_LIMIT_PER_SCHEDULE": "до {n} раз за событие",
}
VISIBLE = {
    "ALWAYS_VISIBLE": "всегда",
    "ON_SCHEDULE": "в событие",
    "ONLY_WHEN_OWNED": "когда есть чертёж",
}

# sections by the first result's loot id, the first match wins
SECTIONS = [
    ("Корабли", lambda i: i.startswith("ship_") and "_design_" not in i),
    ("Дизайны кораблей", lambda i: i.startswith("ship_")),
    ("Дроны и их дизайны", lambda i: i.startswith("drone_")),
    ("Лазеры", lambda i: i.startswith("equipment_weapon_laser")),
    ("Ракетомёты", lambda i: i.startswith("equipment_weapon_rocketlauncher")),
    ("Генераторы", lambda i: i.startswith("equipment_generator")),
    ("Модули апгрейда корабля", lambda i: i.startswith("equipment_shipupgrade")),
    ("Процессоры и экстры", lambda i: i.startswith("equipment_extra")),
    ("Прочее оборудование", lambda i: i.startswith("equipment_")),
    ("Лазерные боеприпасы", lambda i: i.startswith("ammunition_laser")),
    ("Ракеты, мины и другие боеприпасы", lambda i: i.startswith("ammunition_")),
    ("Улучшения предметов (уровни)", lambda i: i.startswith("upgrade_")),
    ("Ресурсы и руды", lambda i: i.startswith("resource_")),
    ("Питомец", lambda i: i.startswith("pet_") or "pet" in i.split("_")[0]),
    ("Бустеры", lambda i: i.startswith("booster")),
    ("Прочее", lambda i: True),
]


def parse_recipes(path):
    """The recipes in the client's ItemDataProvider: a RecipeAttributes object filled line by line up to its
    items["<id>"] = …; parts (BuildPart) go to its result or to a cost level (RecipeCosts)."""
    objs = {}
    recipes = {}
    rec = None
    rvar = None
    for line in open(path, encoding="utf-8"):
        line = line.strip()
        m = re.match(r'(\w+) = new (RecipeAttributes|BuildPart|RecipeCosts)\(\);', line)
        if m:
            var, kind = m.groups()
            if kind == "RecipeAttributes":
                rvar = var
                rec = {"result": [], "costs": [], "targets": []}
                objs[var] = rec
            elif kind == "BuildPart":
                objs[var] = {"item": None, "amount": 0}
            else:
                objs[var] = {"level": 0, "time": 0, "parts": []}
            continue
        if rec is None:
            continue
        m = re.match(r'(\w+)\.(\w+) = (.+);$', line)
        if m:
            var, field, value = m.groups()
            o = objs.get(var)
            if o is None:
                continue
            v = value.strip('"')
            if field == "lootID":
                o["item"] = v
            elif field == "ammount":
                o["amount"] = float(v)
            elif field == "level" and "parts" in o:
                o["level"] = int(float(v))
            elif field == "buildTimeSec":
                o["time"] = int(float(v))
            elif o is rec and field in ("usingStrategy", "visibility", "lootId"):
                rec[field] = v
            elif o is rec and field in ("maxUses", "costScalingFactor"):
                rec[field] = float(v)
            continue
        m = re.match(r'(\w+)\.(result|parts|costs|targets)\.push\((.+)\);$', line)
        if m:
            var, field, arg = m.groups()
            o = objs.get(var)
            if field == "targets" and o is rec:
                rec["targets"].append(arg.strip('"'))
            elif o is not None and arg in objs:
                o[field].append(objs[arg])
            continue
        m = re.match(r'items\["([^"]+)"\] = ', line)
        if m and rec is not None and objs.get(rvar) is rec:
            rid = m.group(1)
            if rec.get("lootId") == rid and rid.startswith(("recipe_", "upgrade_upgrade")):
                recipes[rid] = rec
            rec = None
    return recipes


def texts(lang, local=None):
    if local:  # a copy downloaded before (the CDN is slow now and then)
        text = open(local, encoding="utf-8").read()
    else:
        req = urllib.request.Request(CDN % lang, headers={"User-Agent": "Mozilla/5.0"})
        text = urllib.request.urlopen(req, timeout=300).read().decode("utf-8")
    out = {}
    for key, value in re.findall(r'<item name="items_([^"]+)"><!\[CDATA\[(.*?)\]\]></item>', text, re.S):
        out[key] = " ".join(value.split())
    return out


def num(x):
    x = int(x) if float(x).is_integer() else x
    return f"{x:,}".replace(",", " ") if isinstance(x, int) else str(x)


def clock(sec):
    if sec < 60:
        return f"{sec} с"
    if sec < 3600:
        return f"{sec // 60} мин" + (f" {sec % 60} с" if sec % 60 else "")
    h, m = divmod(sec // 60, 60)
    return f"{h} ч" + (f" {m} мин" if m else "")


def cell(s):
    return s.replace("|", "\\|").replace("\n", " ")


def main(provider=None, lang="ru", local=None):
    provider = provider or sorted(glob.glob("reference/decompiled/*/main/scripts/net/bigpoint/darkorbit/items/"
                                            "ItemDataProvider.as"))[-1]
    build = provider.split("/")[2]
    recipes = {k: v for k, v in parse_recipes(provider).items() if "recipe-test" not in k}
    t = texts(lang, local)
    try:
        sources = json.load(open(SOURCES, encoding="utf-8"))
    except FileNotFoundError:
        sources = {}

    def name(i):
        n = t.get(f"{i}_fullname")
        if n and not n.startswith("{"):
            return n
        if i == "currency_credits":
            return "кредиты"
        if i == "currency_uridium":
            return "уридий"
        return i

    def short(i):
        s = t.get(f"{i}_short", "")
        return "" if s.startswith("{") else s

    makers = defaultdict(list)  # item → recipes that give it
    users = defaultdict(list)  # item → recipes that take it
    for rid, r in recipes.items():
        for p in r["result"]:
            makers[p["item"]].append(rid)
        for c in r["costs"][:1]:
            for p in c["parts"]:
                users[p["item"]].append(rid)

    def gives(r):
        parts = defaultdict(float)
        for p in r["result"]:
            parts[p["item"]] += p["amount"]
        return parts

    def price(c):
        return ", ".join(f"{name(p['item'])} ×{num(p['amount'])}" for p in c["parts"]) or "—"

    def rules(r):
        use = USING.get(r.get("usingStrategy", ""), r.get("usingStrategy", ""))
        use = use.format(n=num(r.get("maxUses", 0)))
        vis = VISIBLE.get(r.get("visibility", ""), r.get("visibility", ""))
        return ", ".join(x for x in (vis, use) if x)

    def title(rid, r):
        g = gives(r)
        items = list(g)
        if r["targets"]:  # an upgrade of the listed items
            return "Улучшение: " + ", ".join(name(x) for x in r["targets"][:4]) + (
                " …" if len(r["targets"]) > 4 else "")
        if len(items) == 1:
            n = g[items[0]]
            return name(items[0]) + (f" ×{num(n)}" if n != 1 else "")
        return name(rid)

    by = defaultdict(list)
    for rid, r in recipes.items():
        first = r["result"][0]["item"] if r["result"] else rid
        sec = next(s for s, f in SECTIONS if f(first))
        by[sec].append((rid, r))

    out = []
    w = out.append
    w("# Сборка (крафт): все рецепты и где брать ресурсы")
    w("")
    w(f"Сгенерировано `tools/craft_doc.py` из клиента {build} (`ItemDataProvider.as`: рецепты, цены, время) и текстов игры")
    w("(`resource_items.xml`: названия и краткие эффекты); где брать ресурсы — `reference/craft/sources-ru.json` (ведётся")
    w("руками). Обновить: `scripts/update-craft-doc.sh` (после декомпиляции нового клиента). Правки — в генераторе и в")
    w("файле источников, не здесь.")
    w("")
    w("Как читать:")
    w("- **Цена** — за одну сборку. У рецептов с уровнями (улучшения предметов) — цена первого уровня и число уровней.")
    w("- **Когда**: «всегда» — рецепт в Сборке постоянно; «в событие» — только пока идёт событие или его расписание;")
    w("  «когда есть чертёж» — виден, если чертёж уже есть. «Один раз», «раз за событие», «до N раз» — сколько можно собрать.")
    w("- Клиент знает и рецепты прошлых событий: «в событие» значит, что сейчас его может и не быть в Сборке.")
    w("- Сколько чего есть у аккаунта и что сейчас доступно, игра присылает при входе (`AssemblyRecipeContentModule`).")
    w("")
    w("## Разделы")
    w("")
    order = [s for s, _ in SECTIONS if by.get(s)]
    for s in order:
        w(f"- [{s}](#{anchor(s)}) — {len(by[s])}")
    w(f"- [Ресурсы: где брать](#{anchor('Ресурсы: где брать')})")
    w("")
    for s in order:
        w(f"## {s}")
        w("")
        w("| Получаешь | Что даёт | Цена | Время | Когда |")
        w("|---|---|---|---|---|")
        rows = []
        for rid, r in by[s]:
            g = list(gives(r))
            eff = short(g[0]) if len(g) == 1 and not r["targets"] else ""
            costs = sorted(r["costs"], key=lambda c: c["level"])
            if not costs:
                continue
            c0 = costs[0]
            p = price(c0)
            if len(costs) > 1:
                p = f"ур. {c0['level']}: {p}; … ур. {costs[-1]['level']}: {price(costs[-1])} (уровней {len(costs)})"
            rows.append((title(rid, r), eff, p, clock(c0["time"]) if c0["time"] else "—", rules(r), rid))
        for row in sorted(rows, key=lambda x: x[0].lower()):
            w("| " + " | ".join(cell(x) for x in (row[0] + f" <sub>`{row[5]}`</sub>",) + row[1:5]) + " |")
        w("")

    w("## Ресурсы: где брать")
    w("")
    w("Каждый ресурс, который идёт в рецепты: где его взять, в скольких рецептах он нужен и собирается ли он сам.")
    w("«Не подтверждено» — источник не найден в надёжных местах, указана лучшая зацепка.")
    w("")
    w("| Ресурс | Где брать | Нужен в рецептах | Собирается |")
    w("|---|---|---|---|")
    for i in sorted(users, key=lambda i: (i.split("_")[0] != "resource", name(i).lower())):
        if not i.startswith(("resource_", "currency_")):
            continue
        src = sources.get(i, {})
        where = src.get("where", "") or "не подтверждено"
        if src.get("event"):
            where = f"**{src['event']}.** " + where
        if src.get("confidence") == "low":
            where += " (не точно)"
        url = next((u.strip() for u in src.get("source", "").split(";") if u.strip().startswith("http")), None)
        if url:
            where += f" <sub>[источник]({url})</sub>"
        made = ", ".join(sorted({name(x) for x in makers.get(i, [])}))[:120]
        w("| " + " | ".join(cell(x) for x in (f"{name(i)} <sub>`{i}`</sub>", where, str(len(users[i])),
                                               made or "—")) + " |")
    w("")
    w("### Предметы в цене, которые сами покупаются или собираются")
    w("")
    w("Корабли, оборудование и боеприпасы, которые тратятся в рецептах (улучшения, сборка более сильного).")
    w("")
    w("| Предмет | Собирается рецептом | Нужен в рецептах |")
    w("|---|---|---|")
    for i in sorted(users, key=lambda i: name(i).lower()):
        if i.startswith(("resource_", "currency_")):
            continue
        made = ", ".join(sorted({name(x) for x in makers.get(i, [])})) or "магазин, награды"
        w("| " + " | ".join(cell(x) for x in (f"{name(i)} <sub>`{i}`</sub>", made, str(len(users[i])))) + " |")
    w("")
    open(OUT, "w", encoding="utf-8").write("\n".join(out))
    print(f"{len(recipes)} recipes, {len(users)} ingredients → {OUT}")


def anchor(s):
    s = s.lower()
    s = re.sub(r"[^\w\- ]", "", s)
    return s.replace(" ", "-")


if __name__ == "__main__":
    main(*sys.argv[1:])
