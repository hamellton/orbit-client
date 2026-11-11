"""Store the game's NPC table (number -> name) from the client.

Usage: python3 -I tools/update_npcs.py [path/to/main.swf]

The client keeps its ship patterns in ``game.xml``, embedded in ``main.swf`` as the binary data
``InitPatternManagerCommand_GAME_XML_FILE``; ``PatternManager.parseShipPatterns`` reads every
``<ship type shipId npcId name>``. The pet's enemy locator offers targets by ``npcId``
(``PetLocatorGearInitializationCommand.possibleTargetValues``) and the client names them through
``shipPatternsByNpcId`` (``InGameCatalog.getNpcName``). On 3-6 it offered 161, 13, 12, 10, 9, 22:
Plagued Kristallin, Cubikon, Protegit, Kristallon, Kristallin, Boss Kristallin.

Writes ``reference/maps/npcs.json`` (bundled with the map facts); prints what changed. By default the
newest ``reference/client/<date>/main.swf`` is read; JPEXS FFDec does the extraction.
"""
import json
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "reference" / "maps" / "npcs.json"
FFDEC = Path.home() / "tools" / "ffdec-26.4.0" / "ffdec.jar"


def newest_swf() -> Path:
    swfs = sorted((ROOT / "reference" / "client").glob("*/main.swf"))
    if not swfs:
        sys.exit("нет reference/client/<дата>/main.swf")
    return swfs[-1]


def game_xml(swf: Path) -> str:
    with tempfile.TemporaryDirectory() as out:
        subprocess.run(["java", "-jar", str(FFDEC), "-export", "binaryData", out, str(swf)],
                       check=True, stdout=subprocess.DEVNULL)
        found = list(Path(out).glob("*InitPatternManagerCommand_GAME_XML_FILE*"))
        if not found:
            sys.exit("в клиенте нет game.xml (InitPatternManagerCommand_GAME_XML_FILE)")
        return found[0].read_text(encoding="utf-8")


def npcs(xml: str) -> list:
    rows = {}
    for m in re.finditer(r"<ship ([^>]*?)/?>", xml):
        a = dict(re.findall(r'(\w+)="([^"]*)"', m.group(1)))
        npc = a.get("npcId", "").strip()
        if npc.isdigit() and a.get("name", "").strip():
            rows.setdefault(int(npc), {"npcId": int(npc), "name": a["name"].strip(), "shipType": a.get("type", "")})
    return [rows[k] for k in sorted(rows)]


def catalog_names() -> dict:
    """``InGameCatalog._npc_names``: the names the client takes first (``getNpcName``), from the newest decompile."""
    found = sorted((ROOT / "reference" / "decompiled").glob("*/main/scripts/net/bigpoint/darkorbit/InGameCatalog.as"))
    if not found:
        return {}
    text = found[-1].read_text(encoding="utf-8")
    return {int(k): v for k, v in re.findall(r'_npc_names\[(\d+)\] = "([^"]+)"', text)}


def main() -> None:
    swf = Path(sys.argv[1]) if len(sys.argv) > 1 else newest_swf()
    new = npcs(game_xml(swf))
    first = catalog_names()
    by_id = {r["npcId"]: r for r in new}
    for npc, name in first.items():
        if npc in by_id:
            by_id[npc]["name"] = name
        else:
            by_id[npc] = {"npcId": npc, "name": name, "shipType": ""}
    new = [by_id[k] for k in sorted(by_id)]
    old = json.loads(OUT.read_text(encoding="utf-8")) if OUT.exists() else []
    before = {r["npcId"]: r["name"] for r in old}
    for r in new:
        if before.get(r["npcId"]) != r["name"]:
            print(f"  {r['npcId']}: {before.get(r['npcId'], '—')} -> {r['name']}")
    OUT.write_text(json.dumps(new, ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"{OUT.relative_to(ROOT)}: {len(new)} NPC из {swf.relative_to(ROOT) if swf.is_relative_to(ROOT) else swf}")


if __name__ == "__main__":
    main()
