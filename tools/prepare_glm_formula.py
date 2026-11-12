"""Developer-only preparation. APK inference never connects to the network.

Downloads the historical v3 GLM-OCR baseline for developer comparisons only.
"""
import argparse
import hashlib
import json
import shutil
import subprocess
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
REVISION = "65a42de1148dbed2297e922b5dbc7d9b70c36578"
LLAMA_REVISION = "2d973636e292ee6f75fadcf08d29cb33511f509f"
FILES = {
    "GLM-OCR-Q8_0.gguf": {"bytes": 950433408, "sha256": "45bc244a6446aff850521dc41f18bc8d7105ad5f0c2c8c28af04e7cc4f4d50b1"},
    "mmproj-GLM-OCR-Q8_0.gguf": {"bytes": 484403648, "sha256": "9c4b58e33e316ed142eb5dcb41abec3844d3e6e5dc361ffb782c3fa9d175141f"},
}

def digest(path):
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache", type=Path, help="Optional existing model directory")
    args = parser.parse_args()
    assets = ROOT / "build/legacy-glm"
    assets.mkdir(parents=True, exist_ok=True)
    for name, spec in FILES.items():
        target = assets / name
        if target.exists() and target.stat().st_size == spec["bytes"] and digest(target) == spec["sha256"]:
            continue
        part = target.with_suffix(".part")
        cached = args.cache / name if args.cache else None
        if cached and cached.exists():
            shutil.copyfile(cached, part)
        else:
            url = f"https://huggingface.co/ggml-org/GLM-OCR-GGUF/resolve/{REVISION}/{name}"
            with urllib.request.urlopen(url, timeout=180) as response, part.open("wb") as output:
                shutil.copyfileobj(response, output, 1024 * 1024)
        if part.stat().st_size != spec["bytes"] or digest(part) != spec["sha256"]:
            raise RuntimeError(f"Checksum failed: {name}")
        part.replace(target)
        print("Verified", name, flush=True)
    manifest = {"model": "ggml-org/GLM-OCR-GGUF", "revision": REVISION,
                "runtime": LLAMA_REVISION, "license": "MIT", "files": FILES}
    (assets / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    source = ROOT / "build/references/llama.cpp"
    if not source.exists():
        source.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["git", "clone", "--depth", "1", "--branch", "b9870",
                        "https://github.com/ggml-org/llama.cpp.git", str(source)], check=True)
    actual = subprocess.check_output(["git", "-C", str(source), "rev-parse", "HEAD"], text=True).strip()
    if actual != LLAMA_REVISION:
        raise RuntimeError(f"Unexpected llama.cpp revision {actual}; require {LLAMA_REVISION}")
    print("Verified runtime", actual)

if __name__ == "__main__":
    main()
