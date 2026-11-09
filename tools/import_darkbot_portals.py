"""Extract the portal graph of regular maps from DarkBot's StarManager as plain facts.

Usage: python3 -I tools/import_darkbot_portals.py <StarManager.java> <commit sha> <out.json>

DarkBot (GPL, https://github.com/darkbot-reloaded/DarkBot) hard-codes where each portal of a
map is and which map it leads to; neither the game client nor the game traffic contains the
destinations. Only these facts are taken (coordinates, map ids and names, faction-specific
portals); no DarkBot code is used. Galaxy gates (addGG/accessBy) are left out.
"""
import json
import re
import sys

MAP_RE = re.compile(r'\.addMap\(\s*(-?\d+)\s*,\s*"([^"]+)"(?:\s*,\s*"([^"]+)")?\s*\)')
PORTAL_RE = re.compile(r'\.addPortal\(\s*(-?\d+)\s*,\s*(-?\d+)\s*,\s*"([^"]+)"(?:\s*,\s*(\d+))?\s*\)')
CALL_RE = re.compile(r'\.(addMap|addGG|addPortal)\(')


def main() -> int:
    src, sha, out = sys.argv[1:4]
    text = open(src, encoding="utf-8").read()
    # drop line comments so commented-out portals do not count
    text = re.sub(r"//[^\n]*", "", text)

    maps = []
    current = None
    for m in CALL_RE.finditer(text):
        kind = m.group(1)
        if kind == "addGG":
            current = None
            continue
        if kind == "addMap":
            mm = MAP_RE.match(text, m.start())
            if not mm:
                raise SystemExit(f"cannot parse addMap at {m.start()}")
            map_id = int(mm.group(1))
            if map_id < 0:  # DarkBot's virtual maps ("Loading", "Home Map")
                current = None
                continue
            current = {"id": map_id, "name": mm.group(2), "portals": []}
            maps.append(current)
            continue
        pm = PORTAL_RE.match(text, m.start())
        if not pm:
            raise SystemExit(f"cannot parse addPortal at {m.start()}")
        if current is None:
            continue
        portal = {"x": int(pm.group(1)), "y": int(pm.group(2)), "to": pm.group(3)}
        if pm.group(4):
            portal["faction"] = int(pm.group(4))
        current["portals"].append(portal)

    by_name = {m["name"]: m["id"] for m in maps}
    for m in maps:
        for p in m["portals"]:
            # "Home Map" is a DarkBot placeholder for the faction's home map
            p["toId"] = by_name.get(p["to"], -1)

    doc = {
        "source": "DarkBot StarManager (GPL), facts only",
        "repository": "https://github.com/darkbot-reloaded/DarkBot",
        "commit": sha,
        "maps": maps,
    }
    with open(out, "w", encoding="utf-8") as fh:
        json.dump(doc, fh, indent=1, ensure_ascii=False)
        fh.write("\n")
    print(f"maps={len(maps)} portals={sum(len(m['portals']) for m in maps)}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
