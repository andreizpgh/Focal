#!/usr/bin/env python3
"""Fetch the pinned speech model and runtime, verifying every byte.

Reads the lock files models/parakeet-ultra.json (the speech model set: the four Parakeet
Ultra INT8 files and the Silero VAD model) and models/sherpa-onnx.json (the sherpa-onnx
Android AAR). Gradle reads the same files, so the paths, sizes and SHA-256 digests live in
exactly one place.

Each file is accepted only when its size and SHA-256 match the lock. Files that are
already in place and verified are left alone. Otherwise a file is taken, in order, from
a verified local copy (--from / --aar: hard-linked, or copied when linking is not
possible) or downloaded over HTTPS. --from may be repeated: each model file is taken
from the first directory that holds a file of that name. New files are written to a
staging directory on the same filesystem (models/parakeet-ultra.partial/ for the model,
which Gradle never packages; libs/ for the AAR), synced, verified and then renamed into
place, so an interrupted run never leaves a partial file under the final name or in the
packaged model directory. There are no automatic retries.

Examples:
  python3 scripts/fetch-model.py                      # model and runtime (~720 MB)
  python3 scripts/fetch-model.py --runtime-only       # runtime only, enough for CI checks
  python3 scripts/fetch-model.py --from DIR --aar AAR # reuse verified local files
  python3 scripts/fetch-model.py --from ULTRA_DIR --from VAD_DIR  # files in several places
  python3 scripts/fetch-model.py --check              # verify what is in place; no network
"""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import shutil
import sys
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
MODEL_LOCK = ROOT / "models/parakeet-ultra.json"
RUNTIME_LOCK = ROOT / "models/sherpa-onnx.json"
BLOCK = 1024 * 1024


class VerificationError(Exception):
    pass


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        while block := source.read(BLOCK):
            digest.update(block)
    return digest.hexdigest()


def matches(path, entry):
    return (path.is_file() and not path.is_symlink() and path.stat().st_size == entry["bytes"]
            and sha256(path) == entry["sha256"])


def require_match(path, entry, what):
    if not path.is_file() or path.is_symlink():
        raise VerificationError(f"{what}: {path} is missing or not a regular file")
    size = path.stat().st_size
    if size != entry["bytes"]:
        raise VerificationError(f"{what}: {path} has {size:,} bytes, expected {entry['bytes']:,}")
    actual = sha256(path)
    if actual != entry["sha256"]:
        raise VerificationError(f"{what}: {path} has SHA-256 {actual}, expected {entry['sha256']}")


def sync_directory(directory):
    """Makes a rename durable. Windows cannot open a directory for fsync; there it is skipped."""
    if os.name == "nt":
        return
    descriptor = os.open(directory, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def from_local(source, partial, entry, name):
    require_match(source, entry, f"local {name}")
    try:
        os.link(source, partial)
        return "hard-linked"
    except OSError:
        with source.open("rb") as reader, partial.open("wb") as writer:
            shutil.copyfileobj(reader, writer, BLOCK)
            writer.flush()
            os.fsync(writer.fileno())
        return "copied"


def download(url, partial, entry):
    if not url.startswith("https://"):
        raise VerificationError(f"refusing a non-HTTPS URL: {url}")
    digest = hashlib.sha256()
    with urllib.request.urlopen(url, timeout=120) as response, partial.open("wb") as writer:
        while block := response.read(BLOCK):
            writer.write(block)
            digest.update(block)
            if writer.tell() > entry["bytes"]:
                raise VerificationError(f"{url} is larger than the {entry['bytes']:,} bytes in the lock")
        writer.flush()
        os.fsync(writer.fileno())
    if digest.hexdigest() != entry["sha256"]:
        raise VerificationError(f"{url} does not match the SHA-256 in the lock")
    return "downloaded"


def install(target, entry, name, local=None, offline=False, staging=None):
    """Places one verified file at target. Returns a short description of what happened.

    The file is prepared in staging (default: next to target), which must be on the same
    filesystem so that the final rename is atomic.
    """
    if matches(target, entry):
        return "already verified"
    if offline:
        raise VerificationError(f"{target} is missing or does not match the lock")
    staging = staging or target.parent
    target.parent.mkdir(parents=True, exist_ok=True)
    staging.mkdir(parents=True, exist_ok=True)
    partial = staging / (target.name + ".partial")
    partial.unlink(missing_ok=True)
    try:
        how = from_local(local, partial, entry, name) if local else download(entry["url"], partial, entry)
        if how != "hard-linked":  # A hard link is the very file verified above.
            require_match(partial, entry, name)
        os.replace(partial, target)
        sync_directory(target.parent)
        return how
    finally:
        partial.unlink(missing_ok=True)
        if staging != target.parent:
            try:
                staging.rmdir()  # Only once empty; another file may still be staged there.
            except OSError:
                pass


def local_copy(directories, name):
    """The file called name in the first of directories that holds one (else in the first)."""
    if not directories:
        return None
    candidates = [directory / name for directory in directories]
    return next((path for path in candidates if path.exists()), candidates[0])


def load(path):
    with path.open() as source:
        return json.load(source)


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--from", dest="model_sources", type=Path, metavar="DIR", action="append",
                        help="directory that holds verified model files; repeat it for files in several "
                             "places (each file comes from the first directory that has it)")
    parser.add_argument("--aar", type=Path, metavar="FILE", help="an already verified sherpa-onnx AAR to reuse")
    parser.add_argument("--runtime-only", action="store_true",
                        help="fetch only the runtime AAR; debug builds and checks work without the model")
    parser.add_argument("--check", action="store_true", help="only verify the files in place; no network")
    args = parser.parse_args(argv)
    if args.runtime_only and args.model_sources:
        parser.error("--from has no effect with --runtime-only")

    runtime = load(RUNTIME_LOCK)
    model = load(MODEL_LOCK)
    directory = ROOT / model["directory"]
    # Gradle packages the model directory as it is, so partial files are staged beside it.
    model_staging = directory.with_name(directory.name + ".partial")
    jobs = [("sherpa-onnx AAR", ROOT / runtime["path"], runtime, args.aar, None)]
    if not args.runtime_only:
        for entry in model["files"]:
            local = local_copy(args.model_sources, entry["name"])
            jobs.append((entry["name"], directory / entry["name"], entry, local, model_staging))

    failed = False
    for name, target, entry, local, staging in jobs:
        try:
            how = install(target, entry, name, local, offline=args.check, staging=staging)
            print(f"{name}: {how} ({entry['bytes']:,} bytes, SHA-256 {entry['sha256'][:12]}…)")
        except (OSError, http.client.HTTPException, VerificationError) as error:
            # HTTPException covers a connection that drops mid-body (IncompleteRead).
            failed = True
            print(f"{name}: {error}", file=sys.stderr)
    if not args.runtime_only and directory.is_dir():
        # Gradle packages the whole directory, so it must hold exactly the locked files.
        extra = sorted(p.name for p in directory.iterdir() if p.name not in {e["name"] for e in model["files"]})
        if extra:
            failed = True
            print(f"Unexpected files in {model['directory']}: {', '.join(extra)}", file=sys.stderr)
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
