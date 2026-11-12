"""Developer experiment: training-split counts only; no validation/test learning."""
import collections,hashlib,html,json,pickle,re,sys,tarfile,time,xml.etree.ElementTree as ET
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'build/references/GryphOne'))
from gryph.codec import tex_to_sym
vocab=(ROOT/'build/references/GryphOne/alphabet/mathwriting.txt').read_text(encoding='utf-8').splitlines();ids={s:i for i,s in enumerate(vocab)}
pattern=re.compile(r'\\(?:mathbb{\w}|begin{\w+}|end{\w+}|operatorname\*|[a-zA-Z]+|.)|.')
annotation=re.compile(rb'<annotation type="normalizedLabel">(.*?)</annotation>',re.S)

def counters():return {key:collections.Counter() for key in ['uni','sym2','mod2','pair','examples']}
def add(count,syms,mods):
    count['uni'].update(syms)
    count['sym2'].update(zip([0]+syms,syms+[0]))
    count['mod2'].update(zip([0]+mods,mods+[0]))
    count['pair'].update(zip(syms,mods));count['examples']['count']+=1

def main():
    sets={key:counters() for key in ['general','calculus','linear_algebra']};seen=0;used=0;unknown=0;verified=0
    wanted={}
    for split in ['valid','test']:
        folder=ROOT/f'build/accuracy-v4/subject-{split}'
        for r in json.loads((folder/'manifest.json').read_text()):wanted[(split,r['id'])]=(folder,r)
    archive=ROOT/'build/accuracy-v4/mathwriting-2024.tgz'
    assert archive.stat().st_size==3096141721
    with archive.open('rb') as f:digest=hashlib.file_digest(f,'sha256').hexdigest()
    assert digest=='f1977f120b0efa8abf715ac544b0596b213b099133da99b5e64f946730929269'
    print('Archive',digest,flush=True)
    with tarfile.open(archive,mode='r|gz') as tar:
        for m in tar:
            pieces=Path(m.name).parts
            if not m.isfile() or not m.name.endswith('.inkml') or len(pieces)!=3:continue
            split=pieces[1];sample=Path(pieces[2]).stem
            if (split,sample) in wanted:
                raw=tar.extractfile(m).read();tree=ET.fromstring(raw);meta={a.get('type'):a.text for a in tree.findall('{*}annotation')}
                folder,row=wanted[(split,sample)];assert meta['inkCreationMethod']=='human'
                assert [t for t in pattern.findall(meta['normalizedLabel']) if not t.isspace()]==row['latex'].split(),sample
                strokes=[[list(map(float,p.split()[:2])) for p in t.text.split(',')] for t in tree.findall('{*}trace')]
                assert strokes==json.loads((folder/row['strokes']).read_text()),sample
                row['inkml_sha256']=hashlib.sha256(raw).hexdigest();row['human_verified']=True;verified+=1
                (folder/(sample+'.inkml')).write_bytes(raw)
                continue
            if split!='train':continue
            raw=tar.extractfile(m).read();seen+=1
            if b'<annotation type="inkCreationMethod">human</annotation>' not in raw:continue
            match=annotation.search(raw)
            if match is None:continue
            label=html.unescape(match[1].decode());tokens=[t for t in pattern.findall(label) if not t.isspace()]
            try:
                parts=tex_to_sym(tokens);syms=[ids[s] for s in parts['sym']];mods=[ids[s] for s in parts['mod']]
            except (KeyError,IndexError,AssertionError):unknown+=1;continue
            add(sets['general'],syms,mods);used+=1
            compact=''.join(tokens)
            if re.search(r'\\(?:i?int|oint|partial)|lim|\\frac\{d',compact):add(sets['calculus'],syms,mods)
            if ('\\begin{matrix}' in tokens and '&' in tokens) or '\\vec' in tokens:add(sets['linear_algebra'],syms,mods)
            if seen%25000==0:print('human training',seen,'used',used,flush=True)
    assert verified==len(wanted),(verified,len(wanted))
    # Write verification separately; frozen evaluation manifests stay byte-identical.
    (ROOT/'build/accuracy-v4/subject-ink-verification.json').write_text(json.dumps([{'id':row['id'],'split':row['split'],'inkml_sha256':row['inkml_sha256'],'human_verified':row['human_verified']} for _,row in wanted.values()],indent=2),encoding='utf-8')
    out={name:{key:dict(counter) if key in ['examples'] else sorted([[list(k) if isinstance(k,tuple) else k,v] for k,v in counter.items()]) for key,counter in count.items()} for name,count in sets.items()}
    out['metadata']=dict(source='Google MathWriting 2024, human train split only',archive_sha256=digest,seen=seen,used=used,out_of_vocabulary=unknown,verified_public_samples=verified,vocabulary_sha256=hashlib.sha256(('\n'.join(vocab)+'\n').encode()).hexdigest(),license='CC-BY-NC-SA-4.0')
    (ROOT/'build/accuracy-v4/training-counts.json').write_text(json.dumps(out,separators=(',',':')),encoding='utf-8')
    for name in ['calculus','linear_algebra']:
        path=ROOT/'formula-packs'/f'{name}.json';pack=json.loads(path.read_text(encoding='utf-8'))
        pack['recognition']['pairs']=[[a,b,n] for (a,b),n in out[name]['pair']]
        pack['recognition']['trainingExamples']=out[name]['examples']['count']
        path.write_text(json.dumps(pack,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Finished counts',out['metadata'],[(name,count['examples']) for name,count in sets.items()],flush=True)
if __name__=='__main__':main()
