import argparse,json,sys,time,runpy,xml.etree.ElementTree as ET
from pathlib import Path
import numpy as np
from PIL import Image,ImageDraw
import torch
from transformers import AutoModel,ViTConfig,ViTModel

ROOT=Path(__file__).resolve().parents[2]
REF=ROOT/'build/references/GryphOne'
sys.path.insert(0,str(REF))
sys.stdout.reconfigure(encoding='utf-8')
torch.set_num_threads(4)
torch.set_num_interop_threads(1)

def model_load():
    import gryph.model,gryph.vocab
    from gryph.utils import build
    original=AutoModel.from_pretrained
    cfg=ViTConfig.from_json_file(str(ROOT/'tools/handwriting/dino-config.json'))
    AutoModel.from_pretrained=lambda *a,**k:ViTModel(cfg,add_pooling_layer=False)
    config=runpy.run_path(str(REF/'configs/mathwriting.py'))['model']
    config['batcher']['lexicon']['load']=str(REF/'alphabet/mathwriting.txt')
    config['losses']=[]
    model=build(config).eval()
    AutoModel.from_pretrained=original
    checkpoint=torch.load(ROOT/'build/accuracy-v4/gryph_e63e36e_mathwriting_sym_mutual_dino_vits8.pth',map_location='cpu',weights_only=True)
    weights=checkpoint.get('state_dict',checkpoint)
    print('checkpoint keys',list(checkpoint)[:5],flush=True)
    print(model.load_state_dict(weights,strict=True),flush=True)
    print('parameters',sum(p.numel() for p in model.parameters()),flush=True)
    return model

def inkml_image(path):
    root=ET.parse(path).getroot()
    strokes=[[tuple(map(float,p.split()[:2])) for p in t.text.split(',')] for t in root.findall('{*}trace')]
    return stroke_image(strokes)

def stroke_image(strokes):
    points=[p for t in strokes for p in t];xs=[p[0] for p in points];ys=[p[1] for p in points]
    l,t=min(xs),min(ys);scale=min(224/max(max(xs)-l,1e-9),224/max(max(ys)-t,1e-9))
    im=Image.new('RGB',(224,224));draw=ImageDraw.Draw(im)
    for trace in strokes:draw.line([((x-l)*scale,(y-t)*scale) for x,y in trace],fill=(1,1,1),width=1)
    return torch.from_numpy(np.asarray(im).copy().transpose(2,0,1)).float().unsqueeze(0)

def raster_image(path):
    # Match Android integer gray conversion and nearest coordinate sampling.
    rgba=np.asarray(Image.open(path).convert('RGBA'),dtype=np.int32)
    a=rgba[:,:,3:4];rgb=(rgba[:,:,:3]*a+255*(255-a))//255
    gray=(299*rgb[:,:,0]+587*rgb[:,:,1]+114*rgb[:,:,2])//1000
    ys,xs=np.where(gray<180)
    gray=gray[ys.min():ys.max()+1,xs.min():xs.max()+1]
    h,w=gray.shape;scale=224/max(w,h);rw=max(1,min(224,int(w*scale)));rh=max(1,min(224,int(h*scale)))
    sampled=gray[np.arange(rh)*h//rh][:,np.arange(rw)*w//rw]
    out=np.zeros((3,224,224),np.float32);out[:,:rh,:rw]=1-sampled.astype(np.float32)[None]/255
    return torch.from_numpy(out).unsqueeze(0)

def skeleton_image(path):
    im=Image.open(path).convert('L');a=np.asarray(im);ys,xs=np.where(a<180)
    im=im.crop((xs.min(),ys.min(),xs.max()+1,ys.max()+1));w,h=im.size;scale=220/max(w,h)
    im=im.resize((max(1,int(w*scale)),max(1,int(h*scale))),Image.Resampling.LANCZOS)
    binary=np.zeros((224,224),np.uint8);binary[2:2+im.height,2:2+im.width]=(np.asarray(im)<200).astype(np.uint8)
    for _ in range(224):
        removed=0
        for phase in [0,1]:
            p=np.pad(binary,1);neighbors=[p[:-2,1:-1],p[:-2,2:],p[1:-1,2:],p[2:,2:],p[2:,1:-1],p[2:,:-2],p[1:-1,:-2],p[:-2,:-2]]
            n=sum(neighbors);transitions=sum((a==0)&(b==1) for a,b in zip(neighbors,neighbors[1:]+neighbors[:1]))
            p2,p3,p4,p5,p6,p7,p8,p9=neighbors
            guard=(p2*p4*p6==0)&(p4*p6*p8==0) if phase==0 else (p2*p4*p8==0)&(p2*p6*p8==0)
            drop=(binary==1)&(n>=2)&(n<=6)&(transitions==1)&guard;removed+=int(drop.sum());binary[drop]=0
        if removed==0:break
    # Restore the same origin/extent convention as original digital pen rendering.
    ys,xs=np.where(binary);crop=binary[ys.min():ys.max()+1,xs.min():xs.max()+1]
    out=np.zeros((3,224,224),np.float32);out[:,:crop.shape[0],:crop.shape[1]]=crop[None]
    return torch.from_numpy(out).unsqueeze(0)

@torch.inference_mode()
def predict(model,img,steps=50,seed=0):
    from gryph.codec import sym_to_tex
    torch.manual_seed(seed)
    start=time.perf_counter();features=model.encoder(img,train=False)['img'];enc=time.perf_counter()-start
    sym=model.handler.MASK.expand(len(img),150);mod=sym.clone()
    decoder=model.decoder.model
    for t in torch.linspace(1,0,steps+1)[1:]:
        out=decoder(sym=sym,mod=mod,img=features,time=t,train=False)
        sym,mod=out['sym'],out['mod']
    result=[]
    for s,m in zip(sym,mod):
        items=model.handler.batcher.reverse(dict(sym=s,mod=m))
        result.append(' '.join(sym_to_tex(**items)['tex']))
    return result,dict(seconds=time.perf_counter()-start,encode_seconds=enc)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('--split',default='valid');p.add_argument('--limit',type=int,default=10);p.add_argument('--steps',type=int,default=50);p.add_argument('--raster',action='store_true');p.add_argument('--output',type=Path)
    args=p.parse_args();model=model_load();folder=ROOT/f'build/accuracy-v3/mathwriting-{args.split}'
    rows=json.loads((folder/'manifest.json').read_text(encoding='utf-8'))[:args.limit];out=[]
    for row in rows:
        path=ROOT/f'build/accuracy-v3/mathwriting-2024-excerpt/{args.split}'/(row['id']+'.inkml')
        image=raster_image(folder/row['image']) if args.raster else inkml_image(path)
        pred,stats=predict(model,image,args.steps)
        item=dict(id=row['id'],truth=row['latex'],latex=pred[0],**stats);out.append(item)
        print(len(out),pred[0],round(stats['seconds'],3),flush=True)
        if args.output:args.output.write_text(json.dumps(out,indent=2),encoding='utf-8')
