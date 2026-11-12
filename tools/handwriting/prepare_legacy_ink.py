"""Restore only known InkML members from the checksum-pinned public excerpt."""
import hashlib,re,tarfile
from pathlib import Path

ROOT=Path(__file__).resolve().parents[2]
work=ROOT/'build/accuracy-v3'
archive=work/'mathwriting-excerpt.tgz'
with archive.open('rb') as stream:
    assert hashlib.file_digest(stream,'sha256').hexdigest()=='cba038def001480a89962b25cb20a60df4c4145e94c86ef9d2af65f192cb82bc'
with tarfile.open(archive,'r:gz') as source:
    for member in source:
        parts=Path(member.name).parts
        if not member.isfile() or len(parts)!=3 or parts[1] not in ['train','valid','test']:continue
        if not re.fullmatch(r'[a-f0-9]+\.inkml',parts[2]):continue
        folder=work/'mathwriting-2024-excerpt'/parts[1];folder.mkdir(parents=True,exist_ok=True)
        (folder/parts[2]).write_bytes(source.extractfile(member).read())
