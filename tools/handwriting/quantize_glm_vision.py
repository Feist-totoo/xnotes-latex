"""Requantize a pinned Q8 vision projector using upstream gguf's block codec.

Developer-only conversion. Does not modify llama.cpp. Measure recognition after
conversion: byte reduction alone is not evidence of preserved model quality.
"""
import argparse,hashlib,json,sys
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'build/references/llama.cpp/gguf-py'))
import gguf

def main():
    p=argparse.ArgumentParser(description=__doc__)
    p.add_argument('source',type=Path);p.add_argument('output',type=Path)
    p.add_argument('--dtype',choices=['Q4_0','Q5_0'],default='Q5_0')
    a=p.parse_args()
    with a.source.open('rb') as f:source_sha=hashlib.file_digest(f,'sha256').hexdigest()
    assert source_sha=='9c4b58e33e316ed142eb5dcb41abec3844d3e6e5dc361ffb782c3fa9d175141f'
    if a.output.exists():raise ValueError('Refusing to overwrite an existing model')
    reader=gguf.GGUFReader(a.source)
    assert reader.fields['general.architecture'].contents()=='clip'
    writer=gguf.GGUFWriter(a.output,'clip',use_temp_file=True)
    for name,field in reader.fields.items():
        if name.startswith('GGUF.') or name=='general.architecture':continue
        writer.add_key_value(name,field.contents(),field.types[0],field.types[1] if len(field.types)>1 else None)
    dtype=getattr(gguf.GGMLQuantizationType,a.dtype);changed=[]
    for tensor in reader.tensors:
        value=tensor.data
        target=tensor.tensor_type
        if target==gguf.GGMLQuantizationType.Q8_0 and len(tensor.shape)>=2:
            value=gguf.quants.quantize(gguf.quants.dequantize(value,target),dtype)
            target=dtype;changed.append(tensor.name)
        writer.add_tensor(tensor.name,value,raw_dtype=target)
    writer.write_header_to_file();writer.write_kv_data_to_file();writer.write_tensors_to_file();writer.close()
    converted=gguf.GGUFReader(a.output)
    assert [(t.name,t.shape.tolist()) for t in converted.tensors]==[(t.name,t.shape.tolist()) for t in reader.tensors]
    with a.output.open('rb') as f:sha=hashlib.file_digest(f,'sha256').hexdigest()
    report=dict(sourceSha256=source_sha,sourceBytes=a.source.stat().st_size,outputBytes=a.output.stat().st_size,
        outputSha256=sha,targetQuantization=a.dtype,changedTensors=changed,
        sourceRuntimeRevision='2d973636e292ee6f75fadcf08d29cb33511f509f',qualityValidated=False)
    a.output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='changedTensors'}),flush=True)

if __name__=='__main__':main()
