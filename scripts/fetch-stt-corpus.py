#!/usr/bin/env python3
"""Fetch only a bounded prefix of pinned FLEURS archives, never a whole shard.

Original WAVs, references, and manifests stay in the ignored build directory.
Each HTTP body is reserved durably before requesting it, including failed tries.
No retries are automatic. Metadata and archive prefixes are cached with hashes.
Requires numpy/scipy for deterministic float-WAV to PCM16 conversion.
"""
import argparse
import hashlib
import io
import json
import os
from pathlib import Path
import re
import tarfile
import wave
import zlib

from stt_artifacts import digest, fetch, save_json

REVISION = "70bb2e84b976b7e960aa89f1c648e09c59f894dd"
CONFIGS = dict(en="en_us", ru="ru_ru", de="de_de", fr="fr_fr", es="es_419", it="it_it", pt="pt_br")
BASE = f"https://huggingface.co/datasets/google/fleurs/resolve/{REVISION}/"
API = f"https://huggingface.co/api/datasets/google/fleurs/tree/{REVISION}/"
BLOCK = 1024 * 1024


def load_exclusions(paths):
    """Bind prior public provenance before selecting audio, without reading references."""
    excluded = dict(ids=set(), canonical_pcm=set(), pcm=set(), sources=[])
    for path in paths:
        if path.stat().st_size > 2 * BLOCK:
            raise ValueError("Exclusion provenance exceeds its size limit")
        source = path.read_bytes()
        document = json.loads(source)
        if not isinstance(document, dict) or document.get("dataset") != "google/fleurs" or document.get("revision") != REVISION:
            raise ValueError("Exclusions require provenance from the pinned FLEURS revision")
        clips = document.get("clips")
        if not isinstance(clips, list) or not 1 <= len(clips) <= 1000:
            raise ValueError("Exclusion provenance must contain a bounded nonempty clip list")
        for clip in clips:
            if not isinstance(clip, dict):
                raise ValueError("Invalid excluded clip")
            identifier, canonical_pcm = clip.get("id"), clip.get("pcm16_sha256")
            if not isinstance(identifier, str) or not re.fullmatch(r"fleurs-(?:" + "|".join(CONFIGS.values()) + r")-(dev|test)-[0-9]+", identifier):
                raise ValueError("Excluded clips need canonical FLEURS IDs")
            if not isinstance(canonical_pcm, str) or not re.fullmatch(r"[0-9a-f]{64}", canonical_pcm):
                raise ValueError("Excluded clips need canonical PCM16 WAV hashes")
            excluded["ids"].add(identifier)
            # Historical provenance hashes the canonical PCM16 WAV container.
            # Conversion below normalizes headers before comparing this identity.
            excluded["canonical_pcm"].add(canonical_pcm)
            if "pcm_sha256" in clip:
                pcm = clip["pcm_sha256"]
                if not isinstance(pcm, str) or not re.fullmatch(r"[0-9a-f]{64}", pcm):
                    raise ValueError("Invalid excluded raw PCM hash")
                excluded["pcm"].add(pcm)
        excluded["sources"].append(dict(sha256=digest(source), clips=len(clips)))
    return excluded


def pcm_hash(converted):
    with wave.open(io.BytesIO(converted), "rb") as wav:
        return digest(wav.readframes(wav.getnframes()))


def select_wavs(prefix, config, split, count, excluded):
    selected, seen = [], set()
    for name, source in complete_wavs(prefix):
        identifier = f"fleurs-{config}-{split}-{Path(name).stem}"
        if identifier in excluded["ids"]:
            continue
        converted, seconds = pcm16(source)
        if not 1 <= seconds <= 30:
            continue
        audio_hash = pcm_hash(converted)
        if digest(converted) in excluded["canonical_pcm"] or audio_hash in excluded["pcm"]:
            continue
        if identifier in seen or audio_hash in seen:
            raise ValueError("Repeated selected source ID or PCM in archive")
        seen.update((identifier, audio_hash))
        selected.append((name, source, converted, seconds))
        if len(selected) == count:
            break
    return selected


def validate_output(output):
    if output.exists():
        if not output.is_dir() or any(path.name != "source" or not path.is_dir() or path.is_symlink()
                                      for path in output.iterdir()):
            raise ValueError("Choose a new corpus output directory; existing evidence is preserved, only a source cache may be reused")
        if os.name != "nt" and output.stat().st_mode & 0o077:
            raise ValueError("Corpus output must be private to the current OS user")


def complete_wavs(prefix):
    """Yield complete regular WAV members only; never extract archive paths."""
    raw = zlib.decompressobj(16 + zlib.MAX_WBITS).decompress(prefix, 64 * BLOCK)
    with tarfile.open(fileobj=io.BytesIO(raw), mode="r:") as archive:
        try:
            for member in archive:
                if member.offset_data + member.size > len(raw):
                    return
                if member.isfile() and member.name.endswith(".wav"):
                    if not 0 < member.size <= 8 * BLOCK:
                        raise ValueError("Unexpected WAV member size")
                    yield Path(member.name).name, archive.extractfile(member).read()
        except tarfile.ReadError:
            # Expected: the bounded prefix can stop between two members.
            return


def pcm16(source):
    import numpy as np
    from scipy.io import wavfile
    rate, samples = wavfile.read(io.BytesIO(source))
    if rate != 16000 or samples.ndim != 1:
        raise ValueError("Expected mono 16 kHz FLEURS audio")
    if samples.dtype.kind == "f":
        if not np.isfinite(samples).all():
            raise ValueError("Nonfinite source audio")
        samples = np.clip(np.rint(samples.astype(np.float64) * 32768), -32768, 32767).astype("<i2")
    elif samples.dtype != np.int16:
        raise ValueError("Unexpected source WAV encoding")
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1); wav.setsampwidth(2); wav.setframerate(rate)
        wav.writeframes(samples.astype("<i2").tobytes())
    return output.getvalue(), len(samples) / rate


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=Path("build/stt/corpus"))
    parser.add_argument("--ledger", type=Path, default=Path("build/stt/download-ledger.json"))
    parser.add_argument("--dev-clips", type=int, default=3)
    parser.add_argument("--test-clips", type=int, default=5)
    parser.add_argument("--splits", nargs="+", choices=("dev", "test"), default=["dev", "test"])
    parser.add_argument("--exclude-provenance", type=Path, action="append", default=[],
                        help="Exclude every prior clip ID and PCM identity in this public provenance; repeat for multiple cohorts")
    args = parser.parse_args()
    if not (1 <= args.dev_clips <= 12 and 1 <= args.test_clips <= 12):
        parser.error("Select 1–12 clips per language/split")
    if len(args.splits) != len(set(args.splits)):
        parser.error("Select each corpus split at most once")
    validate_output(args.output)
    exclusions = load_exclusions(args.exclude_provenance)
    os.umask(0o077)
    import subprocess
    repository = Path(__file__).resolve().parent.parent
    for path in (args.output / "manifest.json", args.ledger):
        if path.resolve().is_relative_to(repository):
            checked = subprocess.run(["git", "-C", str(repository), "check-ignore", "--quiet", "--", str(path.resolve())], capture_output=True)
            if checked.returncode != 0 or ".git" in path.resolve().relative_to(repository).parts:
                parser.error("Audio, references and ledger must stay in ignored paths or outside the repository")
    import fcntl
    args.ledger.parent.mkdir(parents=True, exist_ok=True)
    with args.ledger.with_suffix(".lock").open("w") as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        fetch_corpus(args, exclusions)


def fetch_corpus(args, exclusions):
    cache = args.output / "source" / REVISION
    manifest, provenance = [], []
    for language, config in CONFIGS.items():
        info = json.loads(fetch(API + f"data/{config}", cache / f"{config}-tree.json", BLOCK, args.ledger, kind="metadata"))
        archives = json.loads(fetch(API + f"data/{config}/audio", cache / f"{config}-audio-tree.json", BLOCK, args.ledger, kind="metadata"))
        for split in args.splits:
            count = args.dev_clips if split == "dev" else args.test_clips
            tsv_path = f"data/{config}/{split}.tsv"
            tsv_info = next(item for item in info if item["path"] == tsv_path)
            tsv = fetch(BASE + tsv_path, cache / f"{config}-{split}.tsv", tsv_info["size"] + 1, args.ledger,
                        expected_size=tsv_info["size"])
            git_blob = hashlib.sha1(f"blob {len(tsv)}\0".encode() + tsv).hexdigest()
            if git_blob != tsv_info["oid"]:
                raise ValueError("Reference TSV does not match pinned Git blob")
            rows = [line.split("\t") for line in tsv.decode().splitlines()]
            references = {row[1]: row for row in rows}
            archive_path = f"data/{config}/audio/{split}.tar.gz"
            archive = next(item for item in archives if item["path"] == archive_path)
            prefix = b""
            selected = []
            for block in range(16):
                start, end = block * BLOCK, min((block + 1) * BLOCK, archive["size"]) - 1
                if end + 1 == archive["size"]:
                    raise ValueError("Refusing to download a whole corpus shard")
                prefix += fetch(BASE + archive_path, cache / f"{config}-{split}-{block:02d}.gzpart", end-start+1,
                                args.ledger, (start, end), archive["size"])
                selected = select_wavs(prefix, config, split, count, exclusions)
                if len(selected) == count:
                    break
            if len(selected) != count:
                raise ValueError("Selected clips exceed prefix cap")
            for name, source, converted, seconds in selected:
                row = references[name]
                identifier = f"fleurs-{config}-{split}-{Path(name).stem}"
                output = args.output / (identifier + ".wav")
                output.write_bytes(converted)
                manifest.append(dict(id=identifier, audio=output.name, language=language,
                                     split="dev" if split == "dev" else "test", text=row[2],
                                     source_text_id=row[0], audio_sha256=digest(converted)))
                provenance.append(dict(id=identifier, language=language, split=split, source_wav=name,
                                       source_wav_sha256=digest(source), pcm16_sha256=digest(converted),
                                       pcm_sha256=pcm_hash(converted),
                                       reference_sha256=digest(row[2].encode()), seconds=seconds,
                                       archive=archive_path, archive_expected_sha256=archive["lfs"]["oid"],
                                       prefix_bytes=len(prefix), prefix_sha256=digest(prefix)))
            save_json(args.output / "manifest.json", manifest)
            save_json(args.output / "provenance.json", dict(dataset="google/fleurs", revision=REVISION,
                      license="CC-BY-4.0", selection="First complete 1–30 s WAVs in archive order after prior ID/PCM exclusions; no transcript/ASR selection",
                      selected_splits=args.splits, excluded_provenance=exclusions["sources"],
                      attribution="FLEURS, Conneau et al. (2022), Google; https://huggingface.co/datasets/google/fleurs",
                      grouping="Official dev/test splits; no individual speaker IDs exposed and dev/test speaker independence unverified. No speaker-bootstrap claim.",
                      clips=provenance))
            print(f"{config}/{split}: {count} clips, {len(prefix):,} archive bytes", flush=True)
    print("Corpus ready; audio and reference texts remain local:", args.output)


if __name__ == "__main__":
    main()
