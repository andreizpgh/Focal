#!/usr/bin/env python3
"""Install the pinned Silero speech-presence model from a local file or HTTPS."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import tempfile
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
LOCK = ROOT / "models/speech-presence.json"
ASSETS = ROOT / "app/src/main/assets"
REPOSITORY = "ggml-org/whisper-vad"
REVISION = "9ffd54a1e1ee413ddf265af9913beaf518d1639b"
NAME = "ggml-silero-v5.1.2.bin"
SIZE = 885098
SHA256 = "29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf"


def valid_file(path):
    if not path.is_file() or path.stat().st_size != SIZE:
        return False
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for block in iter(lambda: source.read(65536), b""):
            digest.update(block)
    return digest.hexdigest() == SHA256


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--local-file", type=Path, help="Reuse a locally cached copy; verify it before installation")
    args = parser.parse_args()
    lock = json.loads(LOCK.read_text(encoding="utf-8"))
    if (lock.get("repository"), lock.get("revision"), lock.get("filename"), lock.get("bytes"), lock.get("sha256")) != (
        REPOSITORY, REVISION, NAME, SIZE, SHA256
    ):
        raise ValueError("Speech presence model lock does not match the pinned installer")
    if args.local_file is not None and not valid_file(args.local_file):
        raise ValueError("Local speech presence model has an incorrect size or checksum")
    target = ASSETS / NAME
    if valid_file(target):
        print(f"Verified {NAME}: {SIZE:,} bytes, SHA-256 {SHA256}")
        return
    ASSETS.mkdir(parents=True, exist_ok=True)
    path = None
    try:
        with tempfile.NamedTemporaryFile(prefix=".speech-presence-", suffix=".partial", dir=ASSETS, delete=False) as output:
            path = Path(output.name)
            if args.local_file is not None:
                source = args.local_file.open("rb")
            else:
                source = urllib.request.urlopen(
                    f"https://huggingface.co/{REPOSITORY}/resolve/{REVISION}/{NAME}", timeout=120
                )
            with source:
                total = 0
                digest = hashlib.sha256()
                while True:
                    block = source.read(65536)
                    if not block:
                        break
                    total += len(block)
                    if total > SIZE:
                        raise ValueError("Speech presence model exceeds its pinned size")
                    digest.update(block)
                    output.write(block)
                if total != SIZE or digest.hexdigest() != SHA256:
                    raise ValueError("Speech presence model checksum mismatch; refusing to install")
                output.flush()
                os.fsync(output.fileno())
        os.replace(path, target)
    finally:
        if path is not None:
            path.unlink(missing_ok=True)
    print(f"Verified {NAME}: {SIZE:,} bytes, SHA-256 {SHA256}")


if __name__ == "__main__":
    main()
