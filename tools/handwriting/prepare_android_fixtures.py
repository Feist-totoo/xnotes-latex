"""Prepare verified human data for the instrumented TEST APK only."""
import argparse,hashlib,json,shutil
import numpy as np
from pathlib import Path
from probe_gryph import ROOT,stroke_image

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('data',type=Path);p.add_argument('--confirmation',action='store_true');p.add_argument('--name',choices=['handwriting-v4','handwriting-confirmation','handwriting-v5']);a=p.parse_args()
    name=a.name or ('handwriting-confirmation' if a.confirmation else 'handwriting-v4')
    dest=ROOT/'app/src/androidTest/assets'/name;dest.mkdir(exist_ok=True,parents=True)
    rows=json.loads((a.data/'manifest.json').read_text(encoding='utf-8'))
    for row in rows:
        for key in ['image','strokes']:
            path=a.data/row[key]
            assert path.resolve().parent==a.data.resolve()
            expected=row['image_sha256' if key=='image' else 'strokes_sha256']
            assert hashlib.sha256(path.read_bytes()).hexdigest()==expected
            shutil.copy2(path,dest/path.name)
        pixels=stroke_image(json.loads((a.data/row['strokes']).read_text())).numpy().astype('uint8').tobytes()
        row['raster_sha256']=hashlib.sha256(pixels).hexdigest()
        pen=json.loads((a.data/row['strokes']).read_text())
        stored=[]
        for trace in pen:
            points=np.array(trace);stored.append(((points-points[0]).astype(np.float32).astype(np.float64)+points[0]).tolist())
        pixels=stroke_image(stored).numpy().astype('uint8').tobytes()
        row['raster_roundtrip_sha256']=hashlib.sha256(pixels).hexdigest()
    (dest/'manifest.json').write_text(json.dumps(rows,indent=2),encoding='utf-8')
    print('Prepared test-only human fixtures:',len(rows))

if __name__=='__main__':main()
