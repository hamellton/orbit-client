"""Cross-check parser JSON against the decompiled AS3 sources.

Usage: python3 -I validate_packets.py <packets.json> <module_dir>

Checks:
  * packet ids are unique;
  * the number of primitive write calls in each write() body matches the
    number of primitive entries the parser produced (catches silently
    dropped fields);
  * unknown/odd writeBody entry types.
"""
import collections
import json
import os
import re
import sys

PRIM_WRITE = re.compile(r"param1\.write(Int|Short|Byte|UTF|Boolean|Double|Float)\(")


def write_body_lines(src: str) -> list:
    m = re.search(r"public function write\(param1:IDataOutput\) : void\s*\{", src)
    if not m:
        return []
    depth, i, start = 1, m.end(), m.end()
    while depth and i < len(src):
        depth += {"{": 1, "}": -1}.get(src[i], 0)
        i += 1
    return src[start:i - 1].splitlines()


def main() -> int:
    packets_path, module_dir = sys.argv[1:3]
    packets = json.load(open(packets_path, encoding="utf-8"))

    ids = collections.Counter(p["id"] for p in packets)
    dup = {k: v for k, v in ids.items() if v > 1}
    print(f"packets={len(packets)} unique_ids={len(ids)} duplicate_ids={len(dup)}")
    for k in list(dup)[:10]:
        print("  dup", k, [p["name"] for p in packets if p["id"] == k])

    mismatches, kinds = [], collections.Counter()
    for p in packets:
        src = open(os.path.join(module_dir, p["file"]), encoding="utf-8").read()
        lines = write_body_lines(src)
        # first writeShort is the packet id; array length writes are counted separately
        # writeShort(0) in the else-branch is the "null submodule" marker, not a field
        prim_calls = sum(1 for ln in lines
                         if PRIM_WRITE.search(ln) and ".length" not in ln
                         and "param1.writeShort(0);" not in ln) - 1
        parsed = 0
        for d in p["writeBody"]:
            t = d.get("type") or d.get("type:")
            kinds[t] += 1
            if t in ("int", "short", "byte", "utf", "boolean", "double", "float", "arrayOfPrimitives"):
                parsed += 1
        if prim_calls != parsed:
            mismatches.append((p["name"], prim_calls, parsed))

    print("entry kinds:", dict(kinds))
    print(f"primitive-count mismatches: {len(mismatches)}")
    for m in mismatches[:25]:
        print("  ", m)
    return 0


if __name__ == "__main__":
    sys.exit(main())
