#!/usr/bin/env python3
"""Item names from the game's own texts (spacemap/templates/<lang>/resource_items.xml on the CDN, the client's item
texts): items_<lootId>_fullname → name, written to reference/maps/item-names-<lang>.json."""
import json
import re
import sys
import urllib.request

CDN = "https://darkorbit-22.bpsecure.com/spacemap/templates/%s/resource_items.xml"


def main(lang="ru", out=None):
    req = urllib.request.Request(CDN % lang, headers={"User-Agent": "Mozilla/5.0"})
    text = urllib.request.urlopen(req, timeout=120).read().decode("utf-8")
    names = {}
    for key, value in re.findall(r'<item name="items_([^"]+)_fullname"><!\[CDATA\[(.*?)\]\]></item>', text, re.S):
        value = " ".join(value.split())
        if value and not value.startswith("{"):
            names[key] = value
    path = out or "reference/maps/item-names-%s.json" % lang
    with open(path, "w", encoding="utf-8") as f:
        json.dump(dict(sorted(names.items())), f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("%d names → %s" % (len(names), path))


if __name__ == "__main__":
    main(*sys.argv[1:])
