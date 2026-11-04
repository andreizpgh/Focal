#!/usr/bin/env python3
"""Reference-blind acoustic second-pass shadow analysis of paired cached runs.

Selection calls actual Kotlin Transcript.needsReview using only first-pass
probabilities/noSpeech. References are used for scoring after selection. No ASR,
downloads or app writes occur. Individual outputs must remain private/ignored.
"""
import argparse
import hashlib
import importlib.util
import json
import math
import os
from pathlib import Path
import re
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parent.parent
SPEC = importlib.util.spec_from_file_location("evaluate_stt", ROOT / "scripts/evaluate-stt.py")
EVAL = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVAL)
IDENTITY = ("id", "language", "split", "group", "silence", "audio_sha256", "pcm_sha256", "reference")
PROVENANCE = ("evaluator_sha256", "decoder_sha256", "model_sha256", "settings_sha256", "manifest_sha256", "selected_corpus_sha256")
TEXT_COUNTS = ("words", "word_errors", "characters", "character_errors", "normalized_characters", "normalized_character_errors", "literal_characters", "literal_character_errors")
POLICY = "Any in-original-audio segment satisfies actual Kotlin Transcript.needsReview; no eligible segments do not trigger."


def require(condition, message):
    if not condition:
        raise ValueError(message)


def text_hash(text):
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def valid_hash(value):
    return isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value) is not None


def load_run(path, split):
    raw = path.read_bytes()
    report_path = path.with_suffix(".json")
    report_bytes = report_path.read_bytes()
    report = json.loads(report_bytes)
    records = [json.loads(line) for line in raw.splitlines() if line.strip()]
    require(records and report["selected_split"] == split, "Run must contain the explicitly selected split")
    require(EVAL.json_hash(report["settings"]) == report["settings_sha256"], "Report settings hash mismatch")
    seen = set()
    for record in records:
        require(isinstance(record.get("id"), str) and record["id"] and record["id"] not in seen, "Run IDs must be unique nonempty strings")
        seen.add(record["id"])
        require(record.get("split") == split and record.get("language") in EVAL.LANGUAGES, "Unexpected split or language")
        require(isinstance(record.get("reference"), str) and isinstance(record.get("hypothesis"), str), "Reference/hypothesis must be strings")
        require(type(record.get("silence")) is bool, "Explicit silence flag required")
        require(all(valid_hash(record.get(key)) for key in ("audio_sha256", "pcm_sha256")), "Missing WAV/PCM content hashes")
        require(all(valid_hash(report.get(key)) and record["provenance"].get(key) == report[key] for key in PROVENANCE), "Record/report provenance mismatch")
        for key in ("audio_seconds", "wall_seconds"):
            value = record["metrics"].get(key)
            require(type(value) in {int, float} and math.isfinite(value) and value >= 0, "Invalid cached duration")
        require(record["metrics"]["audio_seconds"] > 0, "Audio duration must be positive")
    require(EVAL.json_hash([{key: record[key] for key in IDENTITY} for record in records]) == report["selected_corpus_sha256"], "Selected corpus hash mismatch")
    return records, report, dict(records_sha256=hashlib.sha256(raw).hexdigest(), report_sha256=hashlib.sha256(report_bytes).hexdigest())


def engine_libraries(report):
    components = {item["name"]: item["sha256"] for item in report["shared_libraries"]
                  if item["name"].startswith(("libwhisper", "libggml"))}
    require(any(name.startswith("libwhisper") for name in components), "Engine shared-library hashes are required")
    require(all(valid_hash(value) for value in components.values()), "Invalid engine library hash")
    return components


def validate_pairs(first, first_report, second, second_report, allow_mixed_runners=False):
    require(allow_mixed_runners or all(first_report[key] == second_report[key] for key in ("decoder_sha256", "evaluator_sha256")),
            "Runner/evaluator identities differ; explicit mixed-runner compatibility evidence is required")
    require(first_report["settings"] == second_report["settings"], "Cached decoding settings differ")
    require(first_report["manifest_sha256"] == second_report["manifest_sha256"] and
            first_report["selected_corpus_sha256"] == second_report["selected_corpus_sha256"], "Cached source cohort differs")
    require(engine_libraries(first_report) == engine_libraries(second_report), "Pinned engine libraries differ")
    other = {record["id"]: record for record in second}
    require({record["id"] for record in first} == set(other), "Paired IDs differ")
    pairs = []
    for baseline in first:
        candidate = other[baseline["id"]]
        require(all(baseline[key] == candidate[key] for key in IDENTITY), "Paired audio/reference identity differs")
        require(text_hash(baseline["reference"]) == text_hash(candidate["reference"]), "Reference hashes differ")
        require(baseline["effective_audio_context"] == candidate["effective_audio_context"] and
                baseline["metrics"]["audio_seconds"] == candidate["metrics"]["audio_seconds"], "Paired audio duration/context differs")
        require(isinstance(baseline.get("probe"), dict), "First pass requires probe confidence diagnostics")
        for record, report in ((baseline, first_report), (candidate, second_report)):
            if "probe" not in record:
                continue
            probe = record["probe"]
            require(probe.get("initial_prompt_sha256") == text_hash("") and probe.get("initial_prompt_bytes") == 0 and
                    probe.get("initial_prompt_utf16_units") == 0, "This isolated-clip selector requires no initial prompt")
            require(probe.get("threads") == report["settings"]["threads"] and probe.get("beam_size") == report["settings"]["beam_size"] and
                    probe.get("effective_audio_context") == record["effective_audio_context"], "Probe profile differs from cached settings")
            document = dict(model=dict(multilingual=True), probe=probe, transcription=probe["segments"])
            normalized = EVAL.probe_diagnostics(document, record["metrics"]["audio_seconds"])
            require(normalized["segments"] == probe["segments"], "Probe original-audio flags or segments are inconsistent")
            reconstructed, _ = EVAL.decoded_text(document, record["metrics"]["audio_seconds"])
            require(reconstructed == record["hypothesis"], "Probe segments do not reconstruct the cached hypothesis")
        pairs.append((baseline, candidate))
    return pairs


def validate_compatibility(first, first_report, witness, witness_report, second_report):
    require(witness_report["model_sha256"] == first_report["model_sha256"] and
            all(witness_report[key] == second_report[key] for key in ("decoder_sha256", "evaluator_sha256")), "Compatibility witness has wrong model/runner identities")
    require(all(witness_report[key] == first_report[key] for key in ("settings", "manifest_sha256", "selected_corpus_sha256")) and
            engine_libraries(witness_report) == engine_libraries(first_report), "Compatibility witness has different sources/settings/engine")
    other = {record["id"]: record for record in witness}
    require(set(other) == {record["id"] for record in first}, "Compatibility witness IDs differ")
    for record in first:
        paired = other[record["id"]]
        require(all(record[key] == paired[key] for key in (*IDENTITY, "hypothesis", "effective_audio_context")) and
                record["metrics"]["audio_seconds"] == paired["metrics"]["audio_seconds"], "Compatibility witness predictions or audio identity differ")
    return len(first)


class KotlinReview:
    def __init__(self, classpath_file, expected_jar_hash):
        raw = classpath_file.read_bytes()
        self.classpath_sha256 = hashlib.sha256(raw).hexdigest()
        classpath = raw.decode().strip()
        artifacts = [Path(entry).resolve() for entry in classpath.split(os.pathsep)]
        require(artifacts and all(path.is_file() for path in artifacts), "Classpath must list existing artifact files")
        contents = [path.read_bytes() for path in artifacts]
        self.artifacts = [dict(name=path.name, sha256=hashlib.sha256(data).hexdigest()) for path, data in zip(artifacts, contents)]
        require(valid_hash(expected_jar_hash) and self.artifacts[0]["sha256"] == expected_jar_hash, "Replay jar does not match the pinned hash")
        self.temporary = tempfile.TemporaryDirectory(prefix="focal-shadow-replay-")
        snapshots = [Path(self.temporary.name) / f"{index}-{path.name}" for index, path in enumerate(artifacts)]
        for path, data in zip(snapshots, contents):
            path.write_bytes(data)
        self.classpath = os.pathsep.join(map(str, snapshots))
        self.paths = [*artifacts, *snapshots]
        self.cache = {}

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self.temporary.cleanup()

    def __call__(self, no_speech, probabilities):
        key = no_speech, tuple(probabilities)
        if key not in self.cache:
            csv = ",".join(map(str, probabilities))
            require(len(probabilities) <= 1024 and len(csv) <= 65536, "Review transport limit exceeded")
            command = ["java", "-Xmx32m", "-cp", self.classpath, "app.focal.tools.TranscriptReplay", "review", str(no_speech), csv]
            result = subprocess.run(command, capture_output=True, text=True, timeout=10)
            require(result.returncode == 0 and result.stdout.strip() in {"true", "false"}, "Actual Kotlin review command failed")
            self.cache[key] = result.stdout.strip() == "true"
        return self.cache[key]


def select(record, reviewer):
    eligible = [(index, segment) for index, segment in enumerate(record["probe"]["segments"]) if segment["in_original_audio"]]
    flagged = [index for index, segment in eligible if reviewer(segment["noSpeech"], segment["probabilities"])]
    return dict(flagged=bool(flagged), flagged_segments=flagged, eligible_segments=len(eligible))


def score_pair(baseline, second, selection):
    # Selection is complete before any references or reference error counts are used.
    selected_text = second["hypothesis"] if selection["flagged"] else baseline["hypothesis"]
    scores = {}
    for label, hypothesis in (("baseline", baseline["hypothesis"]), ("second_pass", second["hypothesis"]), ("shadow", selected_text)):
        measured = EVAL.score(baseline["reference"], hypothesis, baseline["silence"], baseline["metrics"]["audio_seconds"], 0)
        scores[label] = {key: measured[key] for key in TEXT_COUNTS}
    for label, original in (("baseline", baseline), ("second_pass", second)):
        require(all(scores[label][key] == original["metrics"][key] for key in TEXT_COUNTS), "Cached text metrics do not match their stored hypotheses")
    base_time, second_time = baseline["metrics"]["wall_seconds"], second["metrics"]["wall_seconds"]
    return dict(id=baseline["id"], language=baseline["language"], split=baseline["split"], group=baseline["group"],
                audio_sha256=baseline["audio_sha256"], pcm_sha256=baseline["pcm_sha256"], reference=baseline["reference"],
                reference_sha256=text_hash(baseline["reference"]), baseline=baseline["hypothesis"], second_pass=second["hypothesis"],
                shadow=selected_text, baseline_sha256=text_hash(baseline["hypothesis"]), selection=selection, scores=scores,
                cached_cost=dict(baseline=base_time, second_pass=second_time,
                                 estimated_shadow=base_time + (second_time if selection["flagged"] else 0)))


def summarize(records):
    result = dict(clips=len(records), flagged_clips=sum(record["selection"]["flagged"] for record in records),
                  flagged_segments=sum(len(record["selection"]["flagged_segments"]) for record in records),
                  eligible_segments=sum(record["selection"]["eligible_segments"] for record in records),
                  no_eligible_segment_clips=sum(record["selection"]["eligible_segments"] == 0 for record in records))
    result["clip_coverage"] = result["flagged_clips"] / len(records)
    for label in ("baseline", "second_pass", "shadow"):
        totals = {key: sum(record["scores"][label][key] for record in records) for key in TEXT_COUNTS}
        for rate, errors, denominator in (("wer", "word_errors", "words"), ("lexical_cer", "character_errors", "characters"),
                                          ("normalized_cer", "normalized_character_errors", "normalized_characters"),
                                          ("literal_cer", "literal_character_errors", "literal_characters")):
            totals[rate] = EVAL.ratio(totals[errors], totals[denominator])
        result[label] = totals
    base_errors = [record["scores"]["baseline"]["word_errors"] for record in records]
    shadow_errors = [record["scores"]["shadow"]["word_errors"] for record in records]
    result.update(clips_improved=sum(new < old for old, new in zip(base_errors, shadow_errors)),
                  clips_worsened=sum(new > old for old, new in zip(base_errors, shadow_errors)),
                  initially_lexically_correct_clips_harmed=sum(old == 0 and new > 0 for old, new in zip(base_errors, shadow_errors)),
                  changed_hypothesis_clips=sum(record["baseline"] != record["shadow"] for record in records),
                  removed_errors_in_improved_clips=sum(max(old-new, 0) for old, new in zip(base_errors, shadow_errors)),
                  added_errors_in_worsened_clips=sum(max(new-old, 0) for old, new in zip(base_errors, shadow_errors)),
                  unflagged_clips_with_word_errors=sum(not record["selection"]["flagged"] and old > 0 for record, old in zip(records, base_errors)),
                  unflagged_word_errors=sum(old for record, old in zip(records, base_errors) if not record["selection"]["flagged"]),
                  flagged_lexically_correct_clips=sum(record["selection"]["flagged"] and old == 0 for record, old in zip(records, base_errors)))
    result["baseline_error_word_coverage"] = EVAL.ratio(result["baseline"]["word_errors"]-result["unflagged_word_errors"], result["baseline"]["word_errors"])
    character_deltas = [record["scores"]["shadow"]["character_errors"]-record["scores"]["baseline"]["character_errors"] for record in records]
    result["clips_with_increased_lexical_character_errors"] = sum(change > 0 for change in character_deltas)
    result["added_character_errors_in_worsened_clips"] = sum(max(change, 0) for change in character_deltas)
    result["cached_cold_seconds"] = {key: sum(record["cached_cost"][key] for record in records) for key in ("baseline", "second_pass", "estimated_shadow")}
    return result


def main():
    script_hash = EVAL.sha256(Path(__file__))
    scoring_hash = EVAL.sha256(ROOT / "scripts/evaluate-stt.py")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("baseline", type=Path)
    parser.add_argument("second_pass", type=Path)
    parser.add_argument("--split", choices=("dev", "test"), default="dev")
    parser.add_argument("--classpath-file", type=Path, default=Path("/tmp/focal-gate-runtime-classpath.txt"))
    parser.add_argument("--replay-jar-sha256", required=True)
    parser.add_argument("--allow-mixed-runners", action="store_true", help="Exploratory comparison only; requires a same-model compatibility witness")
    parser.add_argument("--compatibility-run", type=Path, help="Cached first-pass model run using the second pass's runner/evaluator")
    parser.add_argument("--output", type=Path, default=Path("build/stt/shadow-metrics.json"))
    parser.add_argument("--records-output", required=True, type=Path)
    args = parser.parse_args()
    try:
        first, first_report, first_source = load_run(args.baseline, args.split)
        second, second_report, second_source = load_run(args.second_pass, args.split)
        pairs = validate_pairs(first, first_report, second, second_report, args.allow_mixed_runners)
        compatibility = None
        if any(first_report[key] != second_report[key] for key in ("decoder_sha256", "evaluator_sha256")):
            require(args.compatibility_run is not None, "Mixed-runner analysis requires --compatibility-run")
            witness, witness_report, compatibility = load_run(args.compatibility_run, args.split)
            compatibility["matching_hypotheses"] = validate_compatibility(first, first_report, witness, witness_report, second_report)
        records_output = EVAL.private_records_path(args.records_output)
        with KotlinReview(args.classpath_file, args.replay_jar_sha256) as reviewer:
            inputs = {path.resolve() for path in (args.baseline, args.second_pass, args.baseline.with_suffix(".json"), args.second_pass.with_suffix(".json"),
                                                 args.classpath_file, Path(__file__), ROOT / "scripts/evaluate-stt.py", *reviewer.paths)}
            if args.compatibility_run:
                inputs.update((args.compatibility_run.resolve(), args.compatibility_run.with_suffix(".json").resolve()))
            outputs = (args.output.resolve(), records_output)
            require(outputs[0] != outputs[1] and not (outputs[0].exists() and outputs[1].exists() and outputs[0].samefile(outputs[1])), "Output paths must be distinct")
            for output in outputs:
                require(output not in inputs and not any(output.exists() and source.exists() and output.samefile(source) for source in inputs), "Outputs cannot replace source records/reports/replay artifacts")
            selections = [select(baseline, reviewer) for baseline, _ in pairs]
        records = [score_pair(baseline, candidate, selection) for (baseline, candidate), selection in zip(pairs, selections)]
    except (OSError, ValueError, KeyError, TypeError, subprocess.TimeoutExpired) as error:
        parser.error(str(error))
    report = dict(scope="Shadow isolated-clip cached analysis only; no application changes, inference or downloads", split=args.split,
                  policy=POLICY, selector_sha256=script_hash, scoring_script_sha256=scoring_hash,
                  replay_artifacts=reviewer.artifacts, classpath_sha256=reviewer.classpath_sha256, baseline_source=first_source, second_pass_source=second_source,
                  replay_binary_note="Pinned Kotlin policy binary; this hash does not independently prove its source origin.",
                  runner_identities={label: {key: report[key] for key in ("decoder_sha256", "evaluator_sha256")} for label, report in (("baseline", first_report), ("second_pass", second_report))},
                  mixed_runner_opt_in=args.allow_mixed_runners, compatibility_evidence=compatibility,
                  baseline_model_sha256=first_report["model_sha256"], second_pass_model_sha256=second_report["model_sha256"],
                  settings_sha256=first_report["settings_sha256"], selected_corpus_sha256=first_report["selected_corpus_sha256"],
                  micro=summarize(records), languages={language: summarize([record for record in records if record["language"] == language])
                                                      for language in sorted({record["language"] for record in records})},
                  limitations=["Uncalibrated confidence can miss incorrect words, including unflagged and empty-output clips.",
                               "References score outcomes only after selection; no human span/critical-error adjudication or speaker confidence intervals.",
                               "Cached cold cost is an additive estimate of previous runs, not measured combined latency; excludes selection, integration and phone costs.",
                               "Clip-level error increases do not count every introduced word error inside otherwise improved clips; WER ties can still worsen CER."])
    if compatibility is not None:
        report["limitations"].append("Different recorded frontends/evaluator versions explicitly allowed; same-model exact hypothesis agreement on this cohort is compatibility evidence, not general wrapper or build equivalence.")
    records_output.parent.mkdir(parents=True, exist_ok=True)
    records_output.write_text("".join(json.dumps(record, ensure_ascii=False) + "\n" for record in records), encoding="utf-8")
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2, allow_nan=False) + "\n")
    print(json.dumps(report, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
