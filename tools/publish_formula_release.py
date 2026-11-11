"""Publish explicit local build artifacts to this fork's GitHub Releases.

Uses the existing Git credential helper without printing/storing credentials.
Requires requests. Defaults to a draft; --publish explicitly publishes after all
uploads. Only pass prepared, reviewed artifact paths. No directory glob uploads.
"""
import argparse
import hashlib
import os
import subprocess
from pathlib import Path
from urllib.parse import quote
import requests

REPO = "Feist-totoo/xnotes-latex"

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--tag", required=True)
    parser.add_argument("--commit", required=True)
    parser.add_argument("--title", required=True)
    parser.add_argument("--body-file", type=Path, required=True)
    parser.add_argument("--publish", action="store_true")
    parser.add_argument("assets", type=Path, nargs="+")
    args = parser.parse_args()
    credential = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
        text=True, capture_output=True, check=True, env=dict(os.environ, GIT_TERMINAL_PROMPT="0", GCM_INTERACTIVE="never"))
    values = dict(line.split("=", 1) for line in credential.stdout.splitlines() if "=" in line)
    session = requests.Session()
    session.headers.update({"Authorization": "Bearer " + values["password"], "Accept": "application/vnd.github+json", "User-Agent": "xnotes-release-builder"})
    api = f"https://api.github.com/repos/{REPO}/releases"
    response = session.get(api + "/tags/" + quote(args.tag, safe=""), timeout=30)
    if response.status_code == 404:
        response = session.post(api, json=dict(tag_name=args.tag, target_commitish=args.commit, name=args.title,
            body=args.body_file.read_text(encoding="utf-8"), draft=True, prerelease=False), timeout=30)
    response.raise_for_status()
    release = response.json()
    if not release["draft"]:
        raise RuntimeError("Refusing to overwrite a published release; use a new version")
    existing = {asset["name"]: asset for asset in release["assets"]}
    for path in args.assets:
        if path.suffix not in {".apk", ".xformula", ".json", ".txt"}:
            raise ValueError(f"Unexpected release asset type: {path.name}")
        with path.open("rb") as stream:
            digest = hashlib.file_digest(stream, "sha256").hexdigest()
        previous = existing.get(path.name)
        if previous:
            if previous.get("digest") == "sha256:" + digest and previous["size"] == path.stat().st_size:
                print("Already verified", path.name, flush=True); continue
            raise RuntimeError(f"Existing draft asset differs: {path.name}")
        url = release["upload_url"].split("{")[0] + "?name=" + quote(path.name)
        if not url.startswith("https://uploads.github.com/"):
            raise RuntimeError("Unexpected upload host")
        print("Uploading", path.name, path.stat().st_size, flush=True)
        with path.open("rb") as stream:
            uploaded = session.post(url, data=stream, headers={"Content-Type": "application/octet-stream"}, timeout=(30, 1800))
        uploaded.raise_for_status()
        asset = uploaded.json()
        if asset["size"] != path.stat().st_size or asset.get("digest") != "sha256:" + digest:
            raise RuntimeError(f"GitHub upload digest mismatch: {path.name}")
        print("Verified", path.name, digest, flush=True)
    if args.publish:
        response = session.patch(api + "/" + str(release["id"]), json={"draft": False, "make_latest": "true"}, timeout=30)
        response.raise_for_status()
        print("Published", response.json()["html_url"], flush=True)
    else:
        print("Draft prepared", release["html_url"], flush=True)

if __name__ == "__main__": main()
