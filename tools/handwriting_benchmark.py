"""Reproducible, developer-only real-handwriting evaluation. No user images uploaded.

fetch downloads public evaluation data. evaluate sends images ONLY to a loopback
llama.cpp process on the developer machine; the Android app uses JNI, never HTTP.
MathWriting is CC-BY-NC-SA 4.0 and is used only for evaluation, not model training.
"""
import argparse
import base64
import hashlib
import io
import json
import shutil
import tarfile
import time
import urllib.request
import xml.etree.ElementTree as ET
from pathlib import Path

ARCHIVE = "https://storage.googleapis.com/mathwriting_data/mathwriting-2024-excerpt.tgz"
ARCHIVE_SHA256 = "cba038def001480a89962b25cb20a60df4c4145e94c86ef9d2af65f192cb82bc"

def sha256(data):
    return hashlib.sha256(data).hexdigest()

def render_ink(data):
    from PIL import Image, ImageDraw
    root = ET.fromstring(data)
    annotations = {a.get("type"): a.text for a in root.findall("{*}annotation")}
    if annotations.get("inkCreationMethod") != "human":
        raise ValueError("Only real human pen traces are eligible")
    strokes = [[tuple(map(float, p.split()[:2])) for p in t.text.split(",")] for t in root.findall("{*}trace")]
    points = [p for stroke in strokes for p in stroke]
    left, top = min(p[0] for p in points), min(p[1] for p in points)
    width = max(p[0] for p in points) - left
    height = max(p[1] for p in points) - top
    scale = min(128 / max(height, 1), 1500 / max(width, 1))
    size = (int(width * scale) + 16, int(height * scale) + 16)
    image = Image.new("RGB", (size[0] * 4, size[1] * 4), "white")
    draw = ImageDraw.Draw(image)
    for stroke in strokes:
        pts = [((x-left)*scale*4+32, (y-top)*scale*4+32) for x,y in stroke]
        draw.line(pts, fill="black", width=6, joint="curve")
        for x,y in pts:
            draw.ellipse((x-3,y-3,x+3,y+3), fill="black")
    image = image.resize(size, Image.Resampling.LANCZOS)
    return image, annotations

def fetch(destination):
    destination.mkdir(parents=True, exist_ok=True)
    with urllib.request.urlopen(ARCHIVE, timeout=120) as response:
        data = response.read()
    if sha256(data) != ARCHIVE_SHA256:
        raise ValueError("MathWriting archive changed")
    (destination / "mathwriting-excerpt.tgz").write_bytes(data)
    records = {"valid": [], "test": []}
    with tarfile.open(fileobj=io.BytesIO(data), mode="r:gz") as archive:
        # Read known members directly; never extract archive paths to the filesystem.
        for member in sorted(archive.getmembers(), key=lambda item: item.name):
            parts = Path(member.name).parts
            if not member.isfile() or len(parts) != 3 or parts[1] not in records or not member.name.endswith(".inkml"):
                continue
            source = archive.extractfile(member).read()
            image, annotations = render_ink(source)
            split = parts[1]
            folder = destination / ("mathwriting-" + split)
            folder.mkdir(exist_ok=True)
            filename = Path(parts[2]).stem + ".png"
            image.save(folder / filename)
            records[split].append(dict(id=Path(parts[2]).stem, image=filename,
                latex=annotations["normalizedLabel"], split=split, ink_sha256=sha256(source),
                image_sha256=sha256((folder / filename).read_bytes())))
    for split, rows in records.items():
        assert len(rows) == 100
        (destination / ("mathwriting-" + split) / "manifest.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    # Disjoint from the original 0..99 HWE model-selection smoke set.
    url = "https://datasets-server.huggingface.co/rows?dataset=alephpi/UniMER-Test&config=default&split=hwe&offset=100&length=100"
    with urllib.request.urlopen(url, timeout=120) as response:
        rows = json.load(response)["rows"]
    folder = destination / "hwe-heldout"
    folder.mkdir(exist_ok=True)
    records = []
    fingerprints = Path(__file__).resolve().parents[1] / "docs/benchmarks/v3/hwe-heldout-samples.json"
    expected = {r["id"]: r for r in json.loads(fingerprints.read_text(encoding="utf-8"))} if fingerprints.exists() else {}
    for item in rows:
        content = base64.b64decode(item["row"]["image"])
        reference = expected.get(str(item["row_idx"]))
        if reference and (sha256(content) != reference["image_sha256"] or sha256(item["row"]["text"].encode()) != reference["label_sha256"]):
            raise ValueError("Public HWE sample changed; do not reuse the recorded baseline")
        filename = f"{item['row_idx']:04}.png"
        (folder / filename).write_bytes(content)
        records.append(dict(id=str(item["row_idx"]), image=filename, latex=item["row"]["text"], image_sha256=sha256(content)))
    (folder / "manifest.json").write_text(json.dumps(records, indent=2), encoding="utf-8")
    print("Fetched human validation/test traces and disjoint HWE samples. Nothing uploaded.")

def evaluate(data, output, port):
    rows = json.loads((data / "manifest.json").read_text(encoding="utf-8"))
    results = json.loads(output.read_text(encoding="utf-8")) if output.exists() else []
    for old, row in zip(results, rows):
        if old["id"] != row["id"] or old["truth"] != row["latex"]:
            raise ValueError("Cannot resume a report from a different dataset")
    for row in rows[len(results):]:
        content = (data / row["image"]).read_bytes()
        if "image_sha256" in row and sha256(content) != row["image_sha256"]:
            raise ValueError("Sample image changed")
        body = dict(model="local", messages=[dict(role="user", content=[
            dict(type="image_url", image_url=dict(url="data:image/png;base64,"+base64.b64encode(content).decode())),
            dict(type="text", text="Formula Recognition:")])], temperature=0, max_tokens=512, seed=42)
        started = time.monotonic()
        request = urllib.request.Request(f"http://127.0.0.1:{port}/v1/chat/completions",
            data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with urllib.request.urlopen(request, timeout=300) as response:
            result = json.load(response)
        choice = result["choices"][0]
        latex = choice["message"]["content"] if choice["finish_reason"] == "stop" else ""
        results.append(dict(id=row["id"], truth=row["latex"], latex=latex,
            image_sha256=sha256(content), seconds=time.monotonic()-started))
        output.parent.mkdir(parents=True, exist_ok=True)
        output.write_text(json.dumps(results, indent=2), encoding="utf-8")
        print(f"{len(results)}/{len(rows)}", flush=True)

def fixtures(data, output, private_regression):
    rows = json.loads((data / "manifest.json").read_text(encoding="utf-8"))[:12]
    output.mkdir(parents=True, exist_ok=True)
    for row in rows:
        shutil.copyfile(data / row["image"], output / row["image"])
    (output / "manifest.json").write_text(json.dumps(rows, indent=2), encoding="utf-8")
    if private_regression:
        shutil.copyfile(private_regression, output / "user-formula.png")
    (output / "LICENSE.txt").write_text("MathWriting: Google Research, CC-BY-NC-SA 4.0. Evaluation only. "
        "https://github.com/google-research-datasets/mathwriting\nPrivate regression image, if present: user-provided, never publish.\n", encoding="utf-8")

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    get = commands.add_parser("fetch")
    get.add_argument("--destination", type=Path, required=True)
    run = commands.add_parser("evaluate")
    run.add_argument("--data", type=Path, required=True)
    run.add_argument("--output", type=Path, required=True)
    run.add_argument("--port", type=int, default=18086)
    android = commands.add_parser("fixtures")
    android.add_argument("--data", type=Path, required=True)
    android.add_argument("--output", type=Path, default=Path("app/src/androidTest/assets/handwriting"))
    android.add_argument("--private-regression", type=Path)
    args = parser.parse_args()
    if args.command == "fetch": fetch(args.destination)
    elif args.command == "fixtures": fixtures(args.data, args.output, args.private_regression)
    else: evaluate(args.data, args.output, args.port)
