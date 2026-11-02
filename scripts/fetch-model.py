#!/usr/bin/env python3
"""Fetch the upstream GGML base Q5_1 model with TLS and LFS SHA-256 verification.

The resolved immutable revision and digest travel inside the APK as provenance.
Use --revision <commit> to reproduce a previous artifact exactly.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
REPO = "ggerganov/whisper.cpp"
NAME = "ggml-base-q5_1.bin"
ASSETS = ROOT / "app/src/main/assets"


def metadata(url):
    with urllib.request.urlopen(url, timeout=60) as response:
        return json.load(response)


def digest(path):
    result = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(1024 * 1024), b""):
            result.update(block)
    return result.hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--revision", help="Immutable Hugging Face commit from an earlier model-provenance.json")
    args = parser.parse_args()
    revision = args.revision or metadata(f"https://huggingface.co/api/models/{REPO}")["sha"]
    if not re.fullmatch(r"[a-f0-9]{40}", revision):
        raise ValueError("Expected an immutable upstream commit")
    files = metadata(f"https://huggingface.co/api/models/{REPO}/tree/{revision}")
    info = next(item for item in files if item["path"] == NAME)
    expected = info["lfs"]["oid"]
    size = info["lfs"]["size"]
    if not re.fullmatch(r"[a-f0-9]{64}", expected) or not 10_000_000 < size < 200_000_000:
        raise ValueError("Unexpected model metadata")
    ASSETS.mkdir(parents=True, exist_ok=True)
    target = ASSETS / NAME
    if not target.exists() or target.stat().st_size != size or digest(target) != expected:
        partial = target.with_suffix(".partial")
        try:
            url = f"https://huggingface.co/{REPO}/resolve/{revision}/{NAME}"
            with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as output:
                while block := response.read(1024 * 1024):
                    output.write(block)
                    if output.tell() > size:
                        raise ValueError("Model exceeds declared size")
                output.flush()
                os.fsync(output.fileno())
            if partial.stat().st_size != size or digest(partial) != expected:
                raise ValueError("Model checksum mismatch; refusing to use this download")
            partial.replace(target)
        finally:
            partial.unlink(missing_ok=True)
    provenance = {"repository": REPO, "revision": revision, "filename": NAME,
                  "sha256": expected, "bytes": size, "license": "MIT"}
    (ASSETS / "model-provenance.json").write_text(json.dumps(provenance, indent=2) + "\n")
    print(f"Verified {NAME}: {size:,} bytes, SHA-256 {expected}, upstream {revision}")


if __name__ == "__main__":
    main()
