"""Copy only live ZIP entries from an incremental APK into a NEW unsigned APK.

Does not delete or modify the Gradle output. Afterwards use Android zipalign and
apksigner with the original build's signing key. All entry bytes are preserved.
"""
import argparse
import copy
import shutil
import zipfile
from pathlib import Path

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    if args.source.resolve() == args.destination.resolve() or args.destination.exists():
        raise ValueError("Destination must be a new file")
    args.destination.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(args.destination, "x") as destination:
        assert len(source.namelist()) == len(set(source.namelist()))
        for original in source.infolist():
            entry = copy.copy(original)
            entry.extra = b""  # zipalign will produce alignment for the new offsets
            with source.open(original) as src, destination.open(entry, "w") as dst:
                shutil.copyfileobj(src, dst, 1024 * 1024)
    with zipfile.ZipFile(args.source) as source, zipfile.ZipFile(args.destination) as destination:
        expected = {x.filename: (x.CRC, x.file_size, x.compress_type) for x in source.infolist()}
        actual = {x.filename: (x.CRC, x.file_size, x.compress_type) for x in destination.infolist()}
        assert expected == actual and destination.testzip() is None
    print("Verified live entries:", args.source.stat().st_size, "->", args.destination.stat().st_size)

if __name__ == "__main__": main()
