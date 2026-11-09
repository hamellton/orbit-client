"""Regenerate the protocol after a game update.

Usage (from the repository root):
    python3 -I tools/update_protocol.py [--force] [--skip-build]

Steps:
  1. download main.swf (+ preloader/loadingscreen) into reference/client/<date>/
  2. stop early if main.swf is identical to the one the current protocol was built from
  3. decompile only the packages we need with JPEXS FFDec
  4. extract version metadata (protocol hash, build, compression capability)
  5. run ProjectOpenOrbit's netcode parser and validate the result
  6. write reference/protocol/packets-<tag>.json and meta-<tag>.json
  7. print which packets were added, removed or changed
  8. ./gradlew build (regenerates classes and runs all tests, incl. recorded traffic)

Environment:
  FFDEC_JAR   path to ffdec.jar (default ~/tools/ffdec-26.4.0/ffdec.jar)
  JAVA_HOME   JDK used for FFDec and Gradle (21 recommended)
"""
import argparse
import email.utils
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SWF_BASE = "https://darkorbit-22.bpsecure.com/spacemap/"
SWF_FILES = ["main.swf", "preloader.swf", "loadingscreen.swf"]
PARSER_REPO = "https://github.com/ProjectOpenOrbit/darkorbit-netcode-parser.git"
PARSER_COMMIT = "42d7a7a"
SELECT = ",".join([
    "net.bigpoint.darkorbit.com.module.**",
    "net.bigpoint.darkorbit.comHash.**",
    "net.bigpoint.darkorbit.utils.version.**",
    "net.bigpoint.darkorbit.net.netty.**",
])
PROTOCOL_DIR = ROOT / "reference" / "protocol"


def log(msg: str) -> None:
    print(msg, flush=True)


def fail(msg: str) -> None:
    print(f"ОШИБКА: {msg}", file=sys.stderr, flush=True)
    sys.exit(1)


def java_exe() -> str:
    home = os.environ.get("JAVA_HOME")
    if home:
        exe = Path(home) / "bin" / ("java.exe" if os.name == "nt" else "java")
        if exe.exists():
            return str(exe)
    found = shutil.which("java")
    if not found:
        fail("Java не найдена. Установите JDK 21 и задайте JAVA_HOME.")
    return found


def download(dest_dir: Path) -> str:
    """Downloads the swf files; returns main.swf's Last-Modified date as YYYY-MM-DD."""
    dest_dir.mkdir(parents=True, exist_ok=True)
    date = None
    for name in SWF_FILES:
        req = urllib.request.Request(SWF_BASE + name, headers={"User-Agent": "Mozilla/5.0"})
        with urllib.request.urlopen(req, timeout=120) as resp:
            data = resp.read()
            if name == "main.swf":
                lm = resp.headers.get("Last-Modified")
                if lm:
                    date = email.utils.parsedate_to_datetime(lm).strftime("%Y-%m-%d")
        if data[:3] not in (b"CWS", b"FWS", b"ZWS"):
            fail(f"{name}: это не SWF (первые байты {data[:3]!r})")
        (dest_dir / name).write_bytes(data)
        log(f"  {name}: {len(data)} байт")
    return date or "unknown-date"


def current_meta():
    metas = sorted(PROTOCOL_DIR.glob("meta-*.json"))
    return (json.loads(metas[-1].read_text(encoding="utf-8")), metas[-1]) if metas else (None, None)


def decompile(swf: Path, out_dir: Path) -> Path:
    jar = Path(os.environ.get("FFDEC_JAR", Path.home() / "tools" / "ffdec-26.4.0" / "ffdec.jar"))
    if not jar.exists():
        fail(f"Не найден JPEXS FFDec: {jar}. Скачайте zip с github.com/jindrapetrik/jpexs-decompiler/releases "
             f"и укажите путь в FFDEC_JAR.")
    if out_dir.exists():
        shutil.rmtree(out_dir)
    cmd = [java_exe(), "-jar", str(jar), "-selectclass", SELECT, "-format", "script:as",
           "-export", "script", str(out_dir), str(swf)]
    res = subprocess.run(cmd, capture_output=True, text=True)
    if res.returncode != 0:
        fail("FFDec завершился с ошибкой:\n" + res.stdout[-2000:] + res.stderr[-2000:])
    scripts = out_dir / "scripts"
    if not (scripts / "net/bigpoint/darkorbit/com/module").is_dir():
        fail("После декомпиляции нет пакета com.module — структура клиента изменилась, нужен ручной разбор.")
    return scripts


def extract_meta(scripts: Path) -> dict:
    base = scripts / "net/bigpoint/darkorbit"

    def grab(rel: str, const: str) -> str:
        text = (base / rel).read_text(encoding="utf-8")
        m = re.search(rf'const {const}:String = "([^"]*)"', text)
        if not m:
            fail(f"Не найдена константа {const} в {rel} — структура клиента изменилась.")
        return m.group(1)

    return {
        "clientVersion": grab("utils/version/Version.as", "VERSION"),
        "build": grab("utils/version/Version.as", "BUILD"),
        "versionHash": grab("comHash/VersionHash.as", "VERSION_HASH"),
        "compression": grab("net/netty/DownstreamCompression.as", "CAPABILITY"),
    }


def ensure_parser() -> Path:
    repo = ROOT / "tools" / "external" / "darkorbit-netcode-parser"
    if not repo.exists():
        repo.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "-q", PARSER_REPO, str(repo)], check=True)
        subprocess.run(["git", "-C", str(repo), "checkout", "-q", PARSER_COMMIT], check=True)
    return repo / "src"


def parse(parser_src: Path, module_dir: Path, out_json: Path) -> list:
    tmp = out_json.with_suffix(".raw.json")
    res = subprocess.run([sys.executable, "-I", str(ROOT / "tools/recon/run_parser.py"),
                          str(parser_src), str(module_dir), str(tmp)], capture_output=True, text=True)
    summary = [l for l in res.stdout.splitlines() if not l.startswith("Skipping")]
    log("  " + "\n  ".join(summary[:10]))
    if res.returncode != 0:
        fail("Парсер завершился с ошибкой:\n" + res.stderr[-2000:])
    m = re.search(r"failed=(\d+)", res.stdout)
    failed = int(m.group(1)) if m else -1
    # CannonType is an enum, not a packet; anything else means the client format changed
    if failed > 1:
        fail(f"Парсер не разобрал {failed} файлов — см. вывод выше. Нужна правка парсера.")

    val = subprocess.run([sys.executable, "-I", str(ROOT / "tools/recon/validate_packets.py"),
                          str(tmp), str(module_dir)], capture_output=True, text=True)
    log("  " + "\n  ".join(val.stdout.splitlines()[:6]))
    mm = re.search(r"primitive-count mismatches: (\d+)", val.stdout)
    dup = re.search(r"duplicate_ids=(\d+)", val.stdout)
    # LogBoxInformationRequest has a field named "length" that the validator over-counts
    if not mm or int(mm.group(1)) > 1 or not dup or int(dup.group(1)) != 0:
        fail("Проверка JSON не пройдена — см. вывод выше.")

    packets = json.loads(tmp.read_text(encoding="utf-8"))
    for p in packets:
        p.pop("file", None)
    tmp.unlink()
    return packets


def diff(old: list, new: list) -> None:
    o = {p["name"]: p for p in old}
    n = {p["name"]: p for p in new}
    added = sorted(n.keys() - o.keys())
    removed = sorted(o.keys() - n.keys())
    changed = sorted(k for k in n.keys() & o.keys()
                     if (n[k]["id"], n[k]["writeBody"]) != (o[k]["id"], o[k]["writeBody"]))
    log(f"  Добавлено: {len(added)}, удалено: {len(removed)}, изменено: {len(changed)}")
    for title, names in (("    +", added), ("    -", removed), ("    ~", changed)):
        for name in names[:40]:
            log(f"{title} {name}")
        if len(names) > 40:
            log(f"{title} … ещё {len(names) - 40}")


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--force", action="store_true", help="перегенерировать, даже если main.swf не изменился")
    ap.add_argument("--skip-build", action="store_true", help="не запускать ./gradlew build")
    args = ap.parse_args()

    log("1. Скачиваю клиент…")
    staging = ROOT / "reference" / "client" / "_download"
    date = download(staging)
    sha = hashlib.sha256((staging / "main.swf").read_bytes()).hexdigest()

    meta_old, meta_old_file = current_meta()
    if meta_old and meta_old.get("mainSwfSha256") == sha and not args.force:
        log(f"main.swf не изменился (сборка {meta_old.get('build')}). Протокол актуален.")
        shutil.rmtree(staging)
        return 0
    client_dir = ROOT / "reference" / "client" / date
    if client_dir.exists():
        shutil.rmtree(client_dir)
    staging.rename(client_dir)

    log("2. Декомпилирую нужные пакеты…")
    scripts = decompile(client_dir / "main.swf", ROOT / "reference" / "decompiled" / date / "selected")

    meta = extract_meta(scripts)
    log(f"   Клиент {meta['clientVersion']}, сборка {meta['build']}, хэш протокола {meta['versionHash']}")
    tag = date
    same_day = PROTOCOL_DIR / f"meta-{date}.json"
    if same_day.exists() and json.loads(same_day.read_text(encoding="utf-8")).get("build") != meta["build"]:
        tag = f"{date}-{meta['build']}"  # a second build released on the same day

    log("3. Разбираю пакеты…")
    packets_file = PROTOCOL_DIR / f"packets-{tag}.json"
    packets = parse(ensure_parser(), scripts / "net/bigpoint/darkorbit/com/module", packets_file)
    packets_file.write_text(json.dumps(packets, indent=1, sort_keys=True), encoding="utf-8")

    meta.update({"mainSwfSha256": sha, "mainSwfLastModified": date, "packets": packets_file.name})
    (PROTOCOL_DIR / f"meta-{tag}.json").write_text(json.dumps(meta, indent=1), encoding="utf-8")
    log(f"   Записано: {packets_file.relative_to(ROOT)} ({len(packets)} пакетов)")

    if meta_old:
        log("4. Изменения относительно сборки " + str(meta_old.get("build")) + ":")
        old_packets = json.loads((PROTOCOL_DIR / meta_old["packets"]).read_text(encoding="utf-8"))
        diff(old_packets, packets)

    if args.skip_build:
        log("Сборка пропущена (--skip-build).")
        return 0
    log("5. Генерирую классы, собираю и запускаю тесты…")
    gradlew = ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")
    res = subprocess.run([str(gradlew), "-q", "build"], cwd=ROOT)
    if res.returncode != 0:
        fail("Сборка или тесты не прошли. Если упал RecordedTrafficTest — старые записи сделаны "
             "на прошлой версии протокола: перезапишите сессию сниффером.")
    log("Готово. Протокол обновлён до сборки " + meta["build"] + ".")
    return 0


if __name__ == "__main__":
    sys.exit(main())
