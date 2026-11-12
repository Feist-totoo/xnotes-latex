"""Freeze 160 new HUMAN official-TEST records before v5 prediction inspection."""
import hashlib,json,re,tarfile,xml.etree.ElementTree as ET
from pathlib import Path
from prepare_subjects import ROOT,Restricted,render

def calculus(tokens):
    s=''.join(tokens)
    if re.search(r'\\(?:i?int|oint|partial)|lim',s):return True
    # A numerator beginning in d alone is not a derivative.
    def group(i):
        if i>=len(s) or s[i]!='{':return '',i
        start=i+1;depth=1;i+=1
        while i<len(s):
            if s[i]=='{':depth+=1
            elif s[i]=='}':depth-=1
            if depth==0:return s[start:i],i+1
            i+=1
        return '',i
    for match in re.finditer(r'\\frac',s):
        numerator,pos=group(match.end());denominator,_=group(pos)
        if numerator.startswith('d') and denominator.startswith('d'):return True
    return False

def main():
    work=ROOT/'build/accuracy-v5';out=work/'confirmation'
    if (out/'manifest.json').exists():raise ValueError('Frozen confirmation already exists')
    source=ROOT/'build/accuracy-v4/gryph_mathwriting_valid_test.pkl'
    with source.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='d76e4a4fe27c7bbee1ae51da8843f04bdbf3a36c354bde37650a2b2326c378f4'
    with source.open('rb') as f:data=Restricted(f).load()['test']
    excluded=set()
    manifests=[ROOT/f'build/accuracy-v3/mathwriting-{s}/manifest.json' for s in ['test','valid']]
    manifests += [ROOT/f'build/accuracy-v4/subject-{s}/manifest.json' for s in ['test','valid']]
    manifests += [ROOT/'build/accuracy-v4/confirmation/manifest.json']
    for p in manifests:excluded.update(r['id'] for r in json.loads(p.read_text(encoding='utf-8')))
    def subject(r):
        if ('\\begin{matrix}' in r['tex'] and '&' in r['tex']) or '\\vec' in r['tex']:return 'linear_algebra'
        if calculus(r['tex']):return 'calculus'
    out.mkdir(parents=True,exist_ok=True);rows=[]
    for discipline in ['calculus','linear_algebra']:
        eligible=[r for r in data if r['name'] not in excluded and subject(r)==discipline]
        eligible.sort(key=lambda r:hashlib.sha256(('xnotes-v5-final-test-20261112:'+r['name']).encode()).hexdigest())
        assert len(eligible)>=80
        for r in eligible[:80]:
            image=r['name']+'.png';strokes=r['name']+'.strokes.json'
            render(r['ink']).save(out/image)
            raw=json.dumps(r['ink'],separators=(',',':')).encode();(out/strokes).write_bytes(raw)
            rows.append(dict(id=r['name'],image=image,strokes=strokes,latex=' '.join(r['tex']),subject=discipline,
                split='official-test-unused-v5',strokes_sha256=hashlib.sha256(raw).hexdigest(),image_sha256=hashlib.sha256((out/image).read_bytes()).hexdigest()))
    wanted={r['id']:r for r in rows};verified=0
    archive=ROOT/'build/accuracy-v4/mathwriting-2024.tgz'
    with archive.open('rb') as f:assert hashlib.file_digest(f,'sha256').hexdigest()=='f1977f120b0efa8abf715ac544b0596b213b099133da99b5e64f946730929269'
    with tarfile.open(archive,mode='r|gz') as tar:
        for member in tar:
            parts=Path(member.name).parts
            if not member.isfile() or len(parts)!=3 or parts[1]!='test':continue
            row=wanted.get(Path(parts[2]).stem)
            if row is None:continue
            raw=tar.extractfile(member).read();tree=ET.fromstring(raw)
            meta={a.get('type'):a.text for a in tree.findall('{*}annotation')}
            assert meta['inkCreationMethod']=='human'
            pen=[[list(map(float,p.split()[:2])) for p in t.text.split(',')] for t in tree.findall('{*}trace')]
            assert pen==json.loads((out/row['strokes']).read_text(encoding='utf-8'))
            assert ''.join(row['latex'].split())==''.join(meta['normalizedLabel'].split())
            row.update(human_verified=True,inkml_sha256=hashlib.sha256(raw).hexdigest());verified+=1
    assert verified==160
    blob=json.dumps(rows,indent=2).encode();(out/'manifest.json').write_bytes(blob)
    freeze=dict(samples=160,source_partition='official TEST, disjoint from all v3/v4 sets',sha256=hashlib.sha256(blob).hexdigest(),
        selection='SHA256 stable order, 80 calculus markers / 80 vectors or multicolumn matrices, original HUMAN ink verified; predictions not used',
        scope='Only 4 unused multicolumn matrices remain in official TEST after v4. This new linear-algebra group adds vector notation; do not compare its percentage directly with the old matrix-only group.',
        seed='xnotes-v5-final-test-20261112:',metric='unchanged conservative MathML; all cases retained')
    (work/'confirmation-freeze.json').write_text(json.dumps(freeze,indent=2)+'\n',encoding='utf-8')
    print(json.dumps(freeze),flush=True)

if __name__=='__main__':main()
