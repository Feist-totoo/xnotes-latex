"""Reject a Paddle-only APK containing offline models/runtimes or private test assets."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), "Duplicate ZIP entries"
        assert not any(n.endswith((".onnx", ".gguf")) for n in names), "Offline model weights"
        assert not any(n.startswith(("assets/formula-gryph/", "assets/formula-precision/", "assets/formula-texo/")) for n in names), "Offline model assets"
        assert not any("handwriting" in n or "user-formula" in n or n.endswith("paddle-api.enc") for n in names), "Test/private data"
        native = [n for n in names if n.startswith("lib/")]
        assert not any("onnxruntime" in n or "xnotesprecision" in n or "llama" in n or "ggml" in n for n in native), "OCR native runtime"
        for name in names:
            if name.endswith(".dex"):
                dex = archive.read(name)
                for marker in [b"Lai/onnxruntime/", b"Lcom/xnotes/platform/LocalFormulaRecognizer;", b"Lcom/xnotes/platform/PrecisionFormulaRecognizer;"]:
                    assert marker not in dex, "OCR runtime code: " + name
        packs = [n for n in names if n.startswith("assets/formula-packs/") and n.endswith(".xformula")]
        assert len(packs) == 10, "Expected all ten editing packs"
        for abi in ["arm64-v8a", "x86_64"]:
            assert f"lib/{abi}/libxnotespdf.so" in names
            assert f"lib/{abi}/libxnotests.so" in names
        assert apk.stat().st_size < 80_000_000, "Unexpectedly large lightweight build"
        with apk.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        return dict(apk=apk.name, bytes=apk.stat().st_size, sha256=digest, offlineModels=0,
                    offlineRuntimes=0, subjectPacks=len(packs), nativeLibraries=native, privateTestData=False)

if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    parser.add_argument("--report", type=Path)
    args = parser.parse_args()
    result = verify(args.apk)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, indent=2))
