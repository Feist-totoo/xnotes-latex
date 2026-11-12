import argparse,json,time,sys,xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np,onnxruntime as ort
from probe_gryph import ROOT,REF,inkml_image,raster_image,stroke_image,skeleton_image

class RNG:
    def __init__(self,seed):self.state=seed
    def next(self):
        x=self.state;x^=(x<<13)&0xffffffff;x^=x>>17;x^=(x<<5)&0xffffffff;self.state=x
        return x/4294967296.0

class Engine:
    def __init__(self,quant=False):
        opt=ort.SessionOptions();opt.intra_op_num_threads=4;opt.inter_op_num_threads=1;opt.log_severity_level=3
        suffix='-q8' if quant else '';folder=ROOT/'build/accuracy-v4/onnx'
        self.enc=ort.InferenceSession(str(folder/f'encoder{suffix}.onnx'),opt,providers=['CPUExecutionProvider'])
        self.dec=ort.InferenceSession(str(folder/f'decoder{suffix}.onnx'),opt,providers=['CPUExecutionProvider'])
        self.vocab=(REF/'alphabet/mathwriting.txt').read_text(encoding='utf-8').splitlines()
    def tex(self,sym,mod):
        out=[]
        for s,m in zip(sym,mod):
            if s==0 or m==0:break
            if s==1 or m==1:return ''
            token=self.vocab[s];modifier=self.vocab[m]
            out.extend(ch for ch in modifier if ch in '{^_')
            out.append(token);out.extend(ch for ch in modifier if ch=='}')
        return ' '.join(out)
    def recognize(self,img,steps=20,seeds=(1,),torch_rng=False,return_logits=False,rescore=False):
        start=time.perf_counter();keys,values=self.enc.run(None,{'pixels':img.numpy() if hasattr(img,'numpy') else img});enc=time.perf_counter()-start
        n=len(seeds);sym=np.ones((n,150),np.int64);mod=sym.copy();rngs=[RNG(seed) for seed in seeds]
        if torch_rng:
            import torch;torch.manual_seed(0)
        for i in range(steps):
            sl,ml=self.dec.run(None,{'symbols':sym,'modifiers':mod,'keys':keys,'values':values})
            sym=np.where(sym==1,sl.argmax(-1),sym);mod=np.where(mod==1,ml.argmax(-1),mod)
            t=1-(i+1)/steps
            if torch_rng:
                t=float(torch.linspace(1,0,steps+1)[i+1])
                masks=[torch.rand(n,150).numpy()<t,torch.rand(n,150).numpy()<t]
            else:
                masks=[]
                for _ in range(2):masks.append(np.array([[rng.next()<t for _ in range(150)] for rng in rngs]))
            if t>0:
                sym=np.where(masks[0],1,sym);mod=np.where(masks[1],1,mod)
        calibrated=np.zeros(n,np.float64)
        ends=[next((i for i in range(150) if sym[k,i]==0 or mod[k,i]==0),150) for k in range(n)]
        diverse=len({(tuple(sym[k,:ends[k]]),tuple(mod[k,:ends[k]])) for k in range(n)})>1
        if rescore and diverse:
            # A token cannot score its own already-visible identity. Hide one quarter
            # of positions at a time, then score the original candidates at those positions.
            for offset in range(4):
                mask=np.arange(150)%4==offset
                hidden_sym=np.where(mask[None,:],1,sym);hidden_mod=np.where(mask[None,:],1,mod)
                hs,hm=self.dec.run(None,{'symbols':hidden_sym,'modifiers':hidden_mod,'keys':keys,'values':values})
                for k in range(n):
                    positions=np.arange(min(ends[k]+1,150));positions=positions[positions%4==offset]
                    for logits,ids in [(hs[k],sym[k]),(hm[k],mod[k])]:
                        a=logits[positions];mx=a.max(-1);lse=mx+np.log(np.exp(a-mx[:,None]).sum(-1))
                        calibrated[k]+=float((a[np.arange(len(a)),ids[positions]]-lse).sum())
            calibrated/=2*np.minimum(np.array(ends)+1,150)
        candidates=[]
        for k in range(n):
            end=next((i for i in range(150) if sym[k,i]==0 or mod[k,i]==0),150)
            if end in (0,150):continue
            scores=[]
            for logits,ids in [(sl[k],sym[k]),(ml[k],mod[k])]:
                a=logits[:min(end+1,150)];mx=a.max(-1);lse=mx+np.log(np.exp(a-mx[:,None]).sum(-1))
                scores.append(a[np.arange(len(a)),ids[:len(a)]]-lse)
            candidates.append(dict(latex=self.tex(sym[k],mod[k]),visual_score=float(calibrated[k]) if rescore else float(np.concatenate(scores).mean()),self_score=float(np.concatenate(scores).mean()),seed=seeds[k],symbols=sym[k,:end].tolist(),modifiers=mod[k,:end].tolist()))
        candidates.sort(key=lambda c:c['visual_score'],reverse=True)
        return candidates,dict(seconds=time.perf_counter()-start,encode_seconds=enc)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--quant',action='store_true');p.add_argument('--data',type=Path);p.add_argument('--split',default='valid');p.add_argument('--limit',type=int,default=100);p.add_argument('--steps',type=int,default=20);p.add_argument('--candidates',type=int,default=1);p.add_argument('--torch-rng',action='store_true');p.add_argument('--raster',action='store_true');p.add_argument('--rescore',action='store_true');p.add_argument('--skeleton',action='store_true');p.add_argument('--output',type=Path,required=True)
    args=p.parse_args();engine=Engine(args.quant);folder=args.data or ROOT/f'build/accuracy-v3/mathwriting-{args.split}';rows=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))[:args.limit]
    results=[]
    for row in rows:
        path=ROOT/f'build/accuracy-v3/mathwriting-2024-excerpt/{args.split}'/(row['id']+'.inkml')
        img=skeleton_image(folder/row['image']) if args.skeleton else raster_image(folder/row['image']) if args.raster else stroke_image(json.loads((folder/row['strokes']).read_text())) if 'strokes' in row else inkml_image(path)
        candidates,stats=engine.recognize(img,args.steps,seeds=(1,17,37,101)[:args.candidates],torch_rng=args.torch_rng,rescore=args.rescore)
        results.append(dict(id=row['id'],truth=row['latex'],latex=candidates[0]['latex'] if candidates else '',candidates=candidates,**stats))
        args.output.write_text(json.dumps(results,indent=2),encoding='utf-8')
        if len(results)%10==0:print(len(results),round(stats['seconds'],3),flush=True)
