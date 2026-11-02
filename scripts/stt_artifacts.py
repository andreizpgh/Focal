"""Bounded, verified research downloads with durable conservative accounting."""
import hashlib
import json
import os
import urllib.request

LIMIT = 1_000_000_000


def digest(data):
    return hashlib.sha256(data).hexdigest()


def save_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix(path.suffix + ".tmp")
    with temporary.open("w") as stream:
        stream.write(json.dumps(value, indent=2, ensure_ascii=False) + "\n")
        stream.flush()
        os.fsync(stream.fileno())
    temporary.replace(path)
    descriptor = os.open(path.parent, os.O_RDONLY)
    try:
        os.fsync(descriptor)
    finally:
        os.close(descriptor)


def reserve(ledger, name, size, kind="corpus"):
    # The caller holds the exclusive ledger lock for the whole fetch operation.
    data = json.loads(ledger.read_text()) if ledger.exists() else dict(limit=LIMIT, reservations=[])
    if not 0 < size <= LIMIT or sum(item["bytes"] for item in data["reservations"]) + size > min(data["limit"], LIMIT):
        raise ValueError("Shared model/corpus transfer budget exhausted")
    data["reservations"].append(dict(name=name, bytes=size, kind=kind))
    save_json(ledger, data)


def fetch(url, target, limit, ledger, byte_range=None, total=None, expected_size=None, kind="corpus"):
    if target.exists():
        checksum = target.with_suffix(target.suffix + ".sha256")
        data = target.read_bytes()
        if not checksum.exists() or digest(data) != checksum.read_text().strip():
            raise ValueError("Cached artifact changed: " + str(target))
        if len(data) > limit or (byte_range is not None and len(data) != limit) or (expected_size is not None and len(data) != expected_size):
            raise ValueError("Cached artifact length does not match requested source")
        return data
    reserve(ledger, target.name, limit, kind)
    headers = {} if byte_range is None else {"Range": f"bytes={byte_range[0]}-{byte_range[1]}"}
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=90) as response:
        if byte_range is not None:
            expected = f"bytes {byte_range[0]}-{byte_range[1]}/{total}"
            if response.status != 206 or response.headers.get("Content-Range") != expected:
                raise ValueError("Server did not honor the exact bounded byte range")
        declared = response.headers.get("Content-Length")
        if declared is not None and int(declared) > limit:
            raise ValueError("Response exceeds reserved bytes")
        data = response.read(limit)
        # Known sizes must be exact; metadata may be shorter than its cap.
        if byte_range is not None and len(data) != limit:
            raise ValueError("Truncated byte range")
        if expected_size is not None and len(data) != expected_size:
            raise ValueError("Truncated source file")
        if byte_range is None and len(data) == limit:
            raise ValueError("Metadata reached its cap; refusing a truncated document")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(data)
    target.with_suffix(target.suffix + ".sha256").write_text(digest(data) + "\n")
    return data
