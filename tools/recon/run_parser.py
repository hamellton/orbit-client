"""Run ProjectOpenOrbit's darkorbit-netcode-parser over a decompiled client.

Usage: python3 -I run_parser.py <parser_src_dir> <module_dir> <out_json>

Walks every .as file under <module_dir>, feeds it to serialize_packet_config,
writes the successful results to <out_json> and prints failure statistics.
"""
import collections
import json
import os
import sys


def main() -> int:
    parser_src, module_dir, out_json = sys.argv[1:4]
    sys.path.insert(0, parser_src)
    from netcode_parser import DONetcodeParser as P  # noqa: E402

    packets, failures, skipped = [], [], 0
    for root, _, files in os.walk(module_dir):
        for name in sorted(files):
            if not name.endswith(".as"):
                continue
            path = os.path.join(root, name)
            try:
                with open(path, encoding="utf-8") as fh:
                    res = P.serialize_packet_config(fh)
                if res is None:
                    skipped += 1
                    continue
                res["file"] = os.path.relpath(path, module_dir)
                packets.append(res)
            except Exception as e:  # parser raises bare RuntimeError on unknown syntax
                failures.append((os.path.relpath(path, module_dir), str(e).splitlines()[0][:160]))

    with open(out_json, "w", encoding="utf-8") as fh:
        json.dump(packets, fh, indent=1)

    print(f"ok={len(packets)} failed={len(failures)} skipped={skipped}")
    reasons = collections.Counter(msg.split(":")[0] for _, msg in failures)
    for reason, n in reasons.most_common(15):
        print(f"  {n:5d}  {reason}")
    for f, msg in failures[:15]:
        print(f"  - {f}: {msg}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
