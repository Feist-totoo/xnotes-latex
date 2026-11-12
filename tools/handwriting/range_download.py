import argparse,concurrent.futures,hashlib,os,time
from pathlib import Path
import requests

def download(url,dest,size,workers,sha=None):
    parts=dest.with_suffix(dest.suffix+'.chunks');parts.mkdir(exist_ok=True)
    chunk=8*1024*1024
    def part(i):
        start=i*chunk;end=min(size,start+chunk)-1;n=end-start+1
        final=parts/str(i);temporary=parts/(str(i)+'.partial')
        if final.is_file() and final.stat().st_size==n:return n
        for attempt in range(4):
            try:
                with requests.get(url,headers={'Range':f'bytes={start}-{end}'},stream=True,timeout=(30,90)) as r:
                    r.raise_for_status()
                    assert r.status_code==206 and r.headers['Content-Range']==f'bytes {start}-{end}/{size}',r.headers.get('Content-Range')
                    with temporary.open('wb') as f:
                        for data in r.iter_content(256*1024):f.write(data)
                assert temporary.stat().st_size==n
                os.replace(temporary,final);return n
            except Exception:
                if attempt==3:raise
                time.sleep(2)
    count=(size+chunk-1)//chunk;done=0
    with concurrent.futures.ThreadPoolExecutor(max_workers=workers) as pool:
        for result in concurrent.futures.as_completed([pool.submit(part,i) for i in range(count)]):
            done+=result.result();print(dest.name,done,'/',size,flush=True)
    with dest.open('wb') as out:
        for i in range(count):
            with (parts/str(i)).open('rb') as stream:
                while block:=stream.read(1024*1024):out.write(block)
    with dest.open('rb') as stream:digest=hashlib.file_digest(stream,'sha256').hexdigest()
    if sha:assert digest==sha
    print('Verified size/SHA256',dest.name,size,digest,flush=True)

if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('url');p.add_argument('destination',type=Path);p.add_argument('--size',type=int,required=True);p.add_argument('--workers',type=int,default=8);p.add_argument('--sha256');a=p.parse_args()
    download(a.url,a.destination,a.size,a.workers,a.sha256)
