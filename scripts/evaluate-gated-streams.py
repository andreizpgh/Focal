#!/usr/bin/env python3
"""Score prepared continuous dev streams through actual Kotlin replay operations.

All output is private: WAVs, prompts, native token diagnostics and hypotheses.
The current baseline is CPU/full-audio-context/beam3/four threads. No downloads.
"""
import argparse
import base64
from decimal import Decimal
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import sys
import tempfile
import time
import wave

SCRIPT_DIR = Path(__file__).resolve().parent
REPOSITORY = SCRIPT_DIR.parent
sys.path.insert(0, str(SCRIPT_DIR))
from replay_compiler import REPLAY_SOURCES, bounded_process, digest_file
REPLAY_HELPER_SHA256 = digest_file(SCRIPT_DIR / "replay_compiler.py")


def module(name, path):
    source_hash = digest_file(path)
    spec = importlib.util.spec_from_file_location(name, path)
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    if digest_file(path) != source_hash:
        raise ValueError("Evaluation helper changed while loading")
    result.source_sha256 = source_hash
    return result


EVALUATE = module("gated_evaluator", SCRIPT_DIR / "evaluate-stt.py")
PREPARE = module("gated_preparation", SCRIPT_DIR / "prepare-gate-experiment.py")
RATE = 16_000


def json_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False, allow_nan=False).encode()).hexdigest()


def save_json(path, value):
    temporary = path.with_suffix(path.suffix + ".tmp")
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + "\n")
    temporary.replace(path)


def inside(root, name):
    path = (root / name).resolve()
    if not path.is_relative_to(root.resolve()) or not path.is_file():
        raise ValueError("Experiment files must remain inside their recorded directory")
    return path


def verify_replay(experiment, root, stdlib):
    if "provenance_status" in experiment and experiment["provenance_status"] != "verified":
        raise ValueError("Historical replay artifacts cannot supply new E4 evidence")
    implementation = experiment.get("implementation", {})
    if implementation.get("script_sha256") != PREPARE.source_sha256 or implementation.get("replay_compiler_sha256") != REPLAY_HELPER_SHA256:
        raise ValueError("Regenerate gate preparation after helper/source hardening")
    build = experiment.get("implementation", {}).get("fresh_replay_build")
    if not isinstance(build, dict) or set(build.get("sources", {})) != set(REPLAY_SOURCES) or "kotlinc-jvm 2.1.20" not in build.get("compiler_version", ""):
        raise ValueError("Regenerate gate preparation with a fresh attested Kotlin build")
    if any(digest_file(REPOSITORY / name) != expected for name, expected in build["sources"].items()):
        raise ValueError("Replay sources changed after gate preparation")
    classes = root / "replay-classes"
    if classes.is_symlink() or not classes.resolve().is_relative_to(root.resolve()):
        raise ValueError("Replay classes must remain inside their private experiment directory")
    files = sorted(path for path in classes.rglob("*") if path.is_file())
    if not 1 <= len(files) <= 256 or any(path.is_symlink() for path in files):
        raise ValueError("Invalid replay class tree")
    actual = {str(path.relative_to(classes)).replace(os.sep, "/"): digest_file(path) for path in files}
    if actual != build.get("classes") or digest_file(stdlib) != build.get("stdlib_sha256"):
        raise ValueError("Replay classes or Kotlin runtime no longer match their attestation")
    return str(classes.resolve()) + os.pathsep + str(stdlib.resolve())


def validate_cases(experiment, root, references, verified):
    cases = experiment.get("cases")
    if not isinstance(cases, list) or not 1 <= len(cases) <= 100:
        raise ValueError("Expected a bounded prepared case list")
    seen = set()
    for case in cases:
        language, scenario = case.get("language"), case.get("scenario")
        if language in PREPARE.LANGUAGES and scenario in PREPARE.GAINS:
            expected_name = f"{language}-{scenario}"
            if case.get("gain") != PREPARE.GAINS[scenario] or not 1 <= len(case.get("clips", [])) <= 12:
                raise ValueError("Invalid prepared speech condition")
        elif language is None and scenario in ("zero", "dc", "noise") and case.get("clips") == []:
            expected_name = f"synthetic-{scenario}"
        else:
            raise ValueError("Unexpected prepared language or condition")
        if case.get("name") != expected_name or expected_name in seen:
            raise ValueError("Prepared case names must be unique generated language/condition names")
        seen.add(expected_name)
        total = case.get("total_samples")
        if type(total) is not int or not 1 <= total <= 400 * RATE:
            raise ValueError("Invalid bounded stream duration")
        pcm = inside(root, case["pcm"])
        if pcm.stat().st_size != total * 2 or digest_file(pcm) != case.get("pcm_sha256"):
            raise ValueError("Prepared PCM identity or size changed")
        tsv = inside(root, case["ranges_tsv"])
        if tsv.stat().st_size > 128 * 1024 or digest_file(tsv) != case.get("ranges_sha256"):
            raise ValueError("Prepared gate metadata changed")
        ranges = case.get("ranges")
        if not isinstance(ranges, list) or len(ranges) > 1024:
            raise ValueError("Invalid bounded gate range list")
        previous = -1
        for row in ranges:
            start, end = row.get("start_sample"), row.get("end_sample")
            if type(start) is not int or type(end) is not int or type(row.get("overlap")) is not bool or not 0 <= start < end <= total or end - start > 16 * RATE or start < previous:
                raise ValueError("Invalid ordered gate range")
            previous = start
        text = tsv.read_text().splitlines()
        if not text or text[0] != "startSample\tendSample\toverlap":
            raise ValueError("Invalid recorded Kotlin gate transport")
        parsed = []
        for line in text[1:]:
            fields = line.split("\t")
            if len(fields) != 3 or fields[2] not in ("true", "false"):
                raise ValueError("Invalid recorded Kotlin gate transport")
            parsed.append(dict(start_sample=int(fields[0]), end_sample=int(fields[1]), overlap=fields[2] == "true"))
        if parsed != ranges:
            raise ValueError("Recorded JSON ranges disagree with hashed Kotlin gate transport")
        previous = -1
        for clip in case["clips"]:
            source = references.get(clip.get("id"))
            audio = verified.get(clip.get("id"))
            first, last = clip.get("start_sample"), clip.get("end_sample")
            if not source or source.get("split") != "dev" or source["language"] != language or not audio:
                raise ValueError("Prepared speech must use the declared development sources only")
            frames = round(audio["seconds"] * RATE)
            if type(first) is not int or type(last) is not int or not 0 <= first < last <= total or first < previous or last - first != frames or clip.get("source_frames") != frames or clip.get("audio_sha256") != audio["audio_sha256"]:
                raise ValueError("Prepared clip intervals do not match their original audio")
            previous = last
    return cases


def verify_source_derivation(experiment, cases, manifest):
    counts = {len(case["clips"]) for case in cases if case["clips"]}
    if len(counts) > 1:
        raise ValueError("Prepared languages must use the same declared dev cohort size")
    selected = PREPARE.select_sources(manifest, next(iter(counts))) if counts else {}
    config = experiment["configuration"]
    required = dict(sample_rate=RATE, leading_samples=2 * RATE, gap_samples=12 * RATE // 10,
                    trailing_samples=2 * RATE, gains=PREPARE.GAINS, synthetic_seconds=30, synthetic_seed=0xF0CA1,
                    synthetic_dc=6000)
    if any(config.get(key) != value for key, value in required.items()):
        raise ValueError("Prepared source framing differs from the frozen experiment")
    with tempfile.TemporaryDirectory(prefix="focal-e4-source-check-") as temporary:
        for case in cases:
            expected_pcm = Path(temporary) / (case["name"] + ".pcm")
            if case["clips"]:
                clips = selected[case["language"]]
                if [row["id"] for row in case["clips"]] != [row["id"] for row in clips]:
                    raise ValueError("Prepared cases must preserve the deterministic development selection")
                intervals, _, total = PREPARE.write_stream(expected_pcm, clips, manifest.parent,
                    PREPARE.GAINS[case["scenario"]], 2 * RATE, 12 * RATE // 10, 2 * RATE)
                if any(any(reported.get(key) != value for key, value in original.items())
                       for reported, original in zip(case["clips"], intervals)):
                    raise ValueError("Prepared clip intervals changed from their source framing")
            else:
                total = PREPARE.write_synthetic(expected_pcm, case["scenario"], 30, 0xF0CA1)
            if total != case["total_samples"] or digest_file(expected_pcm) != case["pcm_sha256"]:
                raise ValueError("Prepared PCM does not derive from its declared development sources and gain")
            expected_pcm.unlink()


def choose_cases(cases, languages, conditions, controls, controls_only=False):
    names = ([] if controls_only else [f"{language}-{condition}" for language in languages for condition in conditions])
    names += [f"synthetic-{kind}" for kind in controls]
    available = {case["name"]: case for case in cases}
    if len(available) != len(cases) or any(name not in available for name in names):
        raise ValueError("Every requested language/condition/control must exist exactly once")
    return [available[name] for name in names]


def verify_original_sources(verified):
    if any(digest_file(audio["path"]) != audio["audio_sha256"] for audio in verified.values()):
        raise ValueError("An original source WAV changed during inference")


def checked_engine(probe):
    engine = EVALUATE.engine_provenance(probe)
    if engine.get("dependency_hash_status") != "hashed":
        raise ValueError("E4 requires verified dynamic decoder-library hashes; unavailable/static dependency inspection is unsupported")
    return engine


def ranges_for(case, mode):
    if mode == "gated":
        return case["ranges"]
    if mode == "direct-source" and case["clips"]:
        return [dict(start_sample=row["start_sample"], end_sample=row["end_sample"], overlap=False) for row in case["clips"]]
    ranges, start, end = [], 0, case["total_samples"]
    while start < end:
        finish = min(start + 30 * RATE, end)
        ranges.append(dict(start_sample=start, end_sample=finish, overlap=bool(ranges)))
        if finish == end:
            break
        start = finish - RATE // 2
    return ranges


def extract_wave(pcm, interval, output):
    # Stored capture ranges truncate both sample offsets to whole milliseconds.
    start_ms, end_ms = interval["start_sample"] // 16, interval["end_sample"] // 16
    if not 0 <= start_ms < end_ms or end_ms - start_ms > 30_000:
        raise ValueError("Invalid bounded decoder interval")
    remaining = (end_ms - start_ms) * 32
    with pcm.open("rb") as source, wave.open(str(output), "wb") as wav:
        source.seek(start_ms * 32)
        wav.setparams((1, 2, RATE, 0, "NONE", "not compressed"))
        while remaining:
            block = source.read(min(remaining, 16_384))
            if not block or len(block) % 2:
                raise ValueError("Prepared PCM is truncated")
            wav.writeframesraw(block)
            remaining -= len(block)
    return start_ms, end_ms


def write_segments(path, segments):
    with path.open("w") as output:
        output.write("startMs\tendMs\tuncertain\ttextBase64\n")
        for segment in segments:
            encoded = base64.b64encode(segment["text"].encode()).decode("ascii")
            output.write(f"{segment['start']}\t{segment['end']}\t{str(segment['uncertain']).lower()}\t{encoded}\n")


def read_segments(text):
    lines = text.splitlines()
    if not lines or lines[0] != "startMs\tendMs\tuncertain\ttextBase64":
        raise ValueError("Unexpected Kotlin passage transport")
    result = []
    for line in lines[1:]:
        fields = line.split("\t")
        if len(fields) != 4 or fields[2] not in ("true", "false"):
            raise ValueError("Invalid Kotlin passage transport")
        result.append(dict(start=int(fields[0]), end=int(fields[1]), uncertain=fields[2] == "true",
                           text=base64.b64decode(fields[3], validate=True).decode("utf-8")))
    return result


def recent_passages(passages, before):
    # Stable insertion order supplies SQLite's passage-ID tie break.
    return sorted((row for row in passages if row["start"] < before), key=lambda row: row["start"])[-6:]


class KotlinReplay:
    def __init__(self, java, classpath):
        self.java, self.classpath = java, classpath

    def command(self, *args):
        return bounded_process([self.java, "-Xmx64m", "-XX:ActiveProcessorCount=1", "-cp", self.classpath,
                                "app.focal.tools.TranscriptReplay", *map(str, args)], 30, limit=8 * 1024 * 1024)

    def review(self, segment):
        result = self.command("review", str(segment["noSpeech"]), ",".join(map(str, segment["probabilities"]))).strip()
        if result not in ("true", "false"):
            raise ValueError("Invalid production review decision")
        return result == "true"


def decoded_segments(document, lexical, start, end, replay):
    EVALUATE.probe_diagnostics(document, (end - start) / 1000)
    result = []
    for segment, confidence in zip(document["transcription"], lexical["transcription"]):
        first, last = segment["offsets"]["from"], segment["offsets"]["to"]
        duration = end - start
        if first >= duration or last <= 0 or last <= first:
            continue
        result.append(dict(start=start + max(0, first), end=start + min(duration, last), text=segment["text"],
                           uncertain=replay.review(confidence)))
    return result


def decode_chunk(args, wav, prompt, prefix, language):
    command = [str(args.probe.resolve()), "-m", str(args.model.resolve()), "-f", str(wav.resolve()), "-l", language,
               "-of", str(prefix.resolve()), "-t", "4", "-bs", "3", "-ac", "0", "-mc", "64", "-bo", "1",
               "-tp", "0", "-tpi", "0", "-ng", "-fa", "-sns", "-oj", "--prompt-file", str(prompt.resolve())]
    start = time.monotonic()
    log = bounded_process(command, args.clip_timeout, limit=1024 * 1024, include_stderr=True)
    elapsed = time.monotonic() - start
    prefix.with_suffix(".log").write_text(log)
    raw = prefix.with_suffix(".json")
    if raw.stat().st_size > 8 * 1024 * 1024:
        raise ValueError("Unexpectedly large native probe output")
    payload = raw.read_text()
    return json.loads(payload), json.loads(payload, parse_float=Decimal), elapsed


def evaluate_case(args, case, references, source_root, output, replay):
    name = case["name"] + "-" + args.mode
    language = case["language"] or args.control_language
    pcm = inside(source_root, case["pcm"])
    if digest_file(pcm) != case["pcm_sha256"]:
        raise ValueError("Prepared source PCM changed")
    emitted = PREPARE.run_gate(replay.java, replay.classpath, pcm, output / f"{case['name']}.gate.tsv",
                               case["total_samples"], 60)
    if emitted != case["ranges"]:
        raise ValueError("Fresh actual Kotlin gate differs from recorded candidate ranges")
    results = []
    for context in args.contexts:
        directory = output / f"{name}-context-{context}"
        directory.mkdir(mode=0o700)
        passages, chunks, records = [], [], []
        started = time.monotonic()
        for number, interval in enumerate(ranges_for(case, args.mode)):
            prefix = directory / f"chunk-{number:03d}"
            wav = prefix.with_suffix(".wav")
            first, last = extract_wave(pcm, interval, wav)
            prior = next((chunk["end"] for chunk in reversed(chunks) if chunk["start"] < first), None) if interval["overlap"] else None
            recent = recent_passages(passages, prior if prior is not None else first)
            previous_tsv, decoded_tsv = prefix.with_suffix(".previous.tsv"), prefix.with_suffix(".decoded.tsv")
            write_segments(previous_tsv, recent)
            prompt = prefix.with_suffix(".prompt.txt")
            encoded = replay.command("context", previous_tsv, first) if context == "on" else "\n"
            prompt.write_bytes(base64.b64decode(encoded.strip(), validate=True))
            document, lexical, elapsed = decode_chunk(args, wav, prompt, prefix, language)
            profile = EVALUATE.probe_diagnostics(document, (last - first) / 1000)
            original_frames = (last - first) * 16
            if document.get("model", {}).get("multilingual") is not True or profile["initial_prompt_sha256"] != digest_file(prompt) or profile["beam_size"] != 3 or profile["threads"] != 4 or profile["effective_audio_context"] != document["model"]["audio"]["ctx"] or profile["original_audio_frames"] != original_frames or profile["decode_audio_frames"] != max(original_frames, RATE):
                raise ValueError("Native probe did not preserve the frozen prompt/decoder settings")
            decoded = decoded_segments(document, lexical, first, last, replay)
            write_segments(decoded_tsv, decoded)
            command = ["reconcile", previous_tsv, decoded_tsv, first, last] + ([prior] if prior is not None else [])
            reconciled = read_segments(replay.command(*command))
            passages.extend(reconciled)
            chunks.append(dict(start=first, end=last))
            records.append(dict(range=interval, start_ms=first, end_ms=last, overlap_end_ms=prior,
                                wav_sha256=digest_file(wav), prompt_sha256=digest_file(prompt),
                                probe_json_sha256=digest_file(prefix.with_suffix(".json")),
                                probe=profile, committed_passages=reconciled, wall_seconds=elapsed))
        passages = sorted(passages, key=lambda row: row["start"])
        write_segments(directory / "passages.tsv", passages)
        hypothesis = " ".join(row["text"] for row in passages if row["text"])
        reference = " ".join(references[row["id"]]["text"] for row in case["clips"])
        probe_seconds = sum(row["wall_seconds"] for row in records)
        result = dict(id=name + "-context-" + context, language=language, condition=case["scenario"], mode=args.mode,
                      context=context, silence=not bool(case["clips"]), source_pcm_sha256=case["pcm_sha256"],
                      reference=reference, hypothesis=hypothesis, passages=passages, chunks=records,
                      metrics=EVALUATE.score(reference, hypothesis, not bool(case["clips"]), case["total_samples"] / RATE, probe_seconds),
                      orchestration_wall_seconds=time.monotonic() - started)
        save_json(directory / "record.json", result)
        results.append(result)
        print(f"{case['name']}/{args.mode}/context-{context}: chunks={len(records)}; "
              f"word_errors={result['metrics']['word_errors']}/{result['metrics']['words']}", flush=True)
    if digest_file(pcm) != case["pcm_sha256"]:
        raise ValueError("Prepared source PCM changed during inference")
    return results


def main():
    runner_hash = digest_file(Path(__file__))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--kotlin-stdlib", type=Path, required=True)
    parser.add_argument("--probe", type=Path, default=Path("build/whisper-host/bin/stt-probe"))
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/ggml-base-q5_1.bin"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java")
    parser.add_argument("--languages", nargs="+", choices=PREPARE.LANGUAGES, default=list(PREPARE.LANGUAGES))
    parser.add_argument("--conditions", nargs="+", choices=list(PREPARE.GAINS), default=["normal"])
    parser.add_argument("--contexts", nargs="+", choices=("off", "on"), default=["off", "on"])
    parser.add_argument("--mode", choices=("gated", "direct-source", "bypass"), default="gated")
    parser.add_argument("--controls", nargs="*", choices=("zero", "dc", "noise"), default=[])
    parser.add_argument("--controls-only", action="store_true", help="Run only the explicitly requested synthetic controls")
    parser.add_argument("--control-language", choices=PREPARE.LANGUAGES, default="en")
    parser.add_argument("--clip-timeout", type=int, default=180)
    parser.add_argument("--plan-only", action="store_true")
    args = parser.parse_args()
    if not args.model.is_file() or not args.probe.is_file() or not 1 <= args.clip_timeout <= 600:
        parser.error("Choose existing local model/probe/runtime and a bounded decoder timeout")
    for values in (args.languages, args.conditions, args.contexts, args.controls):
        if len(values) != len(set(values)):
            parser.error("Repeated experiment options are not independent cases")
    if args.controls_only and not args.controls:
        parser.error("--controls-only needs at least one named control")
    if args.experiment.stat().st_size > 4 * 1024 * 1024 or args.source_manifest.stat().st_size > 2 * 1024 * 1024:
        parser.error("Expected bounded local manifests")
    os.umask(0o077)
    report_source = json.loads(args.experiment.read_text())
    args.java = shutil.which(args.java or report_source.get("implementation", {}).get("java", {}).get("path", "java"))
    if not args.java:
        parser.error("Choose the existing cached Java runtime")
    if digest_file(args.source_manifest) != report_source["source_manifest_sha256"]:
        parser.error("Source manifest no longer matches prepared stream provenance")
    corpus = EVALUATE.validate_manifest(json.loads(args.source_manifest.read_text()))
    verified = EVALUATE.verify_audio_manifest(corpus, args.source_manifest.parent)
    references = {row["id"]: row for row in corpus}
    classpath = verify_replay(report_source, args.experiment.parent, args.kotlin_stdlib)
    all_cases = validate_cases(report_source, args.experiment.parent, references, verified)
    verify_source_derivation(report_source, all_cases, args.source_manifest)
    cases = choose_cases(all_cases, args.languages, args.conditions, args.controls, args.controls_only)
    if not cases or any(references[row["id"]].get("split") != "dev" for case in cases for row in case["clips"]):
        parser.error("Select prepared development cases only")
    plan = dict(cases=len(cases), context_policies=args.contexts, mode=args.mode,
                languages=[] if args.controls_only else args.languages, conditions=[] if args.controls_only else args.conditions,
                controls=args.controls, control_language=args.control_language,
                cold_probe_calls=sum(len(ranges_for(case, args.mode)) for case in cases) * len(args.contexts),
                source_seconds=sum(case["total_samples"] for case in cases) / RATE,
                audio_context=0, beam_size=3, threads=4)
    if args.plan_only:
        print(json.dumps(plan, indent=2))
        return
    output = PREPARE.require_private_output(args.output, REPOSITORY)
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    output.chmod(0o700)
    shutil.copytree(args.experiment.parent / "replay-classes", output / "replay-classes")
    runtime_copy = output / "kotlin-stdlib.jar"
    shutil.copyfile(args.kotlin_stdlib, runtime_copy)
    classpath = verify_replay(report_source, output, runtime_copy)
    identity = dict(experiment_sha256=digest_file(args.experiment), source_manifest_sha256=digest_file(args.source_manifest),
                    model_sha256=digest_file(args.model), runner_sha256=runner_hash,
                    evaluator_sha256=EVALUATE.source_sha256, preparation_sha256=PREPARE.source_sha256,
                    replay_compiler_sha256=REPLAY_HELPER_SHA256,
                    java=dict(path=str(Path(args.java).resolve()), sha256=digest_file(args.java)),
                    replay_build=report_source["implementation"]["fresh_replay_build"],
                    engine=checked_engine(args.probe))
    save_json(output / "plan.json", dict(**plan, provenance=identity))
    replay = KotlinReplay(args.java, classpath)
    records = []
    for case in cases:
        records.extend(evaluate_case(args, case, references, args.experiment.parent, output, replay))
    verify_replay(report_source, output, runtime_copy)
    for path, expected in ((args.experiment, identity["experiment_sha256"]), (args.source_manifest, identity["source_manifest_sha256"]),
                           (args.model, identity["model_sha256"]), (Path(__file__), identity["runner_sha256"]),
                           (SCRIPT_DIR / "evaluate-stt.py", identity["evaluator_sha256"]),
                           (SCRIPT_DIR / "prepare-gate-experiment.py", identity["preparation_sha256"]),
                           (SCRIPT_DIR / "replay_compiler.py", identity["replay_compiler_sha256"]),
                           (Path(args.java), identity["java"]["sha256"])):
        if digest_file(path) != expected:
            raise ValueError("Evaluation inputs or source changed during inference")
    verify_original_sources(verified)
    if checked_engine(args.probe) != identity["engine"]:
        raise ValueError("Probe binary or linked engine changed during inference")
    with (output / "records.jsonl").open("w") as target:
        for record in records:
            target.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
    groups = {}
    for record in records:
        key = f"{record['mode']}/{record['condition']}/context-{record['context']}"
        groups.setdefault(key, []).append(record)
    report = dict(scope="Continuous-source accuracy experiment with actual Kotlin gate/context/review/reconcile; cold host process per chunk, not Android capture or phone/warm performance.",
                  timing="Probe wall totals include each cold process/model load and exclude Kotlin/helper IO. Orchestration wall is recorded separately. No bootstrap or independent-speaker claim.",
                  scoring="Existing evaluator NFC/casefold lexical WER/CER; committed passages and source references each joined by one space.",
                  plan=plan, provenance=identity,
                  groups={key: dict(micro=EVALUATE.aggregate(rows), languages={language: EVALUATE.aggregate([row for row in rows if row["language"] == language])
                          for language in sorted({row["language"] for row in rows})}) for key, rows in groups.items()})
    save_json(output / "report.json", report)
    print("Private E4 results saved:", output / "report.json")


if __name__ == "__main__":
    main()
