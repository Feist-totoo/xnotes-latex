"""Untouched confirmation set after the initial pack test failed its no-regression check.

Selection is independent of predictions. These are unused official VALID examples,
not a second official TEST partition. The original TEST 200 remains fully reported.
"""
import hashlib,json,re,tarfile,xml.etree.ElementTree as ET
from pathlib import Path
from prepare_subjects import Restricted,render,ROOT

def main():
    work=ROOT/'build/accuracy-v4';source=work/'gryph_mathwriting_valid_test.pkl'
    with source.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='d76e4a4fe27c7bbee1ae51da8843f04bdbf3a36c354bde37650a2b2326c378f4'
    with source.open('rb') as f:data=Restricted(f).load()['valid']
    excluded=set()
    for p in [ROOT/'build/accuracy-v3'/f'mathwriting-{s}/manifest.json' for s in ['valid','test']]+[work/f'subject-{s}/manifest.json' for s in ['valid','test']]:
        excluded.update(r['id'] for r in json.loads(p.read_text(encoding='utf-8')))
    def subject(r):
        if '\\begin{matrix}' in r['tex'] and '&' in r['tex']:return 'linear_algebra'
        if re.search(r'\\(?:i?int|oint|partial)|lim|\\frac\{d',''.join(r['tex'])):return 'calculus'
    folder=work/'confirmation';folder.mkdir(exist_ok=True);rows=[]
    for name in ['calculus','linear_algebra']:
        eligible=[r for r in data if r['name'] not in excluded and subject(r)==name]
        eligible.sort(key=lambda r:hashlib.sha256(('xnotes-v4-confirmation-20261112:'+r['name']).encode()).hexdigest())
        assert len(eligible)>=120
        for r in eligible[:120]:
            image=r['name']+'.png';strokes=r['name']+'.strokes.json'
            render(r['ink']).save(folder/image);blob=json.dumps(r['ink'],separators=(',',':')).encode();(folder/strokes).write_bytes(blob)
            rows.append(dict(id=r['name'],image=image,strokes=strokes,latex=' '.join(r['tex']),subject=name,split='official-valid-unused-confirmation',strokes_sha256=hashlib.sha256(blob).hexdigest(),image_sha256=hashlib.sha256((folder/image).read_bytes()).hexdigest()))
    # Validate original HUMAN records and exact coordinates, without learning from them.
    wanted={r['id']:r for r in rows};verified=[]
    with (work/'mathwriting-2024.tgz').open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='f1977f120b0efa8abf715ac544b0596b213b099133da99b5e64f946730929269'
    with tarfile.open(work/'mathwriting-2024.tgz',mode='r|gz') as archive:
        for m in archive:
            parts=Path(m.name).parts
            if not m.isfile() or len(parts)!=3 or parts[1]!='valid':continue
            id=Path(parts[2]).stem
            if id not in wanted:continue
            raw=archive.extractfile(m).read();tree=ET.fromstring(raw)
            meta={a.get('type'):a.text for a in tree.findall('{*}annotation')};assert meta['inkCreationMethod']=='human'
            pen=[[list(map(float,p.split()[:2])) for p in t.text.split(',')] for t in tree.findall('{*}trace')]
            row=wanted[id];assert pen==json.loads((folder/row['strokes']).read_text())
            assert ''.join(row['latex'].split())==''.join(meta['normalizedLabel'].split())
            verified.append(id);row['inkml_sha256']=hashlib.sha256(raw).hexdigest();row['human_verified']=True
    assert len(verified)==240
    blob=json.dumps(rows,indent=2).encode();(folder/'manifest.json').write_bytes(blob)
    (work/'confirmation-freeze.json').write_text(json.dumps(dict(samples=240,sha256=hashlib.sha256(blob).hexdigest(),selection='SHA256 deterministic; original labels/human provenance/coordinates verified; no prediction used',source_partition='official valid; disjoint from all development and prior test samples',seed='xnotes-v4-confirmation-20261112:'),indent=2))
    print('Frozen 240 confirmation cases',hashlib.sha256(blob).hexdigest(),flush=True)

if __name__=='__main__':main()
