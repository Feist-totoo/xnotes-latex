"""Frozen candidate ranking with exact app routing; no ground-truth subject routing.

Scores all predictions with the unchanged conservative MathML metric, including
failures. Candidate oracle is diagnostic only, never an app accuracy claim.
"""
import argparse,json,re,subprocess,tempfile,sys
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools'))
from score_formula_structure import canonical_mathml

def tokens(source):
    raw=re.findall(r'\\[A-Za-z]+|\\.|[^\s]',source);out=[];i=0
    while i<len(raw):
        if i+2<len(raw) and raw[i]=='{' and raw[i+2]=='}':out.append(raw[i+1]);i+=3
        else:out.append(raw[i]);i+=1
    return out

def route(latex,packs):
    source=tokens(latex);scores=[]
    for p in packs:
        score=0
        for signal,weight in p['signals'].items():
            wanted=tokens(signal)
            if any(source[i:i+len(wanted)]==wanted for i in range(len(source)-len(wanted)+1)):score+=weight
        if score>=4:scores.append((-score,p['id']))
    return sorted(scores)[0][1] if scores else None

def tables(packs):
    out={}
    for p in packs:
        c=np.zeros((287,287),np.float64)
        for s,m,n in p['recognition']['pairs']:c[s,m]=n
        out[p['id']]=np.log((c+.25)/(c.sum(-1,keepdims=True)+.25*287))
    return out

def rerank(candidates,packs):
    ordered=sorted(candidates,key=lambda c:c['visual_score'],reverse=True)
    if not ordered:return [],None
    subject=route(ordered[0]['latex'],packs)
    if not subject:return ordered,None
    p=next(p for p in packs if p['id']==subject)['recognition'];table=tables(packs)[subject]
    best=ordered[0]['visual_score']
    def score(c):
        if best-c['visual_score']>p['maxVisualGap']:return -float('inf')
        prior=float(np.mean(table[c['symbols'],c['modifiers']])) if c['symbols'] else -20.
        return c['visual_score']+p['strength']*prior
    return sorted(ordered,key=score,reverse=True),subject

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('report',type=Path);parser.add_argument('--manifest',type=Path)
    a=parser.parse_args();rows=json.loads(a.report.read_text(encoding='utf-8'))
    packs=[json.loads((ROOT/'formula-packs'/f'{n}.json').read_text(encoding='utf-8')) for n in ['calculus','linear_algebra']]
    sources=list(dict.fromkeys(s for r in rows for s in [r['truth']]+[c['latex'] for c in r['candidates']]))
    with tempfile.TemporaryDirectory() as folder:
        source=Path(folder)/'in.json';dest=Path(folder)/'out.json';source.write_text(json.dumps(sources))
        subprocess.run(['node',str(ROOT/'tools/formula_metrics/compare.cjs'),str(source),str(dest)],check=True)
        parsed=dict(zip(sources,[canonical_mathml(s) for s in json.loads(dest.read_text(encoding='utf-8'))]))
    summary=dict(samples=len(rows),off=0,on=0,oracle=0,fixed=0,regressed=0,changed=0,routed=0)
    subjects={r['id']:r['subject'] for r in json.loads(a.manifest.read_text())} if a.manifest else {}
    detail=[]
    for r in rows:
        candidates,subject=rerank(r['candidates'],packs)
        correct=lambda s:parsed[r['truth']] is not None and parsed[r['truth']]==parsed.get(s)
        off=correct(r['latex']);on=correct(candidates[0]['latex'] if candidates else '')
        summary['off']+=off;summary['on']+=on;summary['fixed']+=on and not off;summary['regressed']+=off and not on
        summary['oracle']+=any(correct(c['latex']) for c in candidates);summary['routed']+=subject is not None
        summary['changed']+=bool(candidates) and candidates[0]['latex']!=r['latex']
        detail.append(dict(id=r['id'],truth=r['truth'],latex=candidates[0]['latex'] if candidates else '',base=r['latex'],off=off,on=on,subject=subjects.get(r['id']),routed=subject,seconds=r.get('seconds'),elapsed_ms=r.get('elapsed_ms')))
    for subject in set(subjects.values()):summary[subject]={k:sum(r[k] for r in detail if r['subject']==subject) for k in ['off','on']}
    a.report.with_name(a.report.stem+'-ablation.json').write_text(json.dumps(dict(summary=summary,results=detail),indent=2),encoding='utf-8')
    print(json.dumps(summary),flush=True)

if __name__=='__main__':main()
