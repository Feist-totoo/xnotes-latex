"""Fetch the pinned, AGPL-3.0 Texo model for a fully offline Android build."""
import hashlib
import json
from pathlib import Path
import urllib.request

REVISION = "b2668efe5112082846fde4d446b9bfaab3989533"
BASE = f"https://huggingface.co/alephpi/FormulaNet/resolve/{REVISION}/onnx/"
DEST = Path(__file__).resolve().parents[1] / "app/src/main/assets/formula"
FILES = {
    "encoder_model.onnx": "fbd69cf63cf833db1e2ef40013d859b560671c1253278441a01bde4516b624ae",
    "decoder_model_merged.onnx": "61d4e9e60e3caa62af3f28a15a22bc13567eb4e618c87917d9597461e54c46be",
    "tokenizer.json": "1240f9d178e1ad2a0076fe95ba62e332871c702accdd5ce3ae3ef33ffd6c3a1e",
}


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    records = {}
    for name, expected in FILES.items():
        target = DEST / name
        if target.exists() and (expected is None or hashlib.sha256(target.read_bytes()).hexdigest() == expected):
            print(f"Already present: {name}", flush=True)
        else:
            temporary = target.with_suffix(target.suffix + ".part")
            print(f"Downloading {name}", flush=True)
            with urllib.request.urlopen(BASE + name, timeout=120) as src, temporary.open("wb") as out:
                while chunk := src.read(1024 * 1024):
                    out.write(chunk)
            actual = hashlib.sha256(temporary.read_bytes()).hexdigest()
            if expected and actual != expected:
                raise RuntimeError(f"Checksum mismatch for {name}")
            temporary.replace(target)
        records[name] = hashlib.sha256(target.read_bytes()).hexdigest()
    (DEST / "manifest.json").write_text(json.dumps({"model": "alephpi/FormulaNet", "revision": REVISION, "license": "AGPL-3.0", "sha256": records}, indent=2) + "\n", encoding="utf-8")
    print("Offline formula model ready.", flush=True)


if __name__ == "__main__":
    main()
