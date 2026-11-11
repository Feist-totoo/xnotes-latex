"""Conservative MathML tree agreement, not semantic/CAS equivalence or OCR confidence.

Install the pinned renderer: npm ci --prefix tools/formula_metrics
Run: python tools/score_formula_structure.py report.json [...]
Presentation-only grouping/spacing is ignored; symbols, accents, indices,
fractions, matrices, order and mathvariant are retained. No answers are solved.
Unparseable references stay in the denominator and count as failures.
"""
import json,subprocess,xml.etree.ElementTree as ET
from pathlib import Path
root=Path(__file__).resolve().parent
import tempfile

def canonical_mathml(markup):
    if not markup:return None
    xml=ET.fromstring(markup);math=next(x for x in xml.iter() if x.tag.endswith('}math'));sem=list(math)[0]
    expr=list(sem)[0] if sem.tag.endswith('}semantics') else sem
    def canon(n):
        tag=n.tag.split('}')[-1]
        children=[c for c in n if c.tag.split('}')[-1] not in ['annotation','mspace']]
        attrs={k:v for k,v in n.attrib.items() if k in ['mathvariant']}
        kids=[canon(c) for c in children]
        if tag == 'mrow':
            flat=[]
            for kid in kids:
                if kid[0]=='mrow' and not kid[1]:flat.extend(kid[3])
                else:flat.append(kid)
            kids=[]
            for kid in flat:
                if kids and kid[0]=='mn' and kids[-1][0]=='mn' and kid[1]==kids[-1][1]:kids[-1][2]+=kid[2]
                else:kids.append(kid)
        if tag in ['math','mrow','mstyle'] and not attrs and len(kids)==1:return kids[0]
        if tag in ['munder','mover','munderover'] and kids:
            text=''.join(n[0].itertext())
            if text in ['∫','∑','∏','⋃','⋂','lim']:
                tag={'munder':'msub','mover':'msup','munderover':'msubsup'}[tag]
        return [tag,attrs,(n.text or '') if not children else '',kids]
    return canon(expr)

def measure(path):
    data=json.loads(path.read_text(encoding='utf-8'));rows=data if isinstance(data,list) else data['results']
    src=[]
    for row in rows:src.extend([row.get('truth',row.get('reference','')),row.get('latex',(row.get('candidates') or [''])[0])])
    with tempfile.TemporaryDirectory(prefix='formula-score-') as work:
        input=Path(work)/'input.json';output=Path(work)/'output.json'
        input.write_text(json.dumps(src),encoding='utf-8')
        subprocess.run(['node',str(root/'formula_metrics/compare.cjs'),str(input),str(output)],check=True)
        parsed=[canonical_mathml(s) for s in json.loads(output.read_text(encoding='utf-8'))]
    count=0;valid=0
    for i,row in enumerate(rows):
        a,b=parsed[2*i:2*i+2];row['structure_match']=a is not None and b is not None and a==b;count+=row['structure_match'];valid+=a is not None
    dest=path.with_name(path.stem+'-scored.json');dest.write_text(json.dumps(rows,indent=2),encoding='utf-8')
    print(path.name,'structural',count,'/',len(rows),'parseable references',valid)
if __name__=='__main__':
    import sys
    for name in sys.argv[1:]:measure(Path(name))
