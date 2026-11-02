#!/usr/bin/env python3
"""Fetch one pinned research model within the shared model/corpus byte budget.

This does not change or package the application's default model.
"""
import argparse
import fcntl
import json
from pathlib import Path
import subprocess

from stt_artifacts import digest, fetch, save_json


def main():
    root = Path(__file__).resolve().parent.parent
    lock = json.loads((root / "models/stt-candidates.json").read_text())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("model", choices=lock["models"])
    parser.add_argument("--output", type=Path, default=Path("build/stt"))
    parser.add_argument("--ledger", type=Path, default=Path("build/stt/download-ledger.json"))
    args = parser.parse_args()
    entry = lock["models"][args.model]
    target = args.output / entry["filename"]
    for path in (target, args.ledger):
        if path.resolve().is_relative_to(root):
            checked = subprocess.run(["git", "-C", str(root), "check-ignore", "--quiet", "--", str(path.resolve())], capture_output=True)
            if checked.returncode != 0 or ".git" in path.resolve().relative_to(root).parts:
                parser.error("Models and transfer ledger must stay in ignored paths or outside the repository")
    args.ledger.parent.mkdir(parents=True, exist_ok=True)
    with args.ledger.with_suffix(".lock").open("w") as mutex:
        fcntl.flock(mutex, fcntl.LOCK_EX | fcntl.LOCK_NB)
        # Reuse locally verified model bytes without another transfer reservation.
        if target.is_file() and target.stat().st_size == entry["bytes"] and digest(target.read_bytes()) == entry["sha256"]:
            save_json(target.with_suffix(".provenance.json"), {**entry, "repository": lock["repository"],
                      "revision": lock["revision"], "license": lock["license"]})
            print("Verified existing", entry["filename"])
            return
        if target.exists():
            raise ValueError("Existing model differs from its lock; move it aside before retrying")
        repo, revision = lock["repository"], lock["revision"]
        metadata = json.loads(fetch(f"https://huggingface.co/api/models/{repo}/tree/{revision}",
                                   args.output / "metadata" / f"{revision}.json", 1024 * 1024, args.ledger, kind="metadata"))
        upstream = next(item for item in metadata if item["path"] == entry["filename"])["lfs"]
        if upstream["oid"] != entry["sha256"] or upstream["size"] != entry["bytes"]:
            raise ValueError("Upstream metadata differs from the committed model lock")
        # fetch uses a bounded body and persists a full reservation before HTTP.
        partial = target.with_suffix(target.suffix + ".partial")
        data = fetch(f"https://huggingface.co/{repo}/resolve/{revision}/{entry['filename']}", partial,
                     entry["bytes"] + 1, args.ledger, expected_size=entry["bytes"], kind="model")
        if digest(data) != entry["sha256"]:
            partial.unlink(missing_ok=True)
            partial.with_suffix(partial.suffix + ".sha256").unlink(missing_ok=True)
            raise ValueError("Downloaded model checksum does not match its lock")
        partial.replace(target)
        partial.with_suffix(partial.suffix + ".sha256").replace(target.with_suffix(target.suffix + ".sha256"))
        save_json(target.with_suffix(".provenance.json"), {**entry, "repository": repo, "revision": revision, "license": lock["license"]})
        print("Verified", entry["filename"], entry["bytes"], entry["sha256"])


if __name__ == "__main__":
    main()
