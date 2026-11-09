"""Download the client's map descriptions and store the facts the bot needs.

Usage: python3 -I tools/update_maps.py [--cdn https://darkorbit-22.bpsecure.com/spacemap/]

The client reads ``graphics/maps-config.xml`` (located through ``xml/resources.xml``) and takes
each map's name and ``scaleFactor`` from it: size = 21000 x 13100 times the scale factor
(``net.bigpoint.darkorbit.map.model.Map.init``). ``<reference selector="map WHERE id==N"
children="true"/>`` copies the children of another map, never its attributes
(``XMLUtils.resolveReferences``).

Writes ``reference/maps/maps.json``; prints the maps that changed since the last run.
"""
import argparse
import gzip
import json
import re
import sys
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reference" / "maps" / "maps.json"
STD_WIDTH, STD_HEIGHT = 21000, 13100
USER_AGENT = "BigPointClient/1.6.9"
SELECTOR = re.compile(r"map WHERE id==(\d+)")


def fetch(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
    with urllib.request.urlopen(req, timeout=60) as resp:
        data = resp.read()
    # the CDN sends gzip even when it was not asked for
    return gzip.decompress(data) if data[:2] == b"\x1f\x8b" else data


def spacemap_info(map_el, by_id, depth=0):
    """spacemapInfo of a map, following children references like the client."""
    info = map_el.find("spacemapInfo")
    if info is not None or depth > 5:
        return info
    for ref in map_el.findall("reference"):
        m = SELECTOR.fullmatch(ref.get("selector", ""))
        if m and ref.get("children") != "false" and m.group(1) in by_id:
            found = spacemap_info(by_id[m.group(1)], by_id, depth + 1)
            if found is not None:
                return found
    return None


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--cdn", default="https://darkorbit-22.bpsecure.com/spacemap/")
    args = ap.parse_args()
    cdn = args.cdn if args.cdn.endswith("/") else args.cdn + "/"

    resources = ET.fromstring(fetch(cdn + "xml/resources.xml"))
    locations = {l.get("id"): l.get("path") for l in resources.iter("location")}
    entry = next((f for f in resources.iter("file") if f.get("id") == "maps-config"), None)
    if entry is None:
        print("В resources.xml нет maps-config — клиент изменился.", file=sys.stderr)
        return 1
    file_hash = entry.get("hash")
    url = f"{cdn}{locations[entry.get('location')]}{entry.get('name')}.{entry.get('type')}?__cv={file_hash}"
    root = ET.fromstring(fetch(url))

    by_id = {m.get("id"): m for m in root.findall("map")}
    maps = []
    for m in root.findall("map"):
        scale = float(m.get("scaleFactor", "1"))
        info = spacemap_info(m, by_id)
        maps.append({
            "id": int(m.get("id")),
            "name": m.get("name", ""),
            "scale": scale,
            "width": round(STD_WIDTH * scale),
            "height": round(STD_HEIGHT * scale),
            "type": info.get("type", "") if info is not None else "",
            "homeFaction": int(info.get("home")) if info is not None and info.get("home") else 0,
            "group": m.get("groupSystemName", ""),
        })
    maps.sort(key=lambda x: x["id"])

    old = {}
    if OUT.exists():
        old = {m["id"]: m for m in json.loads(OUT.read_text(encoding="utf-8"))["maps"]}
    changed = [m for m in maps if old.get(m["id"]) != m]
    removed = sorted(set(old) - {m["id"] for m in maps})

    OUT.parent.mkdir(parents=True, exist_ok=True)
    doc = {"source": "client graphics/maps-config.xml", "hash": file_hash, "maps": maps}
    OUT.write_text(json.dumps(doc, indent=1, ensure_ascii=False) + "\n", encoding="utf-8")
    print(f"Карт: {len(maps)}, изменились: {len(changed)}, удалены: {len(removed)} (maps-config {file_hash})")
    for m in changed[:20]:
        print(f"  {m['id']:4d} {m['name']:<20} {m['width']}x{m['height']} {m['type']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
