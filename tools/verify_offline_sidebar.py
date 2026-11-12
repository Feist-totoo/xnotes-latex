"""Verify the downloadable-model APK: runtime present, weights and Paddle absent."""
import argparse
import hashlib
import json
import zipfile
from pathlib import Path

def verify(apk):
    with zipfile.ZipFile(apk) as archive:
        names = archive.namelist()
        assert len(names) == len(set(names)), 'Duplicate ZIP entries'
        assert not any(n.endswith(('.onnx', '.gguf')) for n in names), 'Bundled model weights'
        assert not any(n.startswith(('assets/formula-gryph/', 'assets/formula-precision/', 'assets/formula-texo/')) for n in names)
        assert not any('handwriting' in n or 'user-formula' in n or n.endswith('paddle-api.enc') for n in names), 'Private/test data'
        for name in names:
            if name.endswith('.dex'):
                dex = archive.read(name)
                for marker in [b'Lcom/xnotes/platform/PaddleFormulaSettings;', b'Lcom/xnotes/core/formula/PaddleFormulaApi;',
                    b'Lcom/xnotes/ui/PaddleFormulaPanelKt;', b'Lcom/xnotes/platform/PrecisionFormulaRecognizer;']:
                    assert marker not in dex, 'Removed provider/runtime code: ' + name
        native = [n for n in names if n.startswith('lib/')]
        assert not any('xnotesprecision' in n or 'llama' in n or 'ggml' in n for n in native)
        for abi in ['arm64-v8a', 'x86_64']:
            for library in ['libxnotespdf.so', 'libxnotests.so', 'libonnxruntime.so', 'libonnxruntime4j_jni.so']:
                assert f'lib/{abi}/{library}' in names, library
        for asset in ['vocabulary.json', 'LICENSE-GryphOne.txt', 'LICENSE-DINO.txt', 'LICENSE-ONNX-Runtime.txt']:
            assert 'assets/offline-math/' + asset in names
        packs = [n for n in names if n.startswith('assets/formula-packs/') and n.endswith('.xformula')]
        assert len(packs) == 10
        assert apk.stat().st_size < 100_000_000, 'Unexpected APK growth'
        return dict(apk=apk.name, bytes=apk.stat().st_size, sha256=hashlib.sha256(apk.read_bytes()).hexdigest(),
            bundledModelWeights=0, paddleProvider=False, runtime='ONNX Runtime Android 1.23.2',
            downloadableModelBytes=32226621, subjectPacks=len(packs), nativeLibraries=native, privateTestData=False)

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path); parser.add_argument('--report', type=Path)
    args = parser.parse_args(); result = verify(args.apk)
    if args.report:
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(result, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(result, indent=2))
