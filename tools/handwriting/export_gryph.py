from pathlib import Path
import torch, numpy as np
from probe_gryph import model_load, ROOT, inkml_image

class Encoder(torch.nn.Module):
    def __init__(self,model):
        super().__init__();self.encoder=model.encoder
        self.blocks=torch.nn.ModuleList([b.att2 for b in model.decoder.model.decoder.model])
    def forward(self,pixels):
        features=self.encoder(pixels,train=False)['img']
        keys=[];values=[]
        for b in self.blocks:
            keys.append(b.k(features).reshape(1,785,8,48).transpose(1,2))
            values.append(b.v(features).reshape(1,785,8,48).transpose(1,2))
        return torch.stack(keys),torch.stack(values)

class Decoder(torch.nn.Module):
    def __init__(self,model):
        super().__init__();self.updater=model.decoder.model.updater
        self.blocks=model.decoder.model.decoder.model
        self.register_buffer('position',self.updater.pos(torch.zeros(1,150,384)).clone())
    def forward(self,symbols,modifiers,keys,values):
        x=self.updater.emb(symbols)+self.updater.emb(modifiers)+self.position
        for i,b in enumerate(self.blocks):
            q=b.att1.q(x).reshape(-1,150,8,48).transpose(1,2)
            k=b.att1.k(x).reshape(-1,150,8,48).transpose(1,2)
            v=b.att1.v(x).reshape(-1,150,8,48).transpose(1,2)
            self_att=(q@(k.transpose(-1,-2)/(48**.5))).softmax(-1)@v
            x=x+b.att1.w(self_att.transpose(1,2).flatten(-2))
            q=b.att2.q(x).reshape(-1,150,8,48).transpose(1,2)
            att=(q@(keys[i].transpose(-1,-2)/(48**.5))).softmax(-1)@values[i]
            y=b.att2.w(att.transpose(1,2).flatten(-2))
            x=x+y;x=x+b.feed(x)
        return self.updater.sym(x),self.updater.mod(x)

if __name__=='__main__':
    import onnxruntime as ort
    from onnxruntime.quantization import quantize_dynamic,QuantType
    m=model_load();enc=Encoder(m).eval();dec=Decoder(m).eval();folder=ROOT/'build/accuracy-v4/onnx';folder.mkdir(exist_ok=True)
    img=inkml_image(ROOT/'build/accuracy-v3/mathwriting-2024-excerpt/valid/00000b332dcd6fe5.inkml')
    syms=torch.full((1,150),1,dtype=torch.long)
    with torch.inference_mode():
        keys,vals=enc(img)
        ref=m.decoder.model.updater.predict(**m.decoder.model.decoder.forward(**m.decoder.model.updater.feature(sym=syms,mod=syms,img=m.encoder(img,train=False)['img'])))
        actual=dec(syms,syms,keys,vals)
        print('wrapper max error',max((a-ref[k]).abs().max().item() for a,k in zip(actual,['sym','mod'])),flush=True)
        torch.onnx.export(enc,(img,),str(folder/'encoder.onnx'),input_names=['pixels'],output_names=['keys','values'],opset_version=17,dynamo=False)
        torch.onnx.export(dec,(syms,syms,keys,vals),str(folder/'decoder.onnx'),input_names=['symbols','modifiers','keys','values'],output_names=['symbol_logits','modifier_logits'],dynamic_axes={'symbols':{0:'batch'},'modifiers':{0:'batch'},'symbol_logits':{0:'batch'},'modifier_logits':{0:'batch'}},opset_version=17,dynamo=False)
    opt=ort.SessionOptions();opt.intra_op_num_threads=4;opt.inter_op_num_threads=1
    e=ort.InferenceSession(str(folder/'encoder.onnx'),opt,providers=['CPUExecutionProvider']);d=ort.InferenceSession(str(folder/'decoder.onnx'),opt,providers=['CPUExecutionProvider'])
    k,v=e.run(None,{'pixels':img.numpy()});out=d.run(None,{'symbols':syms.numpy(),'modifiers':syms.numpy(),'keys':k,'values':v})
    print('ONNX max errors',float(abs(k-keys.numpy()).max()),[float(abs(a-b.numpy()).max()) for a,b in zip(out,actual)],flush=True)
    for name in ['encoder','decoder']:
        quantize_dynamic(str(folder/(name+'.onnx')),str(folder/(name+'-q8.onnx')),weight_type=QuantType.QInt8,per_channel=True,op_types_to_quantize=['MatMul','Gemm'])
    print('sizes',[(p.name,p.stat().st_size) for p in folder.iterdir()],flush=True)
