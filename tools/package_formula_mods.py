"""Build independent, data-only .xformula mods and a GitHub Release catalog."""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", default="v0.9.8-latex.5-full")
    parser.add_argument("--output", type=Path, default=ROOT / "build/releases")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    entries = []
    for path in sorted((ROOT / "formula-packs").glob("*.json")):
        pack = json.loads(path.read_text(encoding="utf-8"))
        data = (json.dumps(pack, ensure_ascii=False, separators=(",", ":")) + "\n").encode()
        name = f"{pack['id']}-v{pack['version']}.xformula"
        (args.output / name).write_bytes(data)
        entries.append({k: pack[k] for k in ("id", "version", "nameZh", "nameEn")} | {
            "signals": pack["signals"], "bytes": len(data), "sha256": hashlib.sha256(data).hexdigest(),
            "url": f"https://github.com/Feist-totoo/xnotes-latex/releases/download/{args.tag}/{name}"})
    catalog = dict(schema=1, release=args.tag, packs=entries)
    data = (json.dumps(catalog, ensure_ascii=False, indent=2) + "\n").encode()
    (args.output / "formula-packs-index.json").write_bytes(data)
    assets = ROOT / "app/src/main/assets/formula-packs"
    (assets / "index.json").write_bytes(data)
    for core in entries:
        id = core['id']
        (assets / f"{id}.xformula").write_bytes((args.output / f"{id}-v{core['version']}.xformula").read_bytes())
    print(f"Built {len(entries)} independent packs for {args.tag}")

if __name__ == "__main__": main()
