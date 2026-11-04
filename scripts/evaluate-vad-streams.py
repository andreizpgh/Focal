#!/usr/bin/env python3
"""Evaluate offline Silero framing on the exact prepared continuous source PCM.

This is a bounded host experiment, not Android/live-capture equivalence. All
audio, references, probabilities, prompts and hypotheses remain private output.
--plan-only runs VAD and records its ranges but never invokes speech recognition.
"""
import argparse
import base64
from decimal import Decimal
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import shutil
import sys
import time

SCRIPT_DIR = Path(__file__).resolve().parent
sys.path.insert(0, str(SCRIPT_DIR))
from replay_compiler import bounded_process, digest_file

SPEC = importlib.util.spec_from_file_location("vad_gate_evaluation", SCRIPT_DIR / "evaluate-gated-streams.py")
E4 = importlib.util.module_from_spec(SPEC)
GATE_RUNNER_HASH = digest_file(SPEC.origin)
SPEC.loader.exec_module(E4)
if digest_file(SPEC.origin) != GATE_RUNNER_HASH:
    raise ValueError("Gate evaluation helper changed while loading")

RATE = 16_000
MAX_SAMPLES = 400 * RATE
MAX_CHUNK = 16 * RATE
OVERLAP = RATE // 2
ENGINE_COMMIT = "4979e04f5dcaccb36057e059bbaed8a2f5288315"
VAD_MODEL = dict(repository="ggml-org/whisper-vad", revision="9ffd54a1e1ee413ddf265af9913beaf518d1639b",
                 filename="ggml-silero-v5.1.2.bin", bytes=885098,
                 sha256="29940d98d42b91fbd05ce489f3ecf7c72f0a42f027e4875919a28fb4c04ea2cf")
ASR_MODEL_SHA256 = "422f1ae452ade6f30a004d7e5c6a43195e4433bc370bf23fac9cc591f01a8898"
VAD_SETTINGS = dict(use_gpu=False, threads=1, threshold=0.5, min_speech_ms=250,
                    min_silence_ms=600, max_speech_seconds=15, pad_ms=500, samples_overlap_seconds=0)
SCOPE = "Offline whole-stream Silero VAD and cold host ASR, at most 400 seconds; not live Android capture, phone latency, energy or thermals."


def conditioning_policy(mode):
    if mode == "none":
        return dict(mode="none", input="Original prepared PCM, unchanged")
    if mode != "causal-rms-cap30":
        raise ValueError("Choose none or causal-rms-cap30 VAD conditioning")
    import numpy as np
    return dict(mode=mode, sample_rate=RATE, frame_samples=512, target_rms=0.03,
                min_gain=1.0, max_gain=30.0, release_seconds=0.5, initial_envelope=0.0,
                rms="float64 sqrt(mean(square(PCM16 / 32768))); DC included",
                envelope="max(current_frame_rms, previous_envelope * exp(-frame_samples / 16000 / 0.5))",
                gain="min(30, max(1, 0.03 / max(envelope, 1e-12)))",
                partial_tail="Use actual remaining samples for RMS and release; no added samples",
                pcm="Signed little-endian PCM16; float64 numpy.rint ties-to-even; reject clipping",
                state="Reset once per source stream; no lookahead beyond the current 512-sample frame",
                numpy_version=np.__version__, asr_input="Original prepared PCM, unchanged")


def conditioned_blocks(source, total):
    """Yield exact VAD-only bytes with one 512-sample frame and scalar envelope state."""
    import numpy as np
    envelope, remaining = 0.0, total
    while remaining:
        count = min(512, remaining)
        block = source.read(count * 2)
        if len(block) != count * 2:
            raise ValueError("Prepared PCM was truncated during VAD conditioning")
        frame = np.frombuffer(block, dtype="<i2").astype(np.float64) / 32768
        rms = float(np.sqrt(np.mean(frame * frame)))
        envelope = max(rms, envelope * math.exp(-count / RATE / 0.5))
        gain = min(30.0, max(1.0, 0.03 / max(envelope, 1e-12)))
        scaled = np.rint(frame * gain * 32768)
        if not np.isfinite(scaled).all() or scaled.min() < -32768 or scaled.max() > 32767:
            raise ValueError("VAD conditioning would clip PCM; refusing changed evidence")
        yield block, scaled.astype("<i2").tobytes()
        remaining -= count
    if source.read(1):
        raise ValueError("Prepared PCM grew during VAD conditioning")


def prepare_conditioned_pcm(pcm, target, total, expected_hash):
    if type(total) is not int or not 0 < total <= MAX_SAMPLES or pcm.stat().st_size != total * 2:
        raise ValueError("Invalid bounded PCM size for VAD conditioning")
    source_hash, output_hash = hashlib.sha256(), hashlib.sha256()
    created = False
    try:
        with pcm.open("rb") as source, target.open("xb") as output:
            created = True
            for original, conditioned in conditioned_blocks(source, total):
                source_hash.update(original)
                output_hash.update(conditioned)
                output.write(conditioned)
        if source_hash.hexdigest() != expected_hash or digest_file(pcm) != expected_hash:
            raise ValueError("Prepared PCM changed during VAD conditioning")
        if target.stat().st_size != total * 2 or digest_file(target) != output_hash.hexdigest():
            raise ValueError("Conditioned VAD PCM differs from its derived bytes")
    except BaseException:
        if created:
            target.unlink(missing_ok=True)
        raise
    return output_hash.hexdigest()


def reject_nonfinite(value):
    raise ValueError("Nonfinite JSON number")


def read_json_identity(path, limit):
    if not path.is_file() or path.stat().st_size > limit:
        raise ValueError("Expected a bounded JSON input")
    with path.open("rb") as stream:
        payload = stream.read(limit + 1)
    if len(payload) > limit:
        raise ValueError("JSON input grew beyond its bound")
    document = json.loads(payload, parse_constant=reject_nonfinite)
    return document, hashlib.sha256(payload).hexdigest()


def bind_asr_output(path, document, lexical):
    """Bind both helper parses to one bounded payload before accepting its hash."""
    with path.open("rb") as stream:
        payload = stream.read(8 * 1024 * 1024 + 1)
    if len(payload) > 8 * 1024 * 1024:
        raise ValueError("ASR output exceeded its bound")
    if json.loads(payload, parse_constant=reject_nonfinite) != document or json.loads(
            payload, parse_float=Decimal, parse_constant=reject_nonfinite) != lexical:
        raise ValueError("ASR output changed after parsing")
    return hashlib.sha256(payload).hexdigest()


def save_bound_plan(path, plan):
    payload = (json.dumps(plan, indent=2, ensure_ascii=False, allow_nan=False) + "\n").encode("utf-8")
    with path.open("xb") as stream:
        stream.write(payload)
    return hashlib.sha256(payload).hexdigest()


def verify_vad_model(model, provenance):
    document, provenance_hash = read_json_identity(provenance, 64 * 1024)
    if not isinstance(document, dict) or any(document.get(key) != value for key, value in VAD_MODEL.items()):
        raise ValueError("VAD provenance must identify the pinned Silero 5.1.2 artifact")
    if not model.is_file() or model.stat().st_size != VAD_MODEL["bytes"] or digest_file(model) != VAD_MODEL["sha256"]:
        raise ValueError("VAD model differs from its pinned size or SHA-256")
    return dict(**VAD_MODEL, provenance_sha256=provenance_hash)


def checked_engine(probe):
    engine = E4.checked_engine(probe)
    if engine.get("source_checkout_commit") != ENGINE_COMMIT:
        raise ValueError("Use the recorded Whisper engine checkout")
    if not any(row.get("name", "").startswith("libwhisper.") for row in engine.get("shared_libraries", [])):
        raise ValueError("A hashed dynamic Whisper library is required")
    return engine


def validate_frozen_policy(path, policy, split):
    if split not in ("dev", "test"):
        raise ValueError("Select an explicit dev or test split")
    if path is None:
        if split == "test":
            raise ValueError("Held-out evaluation requires --frozen-plan before reading its source manifest")
        return None
    frozen, frozen_hash = read_json_identity(path, 4 * 1024 * 1024)
    if not isinstance(frozen, dict) or frozen.get("policy") != policy:
        raise ValueError("Requested VAD/ASR policy differs from the frozen plan")
    return frozen_hash


def bounded_ranges(native, total):
    """Union touching native intervals, then retain exactly 500 ms at hard cuts."""
    if type(total) is not int or not 0 < total <= MAX_SAMPLES or not isinstance(native, list) or len(native) > 12_500:
        raise ValueError("Invalid bounded VAD range input")
    merged, previous_end = [], -1
    for index, row in enumerate(native):
        start, end = row.get("start_sample"), row.get("end_sample")
        if type(start) is not int or type(end) is not int or not 0 <= start < end <= total or start < previous_end:
            raise ValueError("VAD intervals must have ordered, nonoverlapping sample bounds")
        if end // 16 <= start // 16:
            raise ValueError("VAD interval has no stored millisecond audio")
        if merged and start == merged[-1][1]:
            merged[-1][1] = end
            merged[-1][2].append(index)
        else:
            merged.append([start, end, [index]])
        previous_end = end
    ranges = []
    for first, last, indices in merged:
        start, overlap = first, False
        while start < last:
            end = min(start + MAX_CHUNK, last)
            ranges.append(dict(start_sample=start, end_sample=end, overlap=overlap,
                               vad_segment_indices=[index for index in indices
                                   if native[index]["start_sample"] < end and native[index]["end_sample"] > start]))
            if end == last:
                break
            start, overlap = end - OVERLAP, True
    return ranges


def validate_vad_output(document, case, expected_model):
    if not isinstance(document, dict):
        raise ValueError("VAD output must be a JSON object")
    total = case["total_samples"]
    windows = (total + 511) // 512
    expected = dict(schema=1, model="silero-16k-5.1.2", model_sha256=expected_model,
                    pcm_sha256=case["pcm_sha256"], sample_rate=RATE, input_samples=total,
                    window_samples=512, probability_windows=windows, padded_samples=windows * 512)
    if any(type(document.get(key)) is not type(value) or document[key] != value for key, value in expected.items()):
        raise ValueError("VAD output differs from expected model, PCM or sample identity")
    settings = document.get("settings")
    if not isinstance(settings, dict) or set(settings) != set(VAD_SETTINGS) or any(
            type(settings[key]) is not type(value) or settings[key] != value for key, value in VAD_SETTINGS.items()):
        raise ValueError("VAD probe changed the frozen settings")
    probabilities = document.get("probabilities")
    if not isinstance(probabilities, list) or len(probabilities) != windows or any(
            type(value) not in (int, float) or not math.isfinite(value) or not 0 <= value <= 1 for value in probabilities):
        raise ValueError("VAD probabilities must cover all expected windows with finite values")
    for key in ("load_seconds", "detect_seconds", "segment_seconds"):
        value = document.get(key)
        if type(value) not in (int, float) or not math.isfinite(value) or value < 0:
            raise ValueError("VAD phase times must be finite nonnegative values")
    peak = document.get("peak_rss_bytes")
    if peak is not None and (type(peak) is not int or peak < 0):
        raise ValueError("Invalid VAD peak RSS")
    ranges, discarded = document.get("ranges"), document.get("padding_only_ranges_discarded")
    if not isinstance(ranges, list) or len(ranges) > windows or type(discarded) is not int or not 0 <= discarded <= windows - len(ranges):
        raise ValueError("Invalid bounded native VAD ranges")
    previous = -1
    for row in ranges:
        if not isinstance(row, dict):
            raise ValueError("Invalid native VAD range")
        start, end = row.get("native_start_cs"), row.get("native_end_cs")
        if type(start) is not int or type(end) is not int or not 0 <= start < end <= math.ceil(windows * 512 / 160) or start < previous:
            raise ValueError("Invalid native VAD centisecond order")
        if type(row.get("start_sample")) is not int or type(row.get("end_sample")) is not int or row["start_sample"] != min(start * 160, total) or row["end_sample"] != min(end * 160, total):
            raise ValueError("VAD sample offsets differ from native centiseconds")
        previous = end
    return bounded_ranges(ranges, total)


def inserted_intervals(case):
    padding, cursor = [], 0
    for clip in case["clips"]:
        if clip["start_sample"] > cursor:
            padding.append((cursor, clip["start_sample"]))
        cursor = clip["end_sample"]
    if cursor < case["total_samples"]:
        padding.append((cursor, case["total_samples"]))
    return padding


def prepare_vad(args, case, source_root, output):
    pcm = E4.inside(source_root, case["pcm"])
    if pcm.stat().st_size != case["total_samples"] * 2 or digest_file(pcm) != case["pcm_sha256"]:
        raise ValueError("Prepared PCM changed before VAD")
    conditioning = conditioning_policy(args.vad_conditioning)
    vad_pcm, vad_hash = pcm, case["pcm_sha256"]
    conditioning_started = time.monotonic()
    derived_name = None
    if args.vad_conditioning != "none":
        derived_name = case["name"] + ".vad-input.pcm"
        vad_pcm = output / derived_name
        vad_hash = prepare_conditioned_pcm(pcm, vad_pcm, case["total_samples"], case["pcm_sha256"])
    conditioning_wall = time.monotonic() - conditioning_started
    target = output / (case["name"] + ".vad.json")
    command = [str(args.vad_probe.resolve()), str(args.vad_model.resolve()), str(vad_pcm), str(target),
               "--samples", str(case["total_samples"]), "--model-sha256", VAD_MODEL["sha256"],
               "--threads", "1", "--threshold", "0.5", "--min-speech-ms", "250", "--min-silence-ms", "600",
               "--max-speech-seconds", "15", "--pad-ms", "500"]
    started = time.monotonic()
    log = bounded_process(command, args.vad_timeout, limit=1024 * 1024, include_stderr=True)
    wall = time.monotonic() - started
    (output / (case["name"] + ".vad.log")).write_text(log)
    document, output_hash = read_json_identity(target, 2 * 1024 * 1024)
    ranges = validate_vad_output(document, dict(case, pcm_sha256=vad_hash), VAD_MODEL["sha256"])
    if digest_file(pcm) != case["pcm_sha256"] or digest_file(vad_pcm) != vad_hash:
        raise ValueError("Prepared PCM changed during VAD")
    # Prepared clips already contain the old gate's coverage; carry only source identity and bounds.
    source_fields = ("id", "language", "split", "audio", "source_text_id", "audio_sha256",
                     "start_sample", "end_sample", "source_frames")
    source_intervals = [{key: clip[key] for key in source_fields} for clip in case["clips"]]
    summary = E4.PREPARE.summarize(ranges, source_intervals, inserted_intervals(case), case["total_samples"])
    return dict(case=case["name"], source_pcm_sha256=case["pcm_sha256"], vad_output_sha256=output_hash,
                vad_input=dict(conditioning=conditioning, pcm_sha256=vad_hash, input_samples=case["total_samples"],
                               bytes=case["total_samples"] * 2, derived_pcm=derived_name,
                               source_pcm_unchanged=True),
                native_ranges=document["ranges"], ranges=ranges, summary=summary,
                profile={key: document[key] for key in ("load_seconds", "detect_seconds", "segment_seconds", "peak_rss_bytes")},
                conditioning_wall_seconds=conditioning_wall, cold_vad_wall_seconds=wall)


def evaluate_case(args, case, selection, references, source_root, output, replay):
    """Reuse the production context/reconciliation pipeline with attested VAD ranges."""
    pcm = E4.inside(source_root, case["pcm"])
    if digest_file(pcm) != selection["source_pcm_sha256"]:
        raise ValueError("Source PCM changed after VAD selection")
    language, results = case["language"] or args.control_language, []
    for context in args.contexts:
        directory = output / f"{case['name']}-vad-context-{context}"
        directory.mkdir(mode=0o700)
        passages, chunks, records, artifacts = [], [], [], {}
        started = time.monotonic()
        for number, interval in enumerate(selection["ranges"]):
            prefix = directory / f"chunk-{number:03d}"
            wav = prefix.with_suffix(".wav")
            first, last = E4.extract_wave(pcm, interval, wav)
            prior = next((chunk["end"] for chunk in reversed(chunks) if chunk["start"] < first), None) if interval["overlap"] else None
            recent = E4.recent_passages(passages, prior if prior is not None else first)
            previous, decoded_file = prefix.with_suffix(".previous.tsv"), prefix.with_suffix(".decoded.tsv")
            E4.write_segments(previous, recent)
            prompt = prefix.with_suffix(".prompt.txt")
            encoded = replay.command("context", previous, first) if context == "on" else "\n"
            prompt.write_bytes(base64.b64decode(encoded.strip(), validate=True))
            wav_hash, prompt_hash = digest_file(wav), digest_file(prompt)
            document, lexical, elapsed = E4.decode_chunk(args, wav, prompt, prefix, language)
            asr_file = prefix.with_suffix(".json")
            asr_hash = bind_asr_output(asr_file, document, lexical)
            if digest_file(wav) != wav_hash or digest_file(prompt) != prompt_hash:
                raise ValueError("ASR chunk audio or prompt changed during decoding")
            profile = E4.EVALUATE.probe_diagnostics(document, (last - first) / 1000)
            frames = (last - first) * 16
            if not isinstance(profile, dict) or document.get("model", {}).get("multilingual") is not True or document["model"].get("audio", {}).get("ctx") != 1500 or profile.get("initial_prompt_sha256") != prompt_hash or profile.get("beam_size") != 3 or profile.get("threads") != 4 or profile.get("effective_audio_context") != 1500 or profile.get("original_audio_frames") != frames or profile.get("decode_audio_frames") != max(frames, RATE):
                raise ValueError("ASR probe changed frozen multilingual base/context/decoder settings")
            decoded = E4.decoded_segments(document, lexical, first, last, replay)
            E4.write_segments(decoded_file, decoded)
            command = ["reconcile", previous, decoded_file, first, last] + ([prior] if prior is not None else [])
            reconciled = E4.read_segments(replay.command(*command))
            passages.extend(reconciled)
            chunks.append(dict(start=first, end=last))
            artifacts.update({wav: wav_hash, prompt: prompt_hash, asr_file: asr_hash})
            records.append(dict(range=interval, start_ms=first, end_ms=last, overlap_end_ms=prior,
                                vad_output_sha256=selection["vad_output_sha256"],
                                wav_sha256=wav_hash, prompt_sha256=prompt_hash,
                                probe_json_sha256=asr_hash,
                                probe=profile, committed_passages=reconciled, wall_seconds=elapsed))
        passages = sorted(passages, key=lambda row: row["start"])
        E4.write_segments(directory / "passages.tsv", passages)
        hypothesis = " ".join(row["text"] for row in passages if row["text"])
        reference = " ".join(references[row["id"]]["text"] for row in case["clips"])
        if any(digest_file(path) != expected for path, expected in artifacts.items()):
            raise ValueError("ASR chunk evidence changed before scoring")
        result = dict(id=f"{case['name']}-vad-context-{context}", language=language, condition=case["scenario"],
                      mode="offline-vad", context=context, split=args.split if case["clips"] else None,
                      silence=not bool(case["clips"]), source_pcm_sha256=case["pcm_sha256"],
                      reference=reference, hypothesis=hypothesis, passages=passages, chunks=records,
                      vad_output_sha256=selection["vad_output_sha256"],
                      vad_input=selection["vad_input"],
                      metrics=E4.EVALUATE.score(reference, hypothesis, not bool(case["clips"]),
                          case["total_samples"] / RATE, sum(row["wall_seconds"] for row in records)),
                      orchestration_wall_seconds=time.monotonic() - started)
        E4.save_json(directory / "record.json", result)
        results.append(result)
        print(f"{case['name']}/offline-vad/context-{context}: chunks={len(records)}; "
              f"word_errors={result['metrics']['word_errors']}/{result['metrics']['words']}", flush=True)
    if digest_file(pcm) != selection["source_pcm_sha256"]:
        raise ValueError("Source PCM changed during ASR")
    return results


def check_inputs(args, files, engines):
    if any(digest_file(path) != expected for path, expected in files.items()):
        raise ValueError("Evaluation source, model, runtime or input identity changed")
    if checked_engine(args.vad_probe) != engines["vad"] or checked_engine(args.probe) != engines["asr"]:
        raise ValueError("A probe or its linked native dependencies changed")


def input_identities(args, policy, experiment_hash, source_manifest_hash, frozen_hash):
    """Retain identities verified earlier; never bless a later file mutation."""
    files = {SCRIPT_DIR / name: expected for name, expected in policy["helper_hashes"].items()}
    files.update({
        args.experiment: experiment_hash, args.source_manifest: source_manifest_hash,
        args.model: ASR_MODEL_SHA256, args.vad_model: VAD_MODEL["sha256"],
        args.vad_model_provenance: policy["vad_model"]["provenance_sha256"],
        Path(args.java): policy["java_sha256"],
    })
    if args.frozen_plan:
        files[args.frozen_plan] = frozen_hash
    return files


def main():
    runner_hash = digest_file(Path(__file__))
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--experiment", type=Path, required=True)
    parser.add_argument("--source-manifest", type=Path, required=True)
    parser.add_argument("--split", choices=("dev", "test"), required=True)
    parser.add_argument("--frozen-plan", type=Path, help="Required for test: an earlier plan with the identical policy")
    parser.add_argument("--kotlin-stdlib", type=Path, required=True)
    parser.add_argument("--vad-model", type=Path, required=True)
    parser.add_argument("--vad-model-provenance", type=Path, required=True)
    parser.add_argument("--vad-probe", type=Path, default=Path("build/whisper-host/bin/vad-probe"))
    parser.add_argument("--vad-conditioning", choices=("none", "causal-rms-cap30"), default="none",
                        help="Optional causal gain for VAD only; original ASR PCM is never conditioned")
    parser.add_argument("--probe", type=Path, default=Path("build/whisper-host/bin/stt-probe"))
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/ggml-base-q5_1.bin"))
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--java")
    parser.add_argument("--languages", nargs="+", choices=E4.PREPARE.LANGUAGES, default=list(E4.PREPARE.LANGUAGES))
    parser.add_argument("--conditions", nargs="+", choices=list(E4.PREPARE.GAINS), default=["normal", "quiet"])
    parser.add_argument("--contexts", nargs="+", choices=("off", "on"), default=["on"])
    parser.add_argument("--controls", nargs="*", choices=("zero", "dc", "noise"), default=[])
    parser.add_argument("--controls-only", action="store_true")
    parser.add_argument("--control-language", choices=E4.PREPARE.LANGUAGES, default="en")
    parser.add_argument("--clip-timeout", type=int, default=180)
    parser.add_argument("--vad-timeout", type=int, default=120)
    parser.add_argument("--plan-only", action="store_true", help="Execute VAD/preparation only, with no ASR or text scoring")
    args = parser.parse_args()
    if args.split == "test" and args.frozen_plan is None:
        parser.error("Held-out evaluation requires --frozen-plan before reading its source manifest")
    if not 1 <= args.clip_timeout <= 600 or not 1 <= args.vad_timeout <= 600:
        parser.error("Choose bounded VAD and ASR timeouts")
    for values in (args.languages, args.conditions, args.contexts, args.controls):
        if len(values) != len(set(values)):
            parser.error("Repeated options are not independent samples")
    if args.controls_only and not args.controls:
        parser.error("--controls-only requires named controls")
    os.umask(0o077)
    output = E4.PREPARE.require_private_output(args.output, E4.REPOSITORY)
    source, experiment_hash = read_json_identity(args.experiment, 4 * 1024 * 1024)
    E4.selected_split(source, args.split)
    args.java = shutil.which(args.java or source.get("implementation", {}).get("java", {}).get("path", "java"))
    if not args.java:
        parser.error("Choose the existing Java runtime")
    classpath = E4.verify_replay(source, args.experiment.parent, args.kotlin_stdlib)
    vad_model = verify_vad_model(args.vad_model, args.vad_model_provenance)
    if not args.model.is_file() or args.model.stat().st_size != 59707625 or digest_file(args.model) != ASR_MODEL_SHA256:
        parser.error("ASR requires the pinned multilingual base Q5_1 model")
    engines = dict(vad=checked_engine(args.vad_probe), asr=checked_engine(args.probe))
    native = [{row["name"]: row["sha256"] for row in engine["shared_libraries"]
               if row["name"].startswith(("libwhisper.", "libggml"))} for engine in engines.values()]
    if native[0] != native[1]:
        raise ValueError("VAD and ASR must use the same pinned Whisper/GGML libraries")
    helper_hashes = {
        "evaluate-vad-streams.py": runner_hash, "evaluate-gated-streams.py": GATE_RUNNER_HASH,
        "evaluate-stt.py": E4.EVALUATE.source_sha256, "prepare-gate-experiment.py": E4.PREPARE.source_sha256,
        "replay_compiler.py": E4.REPLAY_HELPER_SHA256,
    }
    policy = dict(vad=VAD_SETTINGS, vad_conditioning=conditioning_policy(args.vad_conditioning),
                  vad_model=vad_model, engines=engines, helper_hashes=helper_hashes,
                  asr=dict(model_sha256=ASR_MODEL_SHA256, beam_size=3, threads=4, audio_context=0,
                           text_context_tokens=64, temperature=0, temperature_increment=0, best_of=1),
                  framing=dict(max_source_samples=MAX_SAMPLES, max_chunk_samples=MAX_CHUNK, overlap_samples=OVERLAP,
                               native_timebase="centiseconds", stored_timebase="truncated integer milliseconds",
                               touching_intervals="union before bounded splitting", native_overlap_seconds=0),
                  replay_build=source["implementation"]["fresh_replay_build"],
                  java_sha256=digest_file(args.java), languages=[] if args.controls_only else args.languages,
                  conditions=[] if args.controls_only else args.conditions, contexts=args.contexts,
                  controls=args.controls, control_language=args.control_language)
    frozen_hash = validate_frozen_policy(args.frozen_plan, policy, args.split)
    rows, manifest_hash = read_json_identity(args.source_manifest, 2 * 1024 * 1024)
    if manifest_hash != source["source_manifest_sha256"]:
        raise ValueError("Source manifest differs from prepared stream provenance")
    # Validate both splits' paths, PCM and groups without using the other split's references.
    E4.PREPARE.select_sources(args.source_manifest, 1, args.split)
    metadata_keys = ("id", "audio", "language", "split", "group", "audio_sha256")
    metadata = [{key: row[key] for key in metadata_keys if key in row} for row in rows]
    verified = E4.EVALUATE.verify_audio_manifest(metadata, args.source_manifest.parent)
    selected = E4.EVALUATE.validate_manifest([row for row in rows if row.get("split") == args.split], args.split)
    references = {row["id"]: row for row in selected}
    del rows
    all_cases = E4.validate_cases(source, args.experiment.parent, references, verified)
    E4.verify_source_derivation(source, all_cases, args.source_manifest)
    cases = E4.choose_cases(all_cases, args.languages, args.conditions, args.controls, args.controls_only)
    if not cases:
        parser.error("Choose at least one prepared case")
    files = input_identities(args, policy, experiment_hash, source["source_manifest_sha256"], frozen_hash)
    check_inputs(args, files, engines)
    output.mkdir(mode=0o700, parents=True, exist_ok=True)
    if os.name != "nt" and output.stat().st_mode & 0o077:
        raise ValueError("Output must be private to the current OS user")
    shutil.copytree(args.experiment.parent / "replay-classes", output / "replay-classes")
    runtime = output / "kotlin-stdlib.jar"
    shutil.copyfile(args.kotlin_stdlib, runtime)
    classpath = E4.verify_replay(source, output, runtime)
    plan = dict(schema=1, scope=SCOPE, selected_split=args.split, policy=policy,
                frozen_plan_sha256=frozen_hash, experiment_sha256=files[args.experiment],
                source_manifest_sha256=files[args.source_manifest],
                cases=[dict(name=case["name"], total_samples=case["total_samples"], pcm_sha256=case["pcm_sha256"],
                            clips=[dict(id=row["id"], split=row["split"], audio_sha256=row["audio_sha256"])
                                   for row in case["clips"]]) for case in cases])
    plan_hash = save_bound_plan(output / "plan.json", plan)
    files[output / "plan.json"] = plan_hash
    check_inputs(args, files, engines)
    selections = []
    for case in cases:
        selection = prepare_vad(args, case, args.experiment.parent, output)
        selections.append(selection)
        files[output / (case["name"] + ".vad.json")] = selection["vad_output_sha256"]
        if selection["vad_input"]["derived_pcm"] is not None:
            files[output / selection["vad_input"]["derived_pcm"]] = selection["vad_input"]["pcm_sha256"]
        print(f"{case['name']}/offline-vad: {len(selection['ranges'])} bounded candidate ranges", flush=True)
    check_inputs(args, files, engines)
    E4.verify_replay(source, output, runtime)
    E4.verify_original_sources(verified)
    E4.save_json(output / "vad-selection.json", dict(scope=SCOPE, plan_sha256=plan_hash,
                   evidence="Candidate intervals and source-clip coverage only; no ASR accuracy or hallucination inference.",
                   selections=selections))
    if args.plan_only:
        print("Private VAD-only plan and ranges saved:", output)
        return
    replay = E4.KotlinReplay(args.java, classpath)
    records = []
    for case, selection in zip(cases, selections):
        records.extend(evaluate_case(args, case, selection, references, args.experiment.parent, output, replay))
    check_inputs(args, files, engines)
    E4.verify_replay(source, output, runtime)
    E4.verify_original_sources(verified)
    if any(digest_file(output / (row["case"] + ".vad.json")) != row["vad_output_sha256"] for row in selections):
        raise ValueError("VAD selections changed during ASR")
    if digest_file(output / "plan.json") != plan_hash:
        raise ValueError("Frozen run plan changed")
    with (output / "records.jsonl").open("x") as stream:
        for record in records:
            stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + "\n")
    groups = {}
    for record in records:
        groups.setdefault(f"{record['condition']}/context-{record['context']}", []).append(record)
    report = dict(scope=SCOPE, selected_split=args.split, plan_sha256=plan_hash,
                  timing="ASR wall times include cold per-chunk model loads; VAD runs once per case and is reported separately, shared across context ablations. No phone or independent-speaker claim.",
                  plan=plan, vad_selections=selections,
                  groups={key: dict(micro=E4.EVALUATE.aggregate(group),
                      languages={language: E4.EVALUATE.aggregate([row for row in group if row["language"] == language])
                                 for language in sorted({row["language"] for row in group})})
                          for key, group in groups.items()})
    E4.save_json(output / "report.json", report)
    print("Private offline VAD/ASR results saved:", output / "report.json")


if __name__ == "__main__":
    main()
