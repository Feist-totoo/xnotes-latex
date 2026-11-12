"""Fetch pinned build assets. The installed Android app never downloads its OCR model.

Converted from the pinned official GryphOne MIT checkpoint. See
tools/handwriting/export_gryph.py and docs/FORMULA_ACCURACY_V4.md for conversion.
No runtime credentials or services are required.
"""
import argparse,hashlib,json,urllib.request
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'app/src/main/assets/formula-gryph'
BASE='https://github.com/Feist-totoo/xnotes-latex/releases/download/v0.9.8-latex.4-preview/'

def valid(path,spec):
    if not path.is_file() or path.stat().st_size!=spec['bytes']:return False
    with path.open('rb') as stream:return hashlib.file_digest(stream,'sha256').hexdigest()==spec['sha256']

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--check',action='store_true');args=p.parse_args()
    manifest=json.loads((ASSETS/'manifest.json').read_text(encoding='utf-8'))
    for name,spec in manifest['files'].items():
        target=ASSETS/name
        if valid(target,spec):print('Verified',name,spec['bytes']);continue
        if args.check or not name.endswith('.onnx'):raise ValueError(f'Missing/invalid asset: {name}')
        temporary=target.with_suffix(target.suffix+'.part')
        try:
            with urllib.request.urlopen(BASE+name,timeout=120) as source,temporary.open('wb') as dest:
                count=0
                while chunk:=source.read(1024*1024):
                    count+=len(chunk)
                    if count>spec['bytes']:raise ValueError('Unexpected model size')
                    dest.write(chunk)
            if not valid(temporary,spec):raise ValueError('Model checksum mismatch')
            temporary.replace(target)
            print('Prepared',name)
        finally:temporary.unlink(missing_ok=True)

if __name__=='__main__':main()
