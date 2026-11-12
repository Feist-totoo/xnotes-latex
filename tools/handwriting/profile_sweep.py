"""Compare priors on validation data only; never tune on confirmation/test results."""
import argparse,sys,json,subprocess,tempfile
from pathlib import Path
import numpy as np
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'tools/handwriting'))
from score_candidates import route,canonical_mathml
packs=[json.loads((ROOT/'formula-packs'/f'{n}.json').read_text(encoding='utf-8')) for n in ['calculus','linear_algebra']]
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('report',type=Path)
parser.add_argument('--counts',type=Path,default=ROOT/'build/accuracy-v4/training-counts.json')
parser.add_argument('--output',type=Path,required=True)
args=parser.parse_args()
counts=json.loads(args.counts.read_text(encoding='utf-8'))
tables={}
for name in ['calculus','linear_algebra']:
 tables[name]={}
 for kind in ['pair','sym2','mod2']:
  c=np.zeros((287,287),np.float64)
  for (a,b),n in counts[name][kind]:c[a,b]=n
  tables[name][kind]=np.log((c+.25)/(c.sum(-1,keepdims=True)+.25*287))
input=args.report.resolve()
rows=json.loads(input.read_text(encoding='utf-8'))
sources=list(dict.fromkeys(s for r in rows for s in [r['truth']]+[c['latex'] for c in r['candidates']]))
with tempfile.TemporaryDirectory() as work:
 a=Path(work)/'in.json';b=Path(work)/'out.json';a.write_text(json.dumps(sources))
 subprocess.run(['node',str(ROOT/'tools/formula_metrics/compare.cjs'),str(a),str(b)],check=True)
 parsed=dict(zip(sources,[canonical_mathml(s) for s in json.loads(b.read_text(encoding='utf-8'))]))
prepared=[]
for r in rows:
 cs=sorted(r['candidates'],key=lambda c:c['visual_score'],reverse=True)
 subject=route(cs[0]['latex'],packs) if cs else None
 fs=[]
 for c in cs:
  s=c['symbols'];m=c['modifiers'];v=[]
  for kind in ['pair','sym2','mod2']:
   x,y=(s,m) if kind=='pair' else ([0]+s,s+[0]) if kind=='sym2' else ([0]+m,m+[0])
   v.append(float(np.mean(tables[subject][kind][x,y])) if subject and x else 0.)
  fs.append(v)
 good=[parsed[r['truth']] is not None and parsed[r['truth']]==parsed[c['latex']] for c in cs]
 prepared.append((cs,np.array(fs),good))
base=sum(bool(g) and g[0] for _,_,g in prepared)
results=[]
for label,weights in [('pair',(1,0,0)),('symbols',(0,1,0)),('relations',(0,0,1)),('sequence',(0,.5,.5)),('all',(1/3,1/3,1/3))]:
 for strength in [.01,.03,.05,.1,.2]:
  for gap in [.01,.03,.06,.1]:
   correct=fixes=regressions=changes=0
   for cs,fs,good in prepared:
    if not cs:continue
    score=np.array([c['visual_score'] for c in cs]);eligible=score>=score[0]-gap
    score=score+strength*(fs@np.array(weights));score[~eligible]=-np.inf;k=int(score.argmax())
    correct+=good[k];fixes+=good[k] and not good[0];regressions+=good[0] and not good[k];changes+=k!=0
   results.append(dict(kind=label,strength=strength,gap=gap,correct=correct,fixes=fixes,regressions=regressions,changes=changes))
results.sort(key=lambda r:(-r['correct'],r['regressions'],r['strength'],r['gap']))
out={'input':str(input),'samples':len(rows),'baseline':base,'oracle':sum(any(g) for _,_,g in prepared),'rankings':results}
args.output.write_text(json.dumps(out,indent=2),encoding='utf-8')
print(json.dumps({k:v for k,v in out.items() if k!='rankings'}));print(json.dumps(results[:15],indent=2))
