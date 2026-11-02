#!/usr/bin/env python3
"""Measure a pinned whisper-cli against an explicit local speech corpus.

Manifest: JSON array of {audio, language, text}, with optional id, split (dev/test),
speaker/session group, and expected audio_sha256. Blank references require
silence:true. Duplicate PCM across dev/test, or within one language/split, is
rejected; a shared silence fixture may be tested in different languages in the
same split. Audio is mono
16 kHz PCM16 WAV, at most 30 seconds. References are used only after inference.
Reports contain aggregates; --records-output explicitly saves private JSONL under
a Git-ignored path or outside the repository. Host timings are not phone timings.
"""
import argparse
import hashlib
import json
import math
from pathlib import Path
import platform
import re
import subprocess
import tempfile
import time
import unicodedata
import wave

LANGUAGES = {"en", "ru", "de", "fr", "es", "it", "pt"}
REPOSITORY = Path(__file__).resolve().parent.parent


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def json_hash(value):
    return hashlib.sha256(json.dumps(value, sort_keys=True, ensure_ascii=False).encode("utf-8")).hexdigest()


def words(text):
    return re.findall(r"[^\W_]+", unicodedata.normalize("NFC", text).casefold())


def distance(reference, hypothesis):
    row = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        next_row = [i]
        for j, right in enumerate(hypothesis, 1):
            next_row.append(min(next_row[-1] + 1, row[j] + 1, row[j - 1] + (left != right)))
        row = next_row
    return row[-1]


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def percentile(values, probability):
    """Linear interpolation between ordered observations."""
    ordered = sorted(values)
    position = (len(ordered) - 1) * probability
    lower, upper = math.floor(position), math.ceil(position)
    return ordered[lower] + (ordered[upper] - ordered[lower]) * (position - lower)


def validate_manifest(corpus, selected_split="all"):
    if not isinstance(corpus, list) or not corpus:
        raise ValueError("Manifest must be a nonempty JSON array")
    selected, seen_ids, group_splits = [], set(), {}
    for index, original in enumerate(corpus):
        if not isinstance(original, dict):
            raise ValueError("Each manifest entry must be an object")
        item = dict(original)
        if item.get("language") not in LANGUAGES or not isinstance(item.get("text"), str):
            raise ValueError("Each fixture needs text and a supported language code")
        if not isinstance(item.get("audio"), str) or not item["audio"]:
            raise ValueError("Each fixture needs an audio path")
        if "audio_sha256" in item and (not isinstance(item["audio_sha256"], str) or
                                        re.fullmatch(r"[0-9a-fA-F]{64}", item["audio_sha256"]) is None):
            raise ValueError("Expected audio_sha256 must be a 64-digit hexadecimal SHA-256")
        if "silence" in item and type(item["silence"]) is not bool:
            raise ValueError("silence must be a boolean")
        if item.get("silence", False):
            if item["text"] != "":
                raise ValueError("A silence fixture must have an empty reference")
        elif not words(item["text"]):
            raise ValueError("Blank references require explicit silence:true")
        item["id"] = item.get("id", f"fixture-{index + 1}")
        if not isinstance(item["id"], str) or not item["id"] or item["id"] in seen_ids:
            raise ValueError("Fixture IDs must be nonempty unique strings")
        seen_ids.add(item["id"])
        split = item.get("split")
        if split is not None and split not in {"dev", "test"}:
            raise ValueError("Explicit splits must be dev or test")
        if selected_split != "all" and split is None:
            raise ValueError("--split requires an explicit split for every fixture")
        if "group" in item:
            if not isinstance(item["group"], str) or not item["group"]:
                raise ValueError("Explicit groups must be nonempty speaker/session strings")
            if split:
                group_splits.setdefault(item["group"], set()).add(split)
        if selected_split == "all" or split == selected_split:
            selected.append(item)
    if any(len(splits) > 1 for splits in group_splits.values()):
        raise ValueError("A declared speaker/session group occurs in both dev and test")
    if not selected:
        raise ValueError("No fixtures match the selected split")
    return selected


def private_records_path(path):
    path = path.resolve()
    if not path.is_relative_to(REPOSITORY):
        return path
    checked = subprocess.run(["git", "-C", str(REPOSITORY), "check-ignore", "--quiet", "--", str(path)],
                             capture_output=True, timeout=5)
    if checked.returncode != 0 or ".git" in path.relative_to(REPOSITORY).parts:
        raise ValueError("Private records must use a Git-ignored path such as build/, or a path outside the repository")
    return path


def engine_provenance(cli):
    """The executable alone does not identify a dynamically linked engine."""
    result = {"decoder_sha256": sha256(cli), "shared_libraries": [], "dependency_hash_status": "unavailable"}
    if platform.system() == "Linux":
        try:
            linked = subprocess.run(["ldd", str(cli.resolve())], capture_output=True, text=True, timeout=10)
            paths = set(re.findall(r"(?:=>\s+|^\s*)(/[^\n]+?)\s+\(0x[0-9a-fA-F]+\)", linked.stdout, re.MULTILINE))
            for name in sorted(paths):
                path = Path(name)
                result["shared_libraries"].append({"name": path.name, "sha256": sha256(path), "bytes": path.stat().st_size})
            result["dependency_hash_status"] = "hashed" if paths else "no_dynamic_dependencies_reported"
            if "not found" in linked.stdout:
                raise ValueError("Decoder has an unresolved shared-library dependency")
        except (OSError, subprocess.TimeoutExpired):
            pass
    cache = cli.resolve().parent.parent / "CMakeCache.txt"
    if cache.is_file():
        result["cmake_cache_sha256"] = sha256(cache)
        result["build_options"] = {}
        for line in cache.read_text().splitlines():
            match = re.match(r"((?:GGML|WHISPER)_[A-Z0-9_]+|BUILD_SHARED_LIBS):BOOL=(.*)", line)
            if not match:
                match = re.match(r"(CMAKE_BUILD_TYPE):STRING=(.*)", line)
            if match:
                result["build_options"][match[1]] = match[2]
    try:
        commit = subprocess.run(["git", "-C", str(REPOSITORY / "third_party/whisper.cpp"), "rev-parse", "HEAD"],
                                capture_output=True, text=True, timeout=5)
        if commit.returncode == 0:
            result["source_checkout_commit"] = commit.stdout.strip()
            result["source_checkout_note"] = "Informational checkout only; binary/library hashes identify the measured engine, not proof it was built from this checkout."
    except (OSError, subprocess.TimeoutExpired):
        pass
    return result


def audio_context_option(value):
    if value == "auto":
        return value
    try:
        number = int(value)
    except ValueError as error:
        raise argparse.ArgumentTypeError("Audio context must be 0 (full), a positive integer, or auto") from error
    if number < 0:
        raise argparse.ArgumentTypeError("Audio context must be nonnegative")
    return number


def effective_audio_context(setting, seconds):
    if setting == "auto":
        return min(1500, max(768, math.ceil((seconds + 2) * 50 / 256) * 256))
    return setting


def decoder_command(args, audio, language, output, audio_context):
    command = [str(args.cli.resolve()), "-m", str(args.model.resolve()), "-f", str(audio),
               "-l", language, "-t", str(2 if args.baseline else args.threads),
               "-bs", str(1 if args.baseline else args.beam_size), "-bo", "1", "-tp", "0", "-tpi", "0",
               "-mc", "64", "-ng", "-fa", "-sns", "-oj", "-of", str(output)]
    if audio_context:
        command += ["-ac", str(audio_context)]
    if args.baseline:
        command += ["-ml", "180", "-sow"]
    return command


def read_audio(path):
    with wave.open(str(path), "rb") as wav:
        if (wav.getnchannels(), wav.getframerate(), wav.getsampwidth(), wav.getcomptype()) != (1, 16000, 2, "NONE"):
            raise ValueError("Fixtures must be mono 16 kHz PCM16 WAV")
        frames = wav.getnframes()
        if not 0 < frames <= 30 * 16000:
            raise ValueError("Each fixture must contain more than zero and at most 30 seconds of audio")
        pcm = wav.readframes(frames)
        if len(pcm) != frames * 2:
            raise ValueError("Fixture WAV is truncated")
    return pcm, frames / 16000


def verify_audio_manifest(corpus, directory):
    """Preflight every split, including filtered-out audio, before any inference."""
    verified, seen = {}, {}
    for index, item in enumerate(corpus):
        path = (directory / item["audio"]).resolve()
        try:
            pcm, seconds = read_audio(path)
            audio_hash = sha256(path)
        except (OSError, ValueError, wave.Error, EOFError) as error:
            raise ValueError(f"Invalid audio on fixture {index + 1}: {error}") from error
        if "audio_sha256" in item and item["audio_sha256"].lower() != audio_hash:
            raise ValueError(f"Supplied audio_sha256 does not match fixture {index + 1}")
        pcm_hash = hashlib.sha256(pcm).hexdigest()
        split, language = item.get("split"), item["language"]
        for prior_split, prior_language in seen.get(pcm_hash, []):
            if split is not None and prior_split is not None and split != prior_split:
                raise ValueError("Identical audio content occurs in both dev and test")
            if (split, language) == (prior_split, prior_language):
                raise ValueError("Repeated audio content within the same language and split")
        seen.setdefault(pcm_hash, []).append((split, language))
        verified[item["id"]] = dict(path=path, audio_sha256=audio_hash, pcm_sha256=pcm_hash, seconds=seconds)
    return verified


def decoded_text(document, seconds):
    if document.get("model", {}).get("multilingual") is not True:
        raise ValueError("Evaluation requires an explicitly multilingual model")
    segments = document.get("transcription")
    if not isinstance(segments, list):
        raise ValueError("Decoder JSON has no transcription segments")
    texts, discarded = [], 0
    # Recover the integral PCM frame count before truncating to milliseconds,
    # matching Kotlin's pcm.size / 32L without binary-float rounding at the edge.
    duration_ms = round(seconds * 16000) // 16
    for segment in segments:
        start, end = segment["offsets"]["from"], segment["offsets"]["to"]
        text = segment["text"].strip()
        if not text:
            continue
        if start >= duration_ms or end <= 0 or end <= start:
            discarded += 1
            continue
        texts.append(text)
    return " ".join(texts), discarded


def probe_diagnostics(document, seconds):
    """Preserve opt-in confidence evidence privately; never use it for scoring."""
    if "probe" not in document:
        return None
    profile = document["probe"]
    if not isinstance(profile, dict) or profile.get("schema") != 1:
        raise ValueError("Unsupported probe diagnostics schema")
    for key in ("load_seconds", "decode_seconds"):
        value = profile.get(key)
        if type(value) not in {int, float} or not math.isfinite(value) or value < 0:
            raise ValueError("Probe phase timings must be finite nonnegative numbers")
    peak = profile.get("peak_rss_bytes")
    if peak is not None and (type(peak) is not int or peak < 0):
        raise ValueError("Probe peak RSS must be nonnegative bytes or null")
    if "initial_prompt_sha256" in profile and (not isinstance(profile["initial_prompt_sha256"], str) or
                                              not re.fullmatch(r"[0-9a-f]{64}", profile["initial_prompt_sha256"])):
        raise ValueError("Invalid probe prompt hash")
    segments = []
    duration_ms = round(seconds * 16000) // 16
    for segment in document["transcription"]:
        start, end = segment["offsets"]["from"], segment["offsets"]["to"]
        probabilities, no_speech = segment.get("probabilities"), segment.get("noSpeech")
        if type(start) is not int or type(end) is not int or not isinstance(probabilities, list):
            raise ValueError("Probe segments require integer offsets and token probabilities")
        for value in [no_speech, *probabilities]:
            if type(value) not in {int, float} or not math.isfinite(value) or not 0 <= value <= 1:
                raise ValueError("Probe confidence features must be finite values in [0,1]")
        segments.append(dict(offsets={"from": start, "to": end}, text=segment["text"], probabilities=probabilities,
                             noSpeech=no_speech, in_original_audio=bool(segment["text"].strip()) and
                             start < duration_ms and end > 0 and end > start))
    return {**profile, "segments": segments}


def aggregate_probe(records):
    probes = [record["probe"] for record in records if "probe" in record]
    if not probes:
        return None
    result = dict(clips=len(probes), scope="Internal cold host process phases, separate from wall_seconds; not phone costs")
    for key in ("load_seconds", "decode_seconds"):
        values = [probe[key] for probe in probes]
        result[key] = dict(total=sum(values), p50=percentile(values, 0.5), p95=percentile(values, 0.95))
    peaks = [probe["peak_rss_bytes"] for probe in probes if probe["peak_rss_bytes"] is not None]
    result["peak_rss_bytes"] = dict(clips=len(peaks), maximum=max(peaks) if peaks else None)
    return result


def score(reference, hypothesis, silence, seconds, elapsed):
    expected, actual = words(reference), words(hypothesis)
    reference_chars, actual_chars = "".join(expected), "".join(actual)
    normalized_reference, normalized_actual = " ".join(expected), " ".join(actual)
    literal_reference = unicodedata.normalize("NFC", reference)
    literal_actual = unicodedata.normalize("NFC", hypothesis)
    return dict(clips=1, speech_clips=int(not silence), silence_clips=int(silence),
                words=len(expected), word_errors=distance(expected, actual),
                characters=len(reference_chars), character_errors=distance(reference_chars, actual_chars),
                normalized_characters=len(normalized_reference), normalized_character_errors=distance(normalized_reference, normalized_actual),
                literal_characters=len(literal_reference), literal_character_errors=distance(literal_reference, literal_actual),
                silence_inserted_words=len(actual) if silence else 0, silence_seconds=seconds if silence else 0,
                audio_seconds=seconds, wall_seconds=elapsed)


def aggregate(records):
    totals = {key: sum(record["metrics"][key] for record in records) for key in records[0]["metrics"]}
    totals.update(wer=ratio(totals["word_errors"], totals["words"]),
                  cer=ratio(totals["character_errors"], totals["characters"]),
                  lexical_cer=ratio(totals["character_errors"], totals["characters"]),
                  normalized_cer=ratio(totals["normalized_character_errors"], totals["normalized_characters"]),
                  literal_cer=ratio(totals["literal_character_errors"], totals["literal_characters"]),
                  silence_inserted_words_per_minute=ratio(totals["silence_inserted_words"] * 60, totals["silence_seconds"]),
                  real_time_factor=totals["wall_seconds"] / totals["audio_seconds"],
                  wall_seconds_p50=percentile([record["metrics"]["wall_seconds"] for record in records], 0.5),
                  wall_seconds_p95=percentile([record["metrics"]["wall_seconds"] for record in records], 0.95))
    return totals


def main():
    # Capture the evaluator's identity before a long run; a later workspace edit
    # must not attribute already-loaded Python code to the newly edited source.
    evaluator_hash = sha256(Path(__file__).resolve())
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--cli", type=Path, default=Path("build/whisper-host/bin/whisper-cli"))
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/ggml-base-q5_1.bin"))
    parser.add_argument("--threads", type=int, default=4, choices=range(1, 5))
    parser.add_argument("--beam-size", type=int, default=3, choices=range(1, 9))
    parser.add_argument("--audio-context", type=audio_context_option, default=0,
                        help="Experimental encoder context override: 0 uses full context; auto uses length+2s rounded to 256, min768/max1500")
    parser.add_argument("--baseline", action="store_true", help="Historical greedy/two-thread/word-alignment configuration; not current E0")
    parser.add_argument("--split", choices=("all", "dev", "test"), default="all")
    parser.add_argument("--require-all-languages", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("build/stt-metrics.json"))
    parser.add_argument("--records-output", type=Path, help="Opt-in PRIVATE JSONL with references/hypotheses; ignored path or outside repository")
    parser.add_argument("--clip-timeout", type=int, default=180, help="Maximum decoder seconds per clip")
    args = parser.parse_args()
    if args.clip_timeout <= 0:
        parser.error("Clip timeout must be positive")
    if not args.model.is_file() or not args.cli.is_file():
        parser.error("Supply an existing verified model and built whisper-cli")
    try:
        manifest_bytes = args.manifest.read_bytes()
        manifest_hash = hashlib.sha256(manifest_bytes).hexdigest()
        raw_corpus = json.loads(manifest_bytes)
        all_corpus = validate_manifest(raw_corpus)
        corpus = validate_manifest(raw_corpus, args.split)
        verified_audio = verify_audio_manifest(all_corpus, args.manifest.parent)
        if args.records_output:
            args.records_output = private_records_path(args.records_output)
        audio_paths = [verified_audio[item["id"]]["path"] for item in corpus]
        inputs = {args.manifest.resolve(), args.model.resolve(), args.cli.resolve(),
                  *(entry["path"] for entry in verified_audio.values())}
        outputs = [args.output.resolve()] + ([args.records_output] if args.records_output else [])
        if len(set(outputs)) != len(outputs) or any(path in inputs for path in outputs):
            raise ValueError("Output paths must be distinct and must not overwrite inputs")
    except (OSError, ValueError) as error:
        parser.error(str(error))
    if args.require_all_languages and {item["language"] for item in corpus} != LANGUAGES:
        parser.error("Selected evaluation split must contain all seven supported languages")
    engine = engine_provenance(args.cli)
    model_hash = sha256(args.model)
    settings = dict(threads=2 if args.baseline else args.threads, beam_size=1 if args.baseline else args.beam_size,
                    max_text_context=64, no_context=True, no_timestamps=False, token_timestamps=args.baseline,
                    use_gpu=False, flash_attention=True, temperature=0, temperature_increment=0,
                    best_of=1, suppress_non_speech_tokens=True, audio_context=args.audio_context,
                    initial_prompt="none; isolated clips", short_audio_padding_seconds=1)
    records = []
    with tempfile.TemporaryDirectory(prefix="focal-eval-") as temporary:
        for index, (item, audio) in enumerate(zip(corpus, audio_paths)):
            try:
                pcm, seconds = read_audio(audio)
            except (OSError, ValueError, wave.Error, EOFError) as error:
                raise ValueError(f"Invalid audio on fixture {index + 1}: {error}") from error
            verified = verified_audio[item["id"]]
            if hashlib.sha256(pcm).hexdigest() != verified["pcm_sha256"] or sha256(audio) != verified["audio_sha256"]:
                raise ValueError(f"Audio changed after integrity preflight on fixture {index + 1}")
            if len(pcm) < 32000:
                audio = Path(temporary) / f"padded-{index}.wav"
                with wave.open(str(audio), "wb") as wav:
                    wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                    wav.writeframes(pcm + bytes(32000 - len(pcm)))
            output = Path(temporary) / str(index)
            audio_context = effective_audio_context(args.audio_context, seconds)
            command = decoder_command(args, audio, item["language"], output, audio_context)
            start = time.monotonic()
            try:
                result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=args.clip_timeout)
            except subprocess.TimeoutExpired as error:
                raise RuntimeError(f"Decoder timed out on fixture {index + 1}") from error
            elapsed = time.monotonic() - start
            if result.returncode:
                raise RuntimeError(f"Decoder failed on fixture {index + 1}; exit {result.returncode}")
            try:
                document = json.loads(output.with_suffix(".json").read_text(encoding="utf-8"))
                hypothesis, discarded = decoded_text(document, seconds)
                diagnostics = probe_diagnostics(document, seconds)
                full_audio_context = document["model"]["audio"]["ctx"]
                if type(full_audio_context) is not int or full_audio_context <= 0:
                    raise ValueError("Decoder JSON has no valid model audio context")
            except (OSError, ValueError, KeyError, TypeError) as error:
                raise RuntimeError(f"Invalid decoder output on fixture {index + 1}") from error
            records.append(dict(id=item["id"], language=item["language"], split=item.get("split"), group=item.get("group"),
                                silence=item.get("silence", False), audio_sha256=verified["audio_sha256"], pcm_sha256=verified["pcm_sha256"],
                                reference=item["text"],
                                hypothesis=hypothesis, effective_audio_context=audio_context or full_audio_context,
                                discarded_padding_or_invalid_segments=discarded,
                                metrics=score(item["text"], hypothesis, item.get("silence", False), seconds, elapsed)))
            if diagnostics is not None:
                records[-1]["probe"] = diagnostics
    corpus_identity = [{key: record[key] for key in ("id", "language", "split", "group", "silence", "audio_sha256", "pcm_sha256", "reference")} for record in records]
    groups = {item["group"] for item in corpus if "group" in item}
    report = dict(scope="Host isolated-clip decoder only; each trial includes process/model load; excludes capture, queue and thermal delays",
                  mode="historical_greedy" if args.baseline else f"beam{args.beam_size}",
                  threads=settings["threads"], settings=settings, settings_sha256=json_hash(settings),
                  model_sha256=model_hash, model_bytes=args.model.stat().st_size, **engine,
                  evaluator_sha256=evaluator_hash, manifest_sha256=manifest_hash,
                  selected_corpus_sha256=json_hash(corpus_identity), selected_split=args.split,
                  grouping=dict(explicit_group_count=len(groups), clips_without_group=sum("group" not in item for item in corpus),
                                confidence_intervals="not estimated; declared groups do not establish independent speakers"),
                  normalization="NFC+casefold word tokens; lexical CER joins words; normalized CER joins words with spaces; literal CER uses NFC and preserves case/punctuation/interior spacing. Decoder segment edges are trimmed and segments joined by one space.",
                  timing="One cold process per clip; P50/P95 are linearly interpolated observed clip latencies, not repeated warm trials or phone latency.",
                  host=dict(system=platform.system(), architecture=platform.machine()),
                  micro=aggregate(records), languages={language: aggregate([record for record in records if record["language"] == language])
                                                       for language in sorted({item["language"] for item in corpus})})
    probe_totals = aggregate_probe(records)
    if probe_totals is not None:
        report["probe_measurements"] = probe_totals
    if args.records_output:
        args.records_output.parent.mkdir(parents=True, exist_ok=True)
        provenance = {key: report[key] for key in ("evaluator_sha256", "model_sha256", "decoder_sha256", "settings_sha256", "manifest_sha256", "selected_corpus_sha256")}
        with args.records_output.open("w", encoding="utf-8") as stream:
            for record in records:
                stream.write(json.dumps({**record, "provenance": provenance}, ensure_ascii=False) + "\n")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
