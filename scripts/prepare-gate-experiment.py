#!/usr/bin/env python3
"""Prepare local continuous dev PCM and replay the actual Kotlin speech gate.

No downloads or inference. Source references are never copied into the derived
metadata. Audio, source identifiers and local results must remain ignored/private.
"""
import argparse
from array import array
import hashlib
import json
import os
from pathlib import Path
import random
import shutil
import subprocess
import sys
import wave

from replay_compiler import REPLAY_SOURCES, bounded_process, compile_replay, digest_file

RATE = 16_000
BLOCK_FRAMES = 8192
LANGUAGES = ("en", "ru", "de", "fr", "es", "it", "pt")
GAINS = {"normal": 1.0, "quiet": 0.1, "veryquiet": 0.03}
COVERAGE_LABEL = "source-clip coverage, not phoneme-aligned spoken coverage"


def sha256(path):
    digest = hashlib.sha256()
    with Path(path).open("rb") as source:
        for block in iter(lambda: source.read(65_536), b""):
            digest.update(block)
    return digest.hexdigest()


def select_sources(manifest_path, count):
    manifest_path = Path(manifest_path).resolve()
    if not 1 <= count <= 12 or manifest_path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Select 1–12 dev clips per language from a bounded manifest")
    rows = json.loads(manifest_path.read_text())
    if not isinstance(rows, list) or len(rows) > 1000:
        raise ValueError("Expected a bounded array of source clips")
    selected = {language: [] for language in LANGUAGES}
    seen = set()
    for row in rows:
        if not isinstance(row, dict) or not isinstance(row.get("id"), str) or not row["id"] or row["id"] in seen:
            raise ValueError("Every source clip needs a unique string ID")
        seen.add(row["id"])
        language = row.get("language")
        if row.get("split") == "dev" and language in selected and len(selected[language]) < count:
            selected[language].append(row)
    if any(len(clips) != count for clips in selected.values()):
        raise ValueError("Each of the seven languages needs the requested dev clips; held-out clips cannot fill gaps")
    # Validate every held-out row before selecting: different WAV headers can
    # encode identical PCM. Also respect explicit speaker/session group labels.
    audio_splits = {}
    group_splits = {}
    for row in rows:
        if row.get("split") not in ("dev", "test") or row.get("language") not in LANGUAGES:
            raise ValueError("Every source needs an explicit dev/test split and supported language")
        path, _ = validate_source(row, manifest_path.parent)
        digest = hashlib.sha256()
        with wave.open(str(path), "rb") as source:
            while block := source.readframes(BLOCK_FRAMES):
                digest.update(block)
        if sha256(path) != row["audio_sha256"]:
            raise ValueError("Source audio changed during split validation")
        audio_splits.setdefault(digest.hexdigest(), set()).add(row["split"])
        if "group" in row:
            group = row["group"]
            if not isinstance(group, str) or not group:
                raise ValueError("Source groups must be nonempty strings")
            group_splits.setdefault(group, set()).add(row["split"])
    if any(len(splits) > 1 for splits in audio_splits.values()):
        raise ValueError("Identical PCM appears in both development and held-out splits")
    if any(len(splits) > 1 for splits in group_splits.values()):
        raise ValueError("A source speaker/session group occurs in both development and held-out splits")
    return selected


def validate_source(row, root):
    audio = row.get("audio")
    expected = row.get("audio_sha256")
    if not isinstance(audio, str) or Path(audio).is_absolute() or not isinstance(expected, str) or len(expected) != 64:
        raise ValueError("Every selected source needs a relative audio path and SHA-256")
    path = (Path(root) / audio).resolve()
    if not path.is_relative_to(Path(root).resolve()) or not path.is_file():
        raise ValueError("Source audio must be a regular file inside the manifest directory")
    if path.stat().st_size > 4 * 1024 * 1024:
        raise ValueError("Source WAV exceeds the bounded container size")
    if sha256(path) != expected:
        raise ValueError("Selected source audio checksum mismatch")
    with wave.open(str(path), "rb") as source:
        if (source.getnchannels(), source.getsampwidth(), source.getframerate(), source.getcomptype()) != (1, 2, RATE, "NONE"):
            raise ValueError("Expected uncompressed mono 16 kHz signed PCM16 source WAV")
        frames = source.getnframes()
        if not RATE <= frames <= 30 * RATE:
            raise ValueError("Each source clip must contain 1–30 seconds of audio")
        delivered = 0
        while block := source.readframes(BLOCK_FRAMES):
            if len(block) % 2:
                raise ValueError("Source WAV ends in an incomplete PCM16 sample")
            delivered += len(block) // 2
        if delivered != frames:
            raise ValueError("Source WAV is truncated")
    return path, frames


def require_private_output(output, repository):
    output = Path(output).resolve()
    repository = Path(repository).resolve()
    if ".git" in output.parts:
        raise ValueError("Derived audio cannot be written into Git metadata")
    if output.exists() and (not output.is_dir() or any(output.iterdir())):
        raise ValueError("Choose an empty output directory; existing experiment results are preserved")
    # Discover the containing worktree even if it is not this repository, and
    # require the directory itself to be ignored, not just its manifest filename.
    ancestor = output
    while not ancestor.exists():
        ancestor = ancestor.parent
    git_env = {**os.environ, "LC_ALL": "C"}
    result = subprocess.run(["git", "-C", str(ancestor), "rev-parse", "--show-toplevel"],
                            capture_output=True, text=True, check=False, env=git_env)
    if result.returncode == 0:
        worktree = Path(result.stdout.strip()).resolve()
        if ".git" in output.relative_to(worktree).parts:
            raise ValueError("Derived audio cannot be written into Git metadata")
        ignored = subprocess.run(["git", "-C", str(worktree), "check-ignore", "--quiet", "--", str(output) + os.sep])
        if ignored.returncode:
            raise ValueError("Derived audio and metadata must stay in an ignored directory or outside Git worktrees")
    elif result.returncode != 128 or "not a git repository" not in result.stderr.lower():
        raise ValueError("Could not determine whether the output belongs to a Git worktree")
    return output


def scaled_pcm(block, gain):
    if gain == 1.0:
        return block
    samples = array("h")
    samples.frombytes(block)
    if sys.byteorder != "little":
        samples.byteswap()
    for index, sample in enumerate(samples):
        samples[index] = max(-32768, min(32767, round(sample * gain)))
    if sys.byteorder != "little":
        samples.byteswap()
    return samples.tobytes()


def write_stream(path, clips, source_root, gain, leading, gap, trailing):
    intervals = []
    padding = []
    cursor = 0
    with path.open("wb") as output:
        def silence(samples):
            nonlocal cursor
            padding.append((cursor, cursor + samples))
            remaining = samples * 2
            while remaining:
                block = bytes(min(remaining, BLOCK_FRAMES * 2))
                output.write(block)
                remaining -= len(block)
            cursor += samples

        silence(leading)
        for index, row in enumerate(clips):
            source_path, frames = validate_source(row, source_root)
            start = cursor
            with wave.open(str(source_path), "rb") as source:
                while block := source.readframes(BLOCK_FRAMES):
                    output.write(scaled_pcm(block, gain))
            if sha256(source_path) != row["audio_sha256"]:
                raise ValueError("Source audio changed while preparing a stream")
            cursor += frames
            intervals.append(dict(id=row["id"], language=row["language"], split="dev", audio=row["audio"],
                                  source_text_id=row.get("source_text_id"), audio_sha256=row["audio_sha256"],
                                  start_sample=start, end_sample=cursor, source_frames=frames))
            if index + 1 < len(clips):
                silence(gap)
        silence(trailing)
    if path.stat().st_size != cursor * 2:
        raise ValueError("Derived stream sample count does not match its source intervals")
    return intervals, padding, cursor


def merge_ranges(ranges):
    merged = []
    for start, end in sorted(ranges):
        if merged and start <= merged[-1][1]:
            merged[-1] = (merged[-1][0], max(end, merged[-1][1]))
        else:
            merged.append((start, end))
    return merged


def covered_samples(interval, ranges):
    start, end = interval
    return sum(max(0, min(end, right) - max(start, left)) for left, right in ranges)


def missing_ranges(interval, ranges):
    cursor, end = interval
    missing = []
    for start, finish in ranges:
        if finish <= cursor:
            continue
        if start >= end:
            break
        if start > cursor:
            missing.append((cursor, min(start, end)))
        cursor = max(cursor, finish)
    if cursor < end:
        missing.append((cursor, end))
    return missing


def run_gate(java, classpath, pcm, output, total_samples, timeout):
    command = [java, "-Xmx64m", "-XX:ActiveProcessorCount=1", "-cp", classpath,
               "app.focal.tools.SpeechGateReplayKt", str(pcm)]
    replay = bounded_process(command, timeout)
    lines = replay.splitlines()
    if not lines or lines[0] != "startSample\tendSample\toverlap":
        raise ValueError("Unexpected Kotlin gate output format")
    result = []
    for line in lines[1:]:
        fields = line.split("\t")
        if len(fields) != 3 or fields[2] not in ("true", "false"):
            raise ValueError("Invalid Kotlin gate range")
        start, end = map(int, fields[:2])
        if not 0 <= start < end <= total_samples or end - start > 16 * RATE:
            raise ValueError("Kotlin gate returned an invalid source range")
        result.append(dict(start_sample=start, end_sample=end, overlap=fields[2] == "true"))
    output.write_text(replay, encoding="utf-8")
    return result


def summarize(ranges, intervals, padding, total):
    raw = [(row["start_sample"], row["end_sample"]) for row in ranges]
    merged = merge_ranges(raw)
    clips = []
    for source in intervals:
        interval = (source["start_sample"], source["end_sample"])
        covered = covered_samples(interval, merged)
        clips.append(dict(**source, covered_samples=covered, coverage=covered / source["source_frames"],
                          missing_sample_ranges=missing_ranges(interval, merged)))
    source_total = sum(row["source_frames"] for row in intervals)
    source_covered = sum(row["covered_samples"] for row in clips)
    return dict(coverage_label=COVERAGE_LABEL, total_samples=total, candidate_ranges=len(ranges),
                hard_overlap_boundaries=sum(row["overlap"] for row in ranges),
                decode_work_samples=sum(end - start for start, end in raw),
                unique_candidate_samples=sum(end - start for start, end in merged),
                source_clip_samples=source_total, source_clip_covered_samples=source_covered,
                source_clip_coverage=source_covered / source_total if source_total else None,
                clips_without_candidate_audio=sum(row["covered_samples"] == 0 for row in clips),
                inserted_silence_work_samples=sum(covered_samples(interval, raw) for interval in padding),
                clips=clips)


def write_synthetic(path, kind, seconds, seed):
    total = seconds * RATE
    generator = random.Random(seed)
    with path.open("wb") as output:
        for start in range(0, total, BLOCK_FRAMES):
            count = min(BLOCK_FRAMES, total - start)
            samples = array("h", (0 if kind == "zero" else 6000 if kind == "dc" else generator.randint(-32, 32)
                                  for _ in range(count)))
            if sys.byteorder != "little":
                samples.byteswap()
            output.write(samples.tobytes())
    return total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, default=Path("build/stt/corpus/manifest.json"))
    parser.add_argument("--output", type=Path, default=Path("build/stt/gate-experiment"))
    parser.add_argument("--compiler-classpath-file", type=Path, required=True)
    parser.add_argument("--kotlin-stdlib", type=Path, required=True)
    parser.add_argument("--java", default=shutil.which("java"))
    parser.add_argument("--clips-per-language", type=int, default=3)
    parser.add_argument("--timeout", type=int, default=120)
    args = parser.parse_args()
    repository = Path(__file__).resolve().parent.parent
    script_hash = sha256(Path(__file__))
    compiler_helper_hash = sha256(repository / "scripts/replay_compiler.py")
    source_manifest_hash = sha256(args.manifest)
    selected = select_sources(args.manifest, args.clips_per_language)
    if sha256(args.manifest) != source_manifest_hash:
        raise ValueError("Source manifest changed during split validation")
    output = require_private_output(args.output, repository)
    java = shutil.which(args.java) if args.java else None
    if not java or not 1 <= args.timeout <= 300:
        parser.error("Choose an available Java runtime and bounded timeout")
    runtime = bounded_process([java, "-version"], 10, include_stderr=True)
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name != "nt" and output.stat().st_mode & 0o077:
        raise ValueError("Experiment output must be private to the current OS user")
    classes = output / "replay-classes"
    replay_build = compile_replay(repository, java, args.compiler_classpath_file, args.kotlin_stdlib, classes, args.timeout)
    classpath = os.pathsep.join((str(classes.resolve()), str(args.kotlin_stdlib.resolve())))
    provenance_path = args.manifest.parent / "provenance.json"
    if provenance_path.is_file() and provenance_path.stat().st_size > 2 * 1024 * 1024:
        raise ValueError("Source provenance file is too large")
    provenance_hash = sha256(provenance_path) if provenance_path.is_file() else None
    provenance = json.loads(provenance_path.read_text()) if provenance_path.is_file() else {}
    if not isinstance(provenance, dict):
        raise ValueError("Expected a source provenance object")
    report = dict(schema=1, coverage_label=COVERAGE_LABEL,
                  caveat="Source intervals include leading/trailing and internal source silence; no phoneme alignment, WER or hallucination result is inferred.",
                  selection="First requested dev clips per language in source manifest order; no reference-text or ASR selection; held-out clips excluded.",
                  source_manifest=str(args.manifest.resolve()), source_manifest_sha256=source_manifest_hash,
                  source_provenance_sha256=provenance_hash,
                  dataset={key: provenance.get(key) for key in ("dataset", "revision", "license", "attribution", "grouping")},
                  implementation={"script_sha256": script_hash,
                                  "python": sys.version.splitlines()[0],
                                  "java": dict(path=str(Path(java).resolve()), version=runtime.strip()),
                                  "replay_compiler_sha256": compiler_helper_hash,
                                  "fresh_replay_build": replay_build},
                  configuration=dict(sample_rate=RATE, pcm="signed PCM16 little-endian mono", leading_samples=2 * RATE,
                                     gap_samples=12 * RATE // 10, trailing_samples=2 * RATE,
                                     gains=GAINS, gain_rounding="float64 multiply; nearest integer with ties to even; signed16 clamp",
                                     synthetic_seconds=30, synthetic_seed=0xF0CA1,
                                     synthetic_noise="Seeded uniform integer noise [-32,32], not measured room audio", synthetic_dc=6000),
                  cases=[])
    for language, clips in selected.items():
        for scenario, gain in GAINS.items():
            name = f"{language}-{scenario}"
            pcm = output / f"{name}.pcm"
            intervals, padding, total = write_stream(pcm, clips, args.manifest.parent, gain, 2 * RATE, 12 * RATE // 10, 2 * RATE)
            tsv = output / f"{name}.ranges.tsv"
            ranges = run_gate(java, classpath, pcm, tsv, total, args.timeout)
            summary = summarize(ranges, intervals, padding, total)
            report["cases"].append(dict(name=name, language=language, scenario=scenario, gain=gain,
                                        pcm=pcm.name, pcm_sha256=sha256(pcm), ranges_tsv=tsv.name,
                                        ranges_sha256=sha256(tsv), ranges=ranges, **summary))
            print(f"{language}/{scenario}: source-clip coverage {summary['source_clip_coverage']:.1%}; "
                  f"ranges={len(ranges)}, hard-overlap boundaries={summary['hard_overlap_boundaries']}", flush=True)
    for kind in ("zero", "dc", "noise"):
        name = f"synthetic-{kind}"
        pcm = output / f"{name}.pcm"
        total = write_synthetic(pcm, kind, 30, 0xF0CA1)
        tsv = output / f"{name}.ranges.tsv"
        ranges = run_gate(java, classpath, pcm, tsv, total, args.timeout)
        report["cases"].append(dict(name=name, language=None, scenario=kind, gain=1.0, pcm=pcm.name,
                                    pcm_sha256=sha256(pcm), ranges_tsv=tsv.name, ranges_sha256=sha256(tsv),
                                    ranges=ranges, **summarize(ranges, [], [(0, total)], total)))
        print(f"{name}: no-speech-only candidate ranges={len(ranges)}", flush=True)
    if sha256(Path(__file__)) != script_hash or sha256(repository / "scripts/replay_compiler.py") != compiler_helper_hash:
        raise ValueError("Preparation script or compiler helper changed during the experiment")
    if sha256(args.manifest) != source_manifest_hash:
        raise ValueError("Source manifest changed during the experiment")
    if (sha256(provenance_path) if provenance_path.is_file() else None) != provenance_hash:
        raise ValueError("Source provenance changed during the experiment")
    select_sources(args.manifest, args.clips_per_language)
    if sha256(args.manifest) != source_manifest_hash:
        raise ValueError("Source manifest changed during final split validation")
    if replay_build["sources"] != {name: digest_file(repository / name) for name in REPLAY_SOURCES}:
        raise ValueError("Replay sources changed during the experiment")
    actual_classes = {str(path.relative_to(classes)).replace(os.sep, "/"): digest_file(path)
                      for path in sorted(classes.rglob("*")) if path.is_file()}
    if actual_classes != replay_build["classes"] or digest_file(args.kotlin_stdlib) != replay_build["stdlib_sha256"]:
        raise ValueError("Replay classes or runtime changed during the experiment")
    target = output / "manifest.json"
    temporary = target.with_suffix(".json.tmp")
    temporary.write_text(json.dumps(report, indent=2, ensure_ascii=False) + "\n")
    temporary.replace(target)
    print("Gate-only evidence saved:", target)


if __name__ == "__main__":
    main()
