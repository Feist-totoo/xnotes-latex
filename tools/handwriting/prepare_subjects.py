import hashlib,json,pickle,re
from pathlib import Path
from PIL import Image,ImageDraw
ROOT=Path(__file__).resolve().parents[2]
class Restricted(pickle.Unpickler):
    def find_class(self,module,name):raise ValueError('Data-only pickle required')

def render(strokes):
    points=[p for t in strokes for p in t];left=min(p[0] for p in points);top=min(p[1] for p in points)
    width=max(p[0] for p in points)-left;height=max(p[1] for p in points)-top;scale=min(128/max(height,1),1500/max(width,1))
    size=(int(width*scale)+16,int(height*scale)+16);im=Image.new('RGB',(size[0]*4,size[1]*4),'white');d=ImageDraw.Draw(im)
    for trace in strokes:
        pts=[((x-left)*scale*4+32,(y-top)*scale*4+32) for x,y in trace];d.line(pts,fill='black',width=6,joint='curve')
        for x,y in pts:d.ellipse((x-3,y-3,x+3,y+3),fill='black')
    return im.resize(size,Image.Resampling.LANCZOS)

def main():
    p=ROOT/'build/accuracy-v4/gryph_mathwriting_valid_test.pkl'
    with p.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='d76e4a4fe27c7bbee1ae51da8843f04bdbf3a36c354bde37650a2b2326c378f4'
    with p.open('rb') as f:data=Restricted(f).load()
    old={r['id'] for split in ('test','valid') for r in json.loads((ROOT/f'build/accuracy-v3/mathwriting-{split}/manifest.json').read_text(encoding='utf-8'))}
    def subject(row):
        tex=''.join(row['tex'])
        # Multi-column matrices/row vectors; exclude binomial coefficients encoded
        # by this dataset as a one-column matrix inside parentheses.
        if '\\begin{matrix}' in row['tex'] and '&' in row['tex']:return 'linear_algebra'
        if re.search(r'\\(?:i?int|oint|partial)|lim|\\frac\{d',tex):return 'calculus'
    summaries={}
    for split,n in [('valid',80),('test',100)]:
        folder=ROOT/f'build/accuracy-v4/subject-{split}';folder.mkdir(exist_ok=True)
        rows=[]
        for discipline in ['calculus','linear_algebra']:
            eligible=[r for r in data[split] if r['name'] not in old and subject(r)==discipline]
            eligible.sort(key=lambda r:hashlib.sha256(('xnotes-v4-subjects-20261112:'+r['name']).encode()).hexdigest())
            assert len(eligible)>=n
            for r in eligible[:n]:
                image=r['name']+'.png';traces=r['name']+'.strokes.json'
                render(r['ink']).save(folder/image)
                encoded=json.dumps(r['ink'],separators=(',',':')).encode();(folder/traces).write_bytes(encoded)
                # TeX command names must remain delimited: \\int followed by e
                # must never turn into the unrelated command \\inte.
                rows.append(dict(id=r['name'],image=image,strokes=traces,latex=' '.join(r['tex']),subject=discipline,split=split,
                    strokes_sha256=hashlib.sha256(encoded).hexdigest(),image_sha256=hashlib.sha256((folder/image).read_bytes()).hexdigest()))
        blob=json.dumps(rows,indent=2).encode();(folder/'manifest.json').write_bytes(blob);summaries[split]=dict(samples=len(rows),sha256=hashlib.sha256(blob).hexdigest())
    print(summaries)
    (ROOT/'build/accuracy-v4/subject-split-freeze.json').write_text(json.dumps(dict(date='2026-11-12',selection='SHA256 stable order; excluded v3 excerpt valid/test IDs; matrix expressions and calculus markers; no model prediction used',source_sha256='d76e4a4fe27c7bbee1ae51da8843f04bdbf3a36c354bde37650a2b2326c378f4',splits=summaries),indent=2),encoding='utf-8')
if __name__=='__main__':main()
