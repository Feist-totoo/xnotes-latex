"""Remove GLM-OCR's unused NextN prediction layer without changing active weights.

Pinned llama.cpp's glm4 graph runs block_count - nextn_predict_layers blocks.
Changing (17, 1) to (16, 0) preserves all 16 active blocks and removes only the
unused auxiliary layer. Raw-byte parity is checked for every retained tensor.
"""
import argparse,hashlib,json,sys
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
sys.path.insert(0,str(ROOT/'build/references/llama.cpp/gguf-py'))
import gguf

def main():
    p=argparse.ArgumentParser(description=__doc__);p.add_argument('source',type=Path);p.add_argument('output',type=Path);a=p.parse_args()
    if a.output.exists():raise ValueError('Refusing to overwrite an existing model')
    r=gguf.GGUFReader(a.source)
    assert r.fields['general.architecture'].contents()=='glm4'
    assert r.fields['glm4.block_count'].contents()==17 and r.fields['glm4.nextn_predict_layers'].contents()==1
    w=gguf.GGUFWriter(a.output,'glm4',use_temp_file=True)
    for name,field in r.fields.items():
        if name.startswith('GGUF.') or name=='general.architecture':continue
        value=16 if name=='glm4.block_count' else 0 if name=='glm4.nextn_predict_layers' else field.contents()
        w.add_key_value(name,value,field.types[0],field.types[1] if len(field.types)>1 else None)
    kept=[];removed=[]
    for t in r.tensors:
        if t.name.startswith('blk.16.'):removed.append(t.name);continue
        w.add_tensor(t.name,t.data,raw_dtype=t.tensor_type);kept.append(t)
    assert len(removed)==16
    w.write_header_to_file();w.write_kv_data_to_file();w.write_tensors_to_file();w.close()
    result=gguf.GGUFReader(a.output)
    assert len(result.tensors)==len(kept)
    for before,after in zip(kept,result.tensors):
        assert before.name==after.name and before.tensor_type==after.tensor_type and (before.shape==after.shape).all()
        assert hashlib.sha256(before.data).digest()==hashlib.sha256(after.data).digest()
    def sha(path):
        with path.open('rb') as f:return hashlib.file_digest(f,'sha256').hexdigest()
    report=dict(sourceSha256=sha(a.source),outputSha256=sha(a.output),sourceBytes=a.source.stat().st_size,
        outputBytes=a.output.stat().st_size,removedTensors=removed,retainedTensorsByteIdentical=len(kept),
        activeBlocksBefore=16,activeBlocksAfter=16)
    a.output.with_suffix('.json').write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
    print(json.dumps({k:v for k,v in report.items() if k!='removedTensors'}),flush=True)

if __name__=='__main__':main()
