#!/usr/bin/env python3
"""Quest titles from the game's own texts (spacemap/templates/<lang>/resource_quest.xml on the CDN, the file the client
loads into its «quests» category): quest_title_<id> → title, written to reference/maps/quest-titles-<lang>.json."""
import json
import re
import sys
import urllib.request

CDN = "https://darkorbit-22.bpsecure.com/spacemap/templates/%s/resource_quest.xml"


def main(lang="ru", out=None):
    req = urllib.request.Request(CDN % lang, headers={"User-Agent": "Mozilla/5.0"})
    text = urllib.request.urlopen(req, timeout=60).read().decode("utf-8")
    items = dict(re.findall(r'<item name="([^"]+)"><!\[CDATA\[(.*?)\]\]></item>', text, re.S))
    titles = {}
    for key, value in items.items():
        m = re.fullmatch(r"quest_title_(\d+)(?:_([a-z]+))?", key)
        if m and value.strip() and not value.startswith("{"):
            titles[m.group(1) + ("_" + m.group(2) if m.group(2) else "")] = value.strip()
    ordered = dict(sorted(titles.items(), key=lambda kv: (int(kv[0].split("_")[0]), kv[0])))
    path = out or "reference/maps/quest-titles-%s.json" % lang
    with open(path, "w", encoding="utf-8") as f:
        json.dump(ordered, f, ensure_ascii=False, indent=1)
        f.write("\n")
    print("%d titles → %s" % (len(ordered), path))


if __name__ == "__main__":
    main(*sys.argv[1:])
