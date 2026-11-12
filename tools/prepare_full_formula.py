"""Prepare all offline models for the complete APK; never used on the tablet."""
import argparse,hashlib,json,shutil,subprocess,sys,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TAG='v0.9.8-latex.5-full'
RUNTIME='2d973636e292ee6f75fadcf08d29cb33511f509f'

def sha(path):
    with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('--cache',type=Path);p.add_argument('--check',action='store_true');a=p.parse_args()
    subprocess.run([sys.executable,str(ROOT/'tools/prepare_handwriting_model.py')]+(['--check'] if a.check else []),check=True)
    folder=ROOT/'app/src/main/assets/formula-precision'
    manifest=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))
    for name,spec in manifest['files'].items():
        target=folder/name
        if target.is_file() and target.stat().st_size==spec['bytes'] and sha(target)==spec['sha256']:
            print('Verified',name,spec['bytes'],flush=True);continue
        if a.check:raise RuntimeError('Missing or corrupted offline asset: '+name)
        part=target.with_suffix('.part')
        try:
            cached=a.cache/name if a.cache else None
            if cached and cached.is_file():shutil.copyfile(cached,part)
            else:
                url=f'https://github.com/Feist-totoo/xnotes-latex/releases/download/{TAG}/{name}'
                with urllib.request.urlopen(url,timeout=180) as response,part.open('wb') as output:shutil.copyfileobj(response,output,1024*1024)
            if part.stat().st_size!=spec['bytes'] or sha(part)!=spec['sha256']:raise RuntimeError('Model checksum failed: '+name)
            part.replace(target)
        finally:part.unlink(missing_ok=True)
        print('Prepared',name,spec['bytes'],flush=True)
    source=ROOT/'build/references/llama.cpp'
    if not source.exists():
        if a.check:raise RuntimeError('Missing pinned llama.cpp source')
        subprocess.run(['git','clone','--depth','1','--branch','b9870','https://github.com/ggml-org/llama.cpp.git',str(source)],check=True)
    actual=subprocess.check_output(['git','-C',str(source),'rev-parse','HEAD'],text=True).strip()
    if actual!=RUNTIME:raise RuntimeError('Unexpected llama.cpp revision: '+actual)
    print('Verified pinned native runtime',actual,flush=True)

if __name__=='__main__':main()
