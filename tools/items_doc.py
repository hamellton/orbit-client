#!/usr/bin/env python3
"""Item reference from a site recon (scripts/site-recon.sh → local/recon/<date>-<account>/) and the game's item texts
(reference/maps/item-names-ru.json): docs/ITEMS.md — every shop tab with price, currency, limit and stats, the hourly
auction's lots, the new account's hangar — and docs/items/<group>.md, every item the game names, marked where it is sold.

    python3 -I tools/items_doc.py <recon dir> [<repo root>]
"""
import collections
import glob
import html
import json
import os
import re
import sys

TABS = [
    ("Ships", "Корабли"), ("Laser", "Лазеры и наборы"), ("Generator", "Генераторы"), ("Drones", "Дроны"),
    ("Ammo", "Боеприпасы"), ("Booster", "Бустеры"), ("PetGear", "Механизмы питомца"), ("PetProtocols", "Протоколы питомца"),
    ("ShipModel", "Модели кораблей"), ("Customization", "Дизайны и украшения"), ("Specials", "Спецпредложения"),
]
# tabs whose descriptions are short stats worth keeping
DESCRIBED = {"Laser", "Generator", "Drones", "Ammo", "Booster", "PetGear", "PetProtocols"}
GROUPS = [
    ("equipment", "Снаряжение: лазеры, генераторы, процессоры, ракетомёты, модули", ("equipment_",)),
    ("ships", "Корабли и их дизайны", ("ship_",)),
    ("drones", "Дроны, дизайны дронов, формации", ("drone_",)),
    ("ammunition", "Боеприпасы: батареи, ракеты, мины", ("ammunition_",)),
    ("resources", "Ресурсы", ("resource_",)),
    ("boosters", "Бустеры", ("booster_",)),
    ("pet", "Питомец: механизмы, протоколы, дизайны", ("pet_",)),
    ("recipes", "Рецепты и чертежи", ("recipe_", "bpk_")),
    ("packages", "Наборы и акции", ("package_", "deal_", "event")),
    ("other", "Прочее", ()),
]
CURRENCY = {"virtual": "кредиты", "real": "уридий"}


def shop(recon):
    dec = json.JSONDecoder()
    out = collections.OrderedDict()
    for tab, _ in TABS:
        files = glob.glob(os.path.join(recon, "*internalDock" + tab + ".html"))
        if not files:
            continue
        s = open(files[0], encoding="utf-8").read()
        m = re.search(r"itemData\s*:\s*", s)
        if not m:
            continue
        data, _ = dec.raw_decode(s[m.end():])
        out[tab] = data
    return out


def text(s):
    s = re.sub(r"<script.*?</script>", " ", s or "", flags=re.S)
    s = html.unescape(re.sub(r"<[^>]+>", " ", s))
    return " ".join(s.split())


def stats(desc):
    """Only the figures of a shop text: the short clauses holding a number (damage, %, slots), at most four."""
    out = []
    for clause in re.split(r"(?<!ед)\.\s+|[;!?,(]\s+|\)\s*", desc):
        clause = clause.strip(" .")
        if re.search(r"\d", clause) and len(clause) <= 70:
            out.append(clause)
        if len(out) == 4:
            break
    if not out:
        m = re.search(r"(одним|двумя|тремя|\d+)\s+отсек\w*", desc)
        out = [m.group(0)] if m else []
    return "; ".join(out)


def ship_stats(desc):
    rows = re.findall(r'<td class="label">([^<]+)</td>\s*<td class="firstColumn">\s*([^<]*?)\s*</td>', desc or "")
    return ", ".join("%s %s" % (k.strip(), v.strip()) for k, v in rows if v.strip())


def loot_of(key, v):
    """The item's id; a package keeps its own key (what it holds is {@code contents})."""
    loot = (v.get("imageVars") or {}).get("loot_id") or key
    return key if key.startswith("package_") else loot


def contents(key, v):
    loot = (v.get("imageVars") or {}).get("loot_id") or ""
    return loot if key.startswith("package_") and loot and loot != key else ""


def price(v):
    if v.get("isPaymentItem") or v.get("price") is None:
        return "за деньги" if v.get("isPaymentItem") else "—"
    p = "{:,}".format(int(v["price"])).replace(",", " ")
    return "%s %s" % (p, CURRENCY.get(v.get("currency"), v.get("currency") or ""))


def cell(s):
    return (s or "").replace("|", "/").strip()


def auction(recon):
    files = glob.glob(os.path.join(recon, "*internalAuction*.html"))
    if not files:
        return [], ""
    s = open(files[0], encoding="utf-8").read()
    rows = re.findall(
        r'itemKey="(item_\w+?_\d+)".*?auction_item_name_col">\s*(.*?)\s*</td>.*?auction_item_type">\s*(.*?)\s*</td>'
        r'.*?auction_item_highest"[^>]*>\s*(.*?)\s*</td>.*?auction_item_current">\s*(.*?)\s*</td>'
        r'.*?auction_item_instant">\s*([^<]*?)\s*<.*?_lootId" value="([^"]*)".*?_instantBuy" value="([^"]*)"', s, re.S)
    left = re.search(r"var counterHour\s*=\s*\{\s*second:\s*(\d+),\s*minute:\s*(\d+)", s)
    when = "до конца часа %s:%s" % (left.group(2), left.group(1).zfill(2)) if left else ""
    return rows, when


def hangar(recon):
    out = []
    for f in sorted(glob.glob(os.path.join(recon, "hangar-[0-9]*.json"))):
        h = json.load(open(f, encoding="utf-8"))
        d = h.get("data", {})
        ids = d.get("map", {}).get("lootIds", [])
        for i in d.get("ret", {}).get("items", []):
            loot = ids[i["L"]] if isinstance(i.get("L"), int) and i["L"] < len(ids) else "?"
            where = "на корабле" if i.get("EQH") else "в запасе"
            out.append((loot, i.get("Q"), where))
    return out


def main(recon, root="."):
    names = json.load(open(os.path.join(root, "reference/maps/item-names-ru.json"), encoding="utf-8"))
    tabs = shop(recon)
    lots, when = auction(recon)
    sold = {}
    for tab, data in tabs.items():
        for key, v in data.items():
            sold.setdefault(loot_of(key, v), "магазин: " + price(v))
    for key, name, typ, lead, cur, inst, loot, can in lots:
        sold.setdefault(loot, "аукцион")
        if sold[loot].startswith("магазин"):
            sold[loot] += "; аукцион"
    date = os.path.basename(os.path.normpath(recon))

    lines = ["# Предметы: магазин, аукцион, ангар", "",
             "Сгенерировано `scripts/update-items-doc.sh` из разведки сайта `%s` (`scripts/site-recon.sh`, только чтение) и текстов игры"
             " (`reference/maps/item-names-ru.json`). Цены — как показал магазин этого аккаунта (сервер int1); «за деньги» — товар"
             " за реальные деньги. Полный каталог всех предметов игры — `docs/items/` (ниже)." % date, ""]
    lines += ["## Оглавление", ""]
    for tab, title in TABS:
        if tab in tabs:
            lines.append("- [%s](#%s) — %d" % (title, title.lower().replace(" ", "-"), len(tabs[tab])))
    lines += ["- [Аукцион, каждый час](#аукцион-каждый-час) — %d" % len(lots), "- [Ангар нового аккаунта](#ангар-нового-аккаунта)",
              "- [Каталог всех предметов игры](#каталог-всех-предметов-игры)", ""]
    for tab, title in TABS:
        if tab not in tabs:
            continue
        lines += ["## " + title, ""]
        if tab == "Ships":
            lines += ["| Корабль | Id | Цена | Характеристики |", "|---|---|---|---|"]
            for key, v in tabs[tab].items():
                lines.append("| %s | `%s` | %s | %s |" % (cell(v.get("name")), loot_of(key, v), price(v),
                                                         cell(ship_stats(v.get("description")))))
        else:
            head = "| Предмет | Id | Цена | Лимит |" + (" Характеристики |" if tab in DESCRIBED else "")
            lines += [head, "|---|---|---|---|" + ("---|" if tab in DESCRIBED else "")]
            for key, v in tabs[tab].items():
                limit = v.get("maxAmount") or ""
                if v.get("dailyLimitMax"):
                    limit = "%s в день" % v["dailyLimitMax"]
                inside = contents(key, v)
                ident = "`%s`" % loot_of(key, v) + (" (внутри `%s`)" % inside if inside else "")
                row = "| %s | %s | %s | %s |" % (cell(v.get("name")), ident, price(v), limit)
                if tab in DESCRIBED:
                    row += " %s |" % cell(stats(text(v.get("description"))))
                lines.append(row)
        lines.append("")
    lines += ["## Аукцион, каждый час", "",
              "Ставки — кредитами; «купить сразу» — уридием (цена магазина). Снимок страницы %s%s. Как устроен аукцион —"
              " `docs/recon/SHOP-AUCTION.md`." % (date, ", " + when if when else ""), "",
              "| Лот | Id | Раздел | Ставка сейчас | Купить сразу, ури |", "|---|---|---|---|---|"]
    for key, name, typ, lead, cur, inst, loot, can in lots:
        lines.append("| %s | `%s` | %s | %s | %s |" % (cell(name), loot, cell(typ), "нет" if cur.strip() in ("0", "") else cur,
                                                     inst if can == "1" else "нельзя"))
    lines += ["", "## Ангар нового аккаунта", "", "Что есть у аккаунта в день разведки (`getHangar`).", "",
              "| Предмет | Id | Где | Количество |", "|---|---|---|---|"]
    for loot, q, where in hangar(recon):
        lines.append("| %s | `%s` | %s | %s |" % (cell(names.get(loot, "")), loot, where, q or 1))
    lines += ["", "## Каталог всех предметов игры", "",
              "Все предметы, которые называет игра (%d), по группам; у каждого — где он продаётся по этой разведке." % len(names), ""]
    grouped = collections.OrderedDict((g, []) for g, _, _ in GROUPS)
    for loot in sorted(names):
        for g, _, prefixes in GROUPS:
            if not prefixes or loot.startswith(prefixes):
                grouped[g].append(loot)
                break
    os.makedirs(os.path.join(root, "docs/items"), exist_ok=True)
    for g, title, _ in GROUPS:
        lines.append("- [%s](items/%s.md) — %d" % (title, g, len(grouped[g])))
        body = ["# " + title, "", "Из `docs/ITEMS.md` (сгенерировано `scripts/update-items-doc.sh`, разведка %s)." % date, "",
                "| Название | Id | Где взять |", "|---|---|---|"]
        for loot in grouped[g]:
            body.append("| %s | `%s` | %s |" % (cell(names[loot]), loot, sold.get(loot, "")))
        with open(os.path.join(root, "docs/items/%s.md" % g), "w", encoding="utf-8") as f:
            f.write("\n".join(body) + "\n")
    with open(os.path.join(root, "docs/ITEMS.md"), "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print("docs/ITEMS.md: %d товаров магазина, %d лотов; docs/items: %d предметов" % (
        sum(len(d) for d in tabs.values()), len(lots), len(names)))


if __name__ == "__main__":
    main(*sys.argv[1:])
