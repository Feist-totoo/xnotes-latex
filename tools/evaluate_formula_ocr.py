"""Offline formula OCR smoke comparison. This is NOT a calibrated accuracy benchmark.

Install: pip install onnxruntime==1.23.2 pillow numpy tokenizers==0.22.1
Fetch public smoke samples (only this action uses the network):
  python tools/evaluate_formula_ocr.py fetch --data build/hwe
Evaluate locally:
  python tools/evaluate_formula_ocr.py evaluate --data build/hwe --output build/texo.json
  python tools/evaluate_formula_ocr.py evaluate --data build/hwe --beam 4 --output build/beam.json
  python tools/evaluate_formula_ocr.py evaluate --data build/hwe --kind ppformula \
    --model-dir build/ppformula --model-file pp-formulanet_plus-m.onnx --output build/plus-m.json

The checked-in sample hashes detect changes to the public dataset viewer. No input images are
uploaded. PP-FormulaNet results refer to the tested community ONNX export, not verified Paddle
parity. Scores count conservatively normalized token matches, not visual/semantic equivalence.
"""

import argparse
import base64
import hashlib
import json
import re
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def fetch(data):
    import urllib.request

    reference = json.loads((ROOT / "docs/benchmarks/hwe-smoke-samples.json").read_text(encoding="utf-8"))
    data.mkdir(parents=True, exist_ok=True)
    url = "https://datasets-server.huggingface.co/rows?dataset=alephpi/UniMER-Test&config=default&split=hwe&offset=0&length=100"
    with urllib.request.urlopen(url, timeout=90) as response:
        rows = json.load(response)["rows"]
    expected = {row["id"]: row for row in reference["samples"]}
    records = []
    for row in rows:
        identifier = str(row["row_idx"])
        image = base64.b64decode(row["row"]["image"])
        label = row["row"]["text"]
        check = expected[identifier]
        if hashlib.sha256(image).hexdigest() != check["image_sha256"] or hashlib.sha256(label.encode()).hexdigest() != check["label_sha256"]:
            raise ValueError(f"Public sample {identifier} has changed; do not compare it to the saved baseline")
        name = f"{int(identifier):04}.png"
        (data / name).write_bytes(image)
        records.append(dict(id=identifier, image=name, latex=label))
    if len(records) != reference["count"]:
        raise ValueError("Incomplete sample download")
    (data / "manifest.json").write_text(json.dumps(records, indent=2) + "\n", encoding="utf-8")
    print(f"Verified {len(records)} public samples. Nothing uploaded.")


def canonical(source):
    """Conservative smoke metric; deliberately does not solve or simplify formulas.

    Ignores math whitespace, delimiter sizing and explicit spacing. Equivalent command aliases,
    text spacing, redundant groups and alternative matrix notation are NOT evaluated semantically.
    """
    ignored = {r"\left", r"\right", r"\displaystyle", r"\,", r"\;", r"\!", r"\quad", r"\qquad"}
    return [t for t in re.findall(r"\\[a-zA-Z]+|\\.|[^\s]", source) if t not in ignored]


def preprocess(path, kind):
    import numpy as np
    from PIL import Image, ImageOps

    image = Image.open(path).convert("RGB" if kind == "ppformula" else "L")
    grey = np.asarray(image.convert("L"), dtype=np.float32)
    if grey.max() == grey.min():
        raise ValueError("Empty/uniform input image")
    bounds = (grey - grey.min()) / (grey.max() - grey.min()) * 255 if kind == "ppformula" else grey
    ys, xs = np.where(bounds < 200)
    if not len(xs):
        raise ValueError("No ink found")
    image = image.crop((xs.min(), ys.min(), xs.max() + 1, ys.max() + 1))
    width, height = image.size
    if kind == "ppformula":
        # PaddleX UniMERNetImgDecode: short-edge bilinear resize, then PIL thumbnail.
        short = min(width, height)
        image = image.resize((int(384 * width / short), int(384 * height / short)), Image.Resampling.BILINEAR)
        image.thumbnail((384, 384))
        dx, dy = 384 - image.width, 384 - image.height
        image = ImageOps.expand(image, (dx // 2, dy // 2, dx - dx // 2, dy - dy // 2), fill=0)
        pixels = (np.asarray(image, dtype=np.float32) / 255 - .7931) / .1738
        grey = pixels[:, :, 0] * .299 + pixels[:, :, 1] * .587 + pixels[:, :, 2] * .114
        return grey[None, None].astype(np.float32)
    ratio = 384 / max(width, height)
    width, height = round(width * ratio), round(height * ratio)
    canvas = Image.new("L", (384, 384), 0)
    canvas.paste(image.resize((width, height), Image.Resampling.BILINEAR), ((384 - width) // 2, (384 - height) // 2))
    grey = (np.asarray(canvas, dtype=np.float32) / 255 - .7931) / .1738
    return np.repeat(grey[None, None], 3, axis=1)


class Recognizer:
    def __init__(self, args):
        import onnxruntime as ort

        self.args = args
        options = ort.SessionOptions()
        options.intra_op_num_threads = 4
        options.inter_op_num_threads = 1
        options.log_severity_level = 3

        def session(name):
            return ort.InferenceSession(str(args.model_dir / name), options, providers=["CPUExecutionProvider"])

        if args.kind == "ppformula":
            from tokenizers import Tokenizer
            self.model = session(args.model_file)
            self.tokenizer = Tokenizer.from_file(str(args.model_dir / "unimernet_tokenizer.json"))
        else:
            self.encoder = session("encoder_model.onnx")
            self.decoder = session("decoder_model_merged.onnx")
            vocab = json.loads((args.model_dir / "tokenizer.json").read_text(encoding="utf-8"))["model"]["vocab"]
            self.words = {value: key for key, value in vocab.items()}

    def recognize(self, path):
        import numpy as np

        if self.args.kind == "ppformula":
            values = self.model.run(None, {self.model.get_inputs()[0].name: preprocess(path, "ppformula")})
            sequences = [v for v in values if v.dtype == np.int64 and v.ndim == 2]
            if len(sequences) != 1:
                raise ValueError("Expected exactly one token-ID output")
            ids = sequences[0][0].tolist()
            if 2 not in ids:
                return []  # Never count a truncated sequence as a finished formula.
            ids = ids[:ids.index(2)]
            return [self.tokenizer.decode([i for i in ids if 3 < i < self.tokenizer.get_vocab_size()])]

        hidden = self.encoder.run(None, {"pixel_values": preprocess(path, "texo")})[0]
        names = [v.name for v in self.decoder.get_inputs() if v.name.startswith("past_key_values.")]
        outputs = [v.name for v in self.decoder.get_outputs()]
        empty = {name: np.zeros((1, 16, 0, 24), np.float32) for name in names}
        active = [([], 0., empty)]
        finished = []
        width = self.args.beam
        for step in range(self.args.max_tokens):
            expanded = []
            for tokens, score, cache in active:
                inputs = dict(input_ids=np.array([[tokens[-1] if tokens else 0]], np.int64), encoder_hidden_states=hidden, use_cache_branch=np.array([step > 0]), **cache)
                result = dict(zip(outputs, self.decoder.run(None, inputs)))
                logits = result["logits"].reshape(-1).astype(np.float64)
                logits -= logits.max()
                logits -= np.log(np.exp(logits).sum())
                next_cache = {name: cache[name] if step and ".encoder." in name else result[name.replace("past_key_values.", "present.")] for name in names}
                for token in np.argsort(logits)[-width:][::-1]:
                    if token == 2:
                        finished.append((tokens, score + float(logits[token])))
                    elif token > 3:
                        expanded.append((tokens + [int(token)], score + float(logits[token]), next_cache))
            active = sorted(expanded, key=lambda value: value[1], reverse=True)[:width]
            if not active or (width == 1 and finished):
                break
            if len(finished) >= width:
                worst = sorted((score / ((len(tokens) + 1) ** .7) for tokens, score in finished), reverse=True)[width - 1]
                # Scores can only decrease. This is an optimistic upper bound on all future paths.
                best_possible = max(value[1] / ((self.args.max_tokens + 1) ** .7) for value in active)
                if best_possible < worst:
                    break
        final = sorted(finished, key=lambda value: value[1] / ((len(value[0]) + 1) ** .7), reverse=True)[:width]
        return [" ".join(self.words[token] for token in tokens) for tokens, _ in final]


def evaluate(args):
    records = json.loads((args.data / "manifest.json").read_text(encoding="utf-8"))
    recognizer = Recognizer(args)
    rows = []
    for item in records:
        start = time.monotonic()
        candidates = recognizer.recognize(args.data / item["image"])
        truth = canonical(item["latex"])
        rows.append(dict(id=item["id"], reference=item["latex"], candidates=candidates, seconds=time.monotonic() - start,
                         top1=bool(candidates) and canonical(candidates[0]) == truth,
                         topk=any(canonical(c) == truth for c in candidates)))
        if len(rows) % 10 == 0:
            print(f"{len(rows)}/{len(records)}: top1={sum(r['top1'] for r in rows)}, candidate_coverage={sum(r['topk'] for r in rows)}", flush=True)
    filenames = [args.model_file, "unimernet_tokenizer.json"] if args.kind == "ppformula" else ["encoder_model.onnx", "decoder_model_merged.onnx", "tokenizer.json"]
    result = dict(kind=args.kind, beam=args.beam, max_tokens=args.max_tokens if args.kind == "texo" else None,
                  model_sha256={name: sha256(args.model_dir / name) for name in filenames},
                  metric="conservative_token_match_not_semantic_equivalence", samples=len(rows),
                  top1=sum(r["top1"] for r in rows), topk=sum(r["topk"] for r in rows), results=rows)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(f"Saved local report to {args.output}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    download = commands.add_parser("fetch")
    download.add_argument("--data", type=Path, required=True)
    test = commands.add_parser("evaluate")
    test.add_argument("--data", type=Path, required=True)
    test.add_argument("--output", type=Path, required=True)
    test.add_argument("--kind", choices=["texo", "ppformula"], default="texo")
    test.add_argument("--beam", type=int, choices=[1, 4], default=1)
    test.add_argument("--max-tokens", type=int, default=128)
    test.add_argument("--model-dir", type=Path, default=ROOT / "app/src/main/assets/formula")
    test.add_argument("--model-file", default="pp-formulanet_plus-m.onnx")
    args = parser.parse_args()
    if args.command == "fetch":
        fetch(args.data)
    else:
        if args.kind == "ppformula" and args.beam != 1:
            parser.error("The PP-FormulaNet graph controls decoding; external beam search is unavailable")
        if not 1 <= args.max_tokens <= 512:
            parser.error("max-tokens must be within 1..512")
        evaluate(args)


if __name__ == "__main__":
    main()
