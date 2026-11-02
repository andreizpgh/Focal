#!/usr/bin/env python3
"""Score paired local ASR corrections; no inference, downloads, or transcript output.

JSONL schema and annotation rules: docs/stt-quality-experiments.md.
Edit offsets index Unicode code points in the immutable original string, before
normalization. Factual span precision and introduced errors require human labels;
WER improvement alone does not supply those labels.
"""
import argparse
from collections import defaultdict
import hashlib
import json
import math
from pathlib import Path
import random
import re
import unicodedata

LANGUAGES = frozenset({"en", "ru", "de", "fr", "es", "it", "pt"})
CRITICAL = ("names", "numbers", "negation")
MEASURES = ("correction_ms", "finish_to_ready_ms", "peak_rss_bytes", "peak_pss_bytes", "energy_mj", "peak_thermal_status")


def words(text):
    return re.findall(r"[^\W_]+", unicodedata.normalize("NFC", text).casefold())


def distance(left, right):
    row = list(range(len(right) + 1))
    for i, first in enumerate(left, 1):
        new = [i]
        for j, second in enumerate(right, 1):
            new.append(min(new[-1] + 1, row[j] + 1, row[j - 1] + (first != second)))
        row = new
    return row[-1]


def nonnegative_integer(value):
    return type(value) is int and value >= 0


def apply_edits(original, proposals):
    accepted = []
    for edit in proposals:
        if not isinstance(edit, dict):
            raise ValueError("Each proposal must be an object")
        if edit.get("decision") not in {"accept", "abstain"}:
            raise ValueError("Each proposal needs an accept/abstain decision")
        start, end = edit.get("start"), edit.get("end")
        if not nonnegative_integer(start) or not nonnegative_integer(end) or not start <= end <= len(original):
            raise ValueError("Proposal offsets must index the original Unicode string")
        if not isinstance(edit.get("replacement"), str):
            raise ValueError("A proposal replacement must be a string")
        if "confidence" in edit and (type(edit["confidence"]) not in {int, float} or not math.isfinite(edit["confidence"]) or not 0 <= edit["confidence"] <= 1):
            raise ValueError("Confidence must be finite and between zero and one")
        if edit.get("judgement") not in {None, "correct", "incorrect", "uncertain"}:
            raise ValueError("Unknown human judgement")
        if "introduced_errors" in edit and not nonnegative_integer(edit["introduced_errors"]):
            raise ValueError("Introduced errors must be an adjudicated nonnegative count")
        if edit.get("judgement") == "correct" and edit.get("introduced_errors", 0) != 0:
            raise ValueError("A wholly correct edit cannot introduce an error")
        if edit["decision"] == "accept":
            if original[start:end] == edit["replacement"]:
                raise ValueError("An accepted proposal must change the original")
            accepted.append(edit)
    accepted.sort(key=lambda edit: (edit["start"], edit["end"]))
    for previous, current in zip(accepted, accepted[1:]):
        if current["start"] < previous["end"] or current["start"] == previous["start"]:
            raise ValueError("Accepted proposals overlap or share an ambiguous insertion point")
    result = original
    for edit in reversed(accepted):
        result = result[:edit["start"]] + edit["replacement"] + result[edit["end"]:]
    return result, accepted


def score_record(item):
    if not isinstance(item.get("id"), str) or not item["id"] or item.get("language") not in LANGUAGES:
        raise ValueError("Each record needs a unique ID and one supported language")
    for key in ("reference", "original"):
        if not isinstance(item.get(key), str):
            raise ValueError("Reference and original must be strings; silence may be empty")
    if "group" in item and (not isinstance(item["group"], str) or not item["group"]):
        raise ValueError("A speaker/session group must be a nonempty string")
    if not isinstance(item.get("proposals"), list):
        raise ValueError("Each record must provide a proposals list, even for abstention")
    expected_hash = item.get("original_sha256")
    if expected_hash is not None and expected_hash != hashlib.sha256(item["original"].encode()).hexdigest():
        raise ValueError("The original transcript revision does not match its hash")
    candidate, accepted = apply_edits(item["original"], item.get("proposals", []))
    if "candidate" in item and item["candidate"] != candidate:
        raise ValueError("Candidate text differs from applying accepted proposals")
    reference, original, corrected = map(words, (item["reference"], item["original"], candidate))
    reference_chars = "".join(reference)
    reference_literal = unicodedata.normalize("NFC", item["reference"])
    original_literal = unicodedata.normalize("NFC", item["original"])
    candidate_literal = unicodedata.normalize("NFC", candidate)
    critical = item.get("critical_errors", {})
    if not isinstance(critical, dict):
        raise ValueError("Critical error annotations must be an object")
    for kind, values in critical.items():
        if kind not in CRITICAL or not isinstance(values, dict) or set(values) != {"original", "candidate"} or not all(nonnegative_integer(value) for value in values.values()):
            raise ValueError("Critical errors need original/candidate counts for names, numbers, or negation")
    measurements = item.get("measurements", [])
    if not isinstance(measurements, list):
        raise ValueError("Measurements must be a list")
    for measure in measurements:
        if not isinstance(measure, dict) or not isinstance(measure.get("device"), str) or not measure["device"] or measure.get("phase") not in {"cold", "warm"} or measure.get("variant") not in {"original", "candidate"}:
            raise ValueError("A measurement needs a device label, original/candidate variant, and cold/warm phase")
        for key in MEASURES:
            if key in measure and (type(measure[key]) not in {int, float} or not math.isfinite(measure[key]) or measure[key] < 0):
                raise ValueError("Resource measurements must be finite and nonnegative")
        if "peak_thermal_status" in measure and (type(measure["peak_thermal_status"]) is not int or measure["peak_thermal_status"] > 6):
            raise ValueError("Thermal status must be an Android status from zero to six")
    return dict(language=item["language"], group=item.get("group", item["id"]), explicit_group="group" in item, reference_words=len(reference),
                original_errors=distance(reference, original), candidate_errors=distance(reference, corrected),
                reference_characters=len(reference_chars), original_character_errors=distance(reference_chars, "".join(original)),
                candidate_character_errors=distance(reference_chars, "".join(corrected)),
                reference_literal_characters=len(reference_literal), original_literal_errors=distance(reference_literal, original_literal),
                candidate_literal_errors=distance(reference_literal, candidate_literal),
                changed=candidate != item["original"], accepted=accepted,
                proposals=len(item.get("proposals", [])), critical=critical, measurements=measurements,
                silence=not reference, original_insertions=len(original) if not reference else 0,
                candidate_insertions=len(corrected) if not reference else 0)


def ratio(numerator, denominator):
    return numerator / denominator if denominator else None


def percentile(values, fraction):
    if not values:
        return None
    ordered = sorted(values)
    position = (len(ordered) - 1) * fraction
    low, high = math.floor(position), math.ceil(position)
    return ordered[low] + (ordered[high] - ordered[low]) * (position - low)


def wilson_lower(correct, total):
    """Lower bound of a two-sided 95% Wilson interval; no model-confidence input."""
    if not total:
        return None
    z = 1.959963984540054
    p = correct / total
    return (p + z*z/(2*total) - z*math.sqrt(p*(1-p)/total + z*z/(4*total*total))) / (1 + z*z/total)


def paired_wer_interval(rows, repetitions, seed):
    # A single reference-bearing group cannot establish between-group variation.
    # Too few replicates also cannot support stable 2.5th/97.5th percentiles.
    reference_groups = {row["group"] for row in rows if row["reference_words"]}
    if repetitions < 1000 or len(reference_groups) < 2:
        return None
    rng = random.Random(seed)
    groups = defaultdict(list)
    for row in rows:
        groups[row["group"]].append(row)
    grouped_rows = list(groups.values())
    changes = []
    for _ in range(repetitions):
        sample = [row for group in rng.choices(grouped_rows, k=len(grouped_rows)) for row in group]
        denominator = sum(row["reference_words"] for row in sample)
        if denominator:
            changes.append(sum(row["candidate_errors"] - row["original_errors"] for row in sample) / denominator)
    return [percentile(changes, .025), percentile(changes, .975)]


def summarize(rows, repetitions=1000, seed=0):
    totals = {key: sum(row[key] for row in rows) for key in (
        "reference_words", "original_errors", "candidate_errors", "reference_characters", "original_character_errors",
        "candidate_character_errors", "reference_literal_characters", "original_literal_errors", "candidate_literal_errors",
        "proposals", "original_insertions", "candidate_insertions")}
    edits = [edit for row in rows for edit in row["accepted"]]
    labelled = [edit for edit in edits if edit.get("judgement") is not None]
    correct = sum(edit["judgement"] == "correct" for edit in labelled)
    introduced = [edit["introduced_errors"] for edit in edits if "introduced_errors" in edit]
    totals.update(clips=len(rows), changed_clips=sum(row["changed"] for row in rows),
                  independent_groups=len({row["group"] for row in rows}), explicit_group_clips=sum(row["explicit_group"] for row in rows),
                  reference_bearing_groups=len({row["group"] for row in rows if row["reference_words"]}),
                  improved_clips=sum(row["candidate_errors"] < row["original_errors"] for row in rows),
                  regressed_clips=sum(row["candidate_errors"] > row["original_errors"] for row in rows),
                  originally_correct_clips_harmed=sum(row["original_errors"] == 0 < row["candidate_errors"] for row in rows),
                  silence_clips=sum(row["silence"] for row in rows), accepted_spans=len(edits),
                  abstained_proposals=totals["proposals"] - len(edits), unlabelled_accepted_spans=len(edits)-len(labelled),
                  correct_spans=correct, incorrect_or_uncertain_spans=len(labelled)-correct,
                  adjudicated_introduced_errors=sum(introduced), unscored_introduced_error_spans=len(edits)-len(introduced),
                  span_precision=ratio(correct, len(edits)) if len(labelled) == len(edits) else None,
                  span_precision_lower_95=wilson_lower(correct, len(edits)) if len(labelled) == len(edits) else None)
    for prefix in ("original", "candidate"):
        totals[prefix+"_wer"] = ratio(totals[prefix+"_errors"], totals["reference_words"])
        totals[prefix+"_lexical_cer"] = ratio(totals[prefix+"_character_errors"], totals["reference_characters"])
        totals[prefix+"_literal_cer"] = ratio(totals[prefix+"_literal_errors"], totals["reference_literal_characters"])
    totals["paired_wer_delta_ci95"] = paired_wer_interval(rows, repetitions, seed)
    totals["critical_errors"] = {}
    for kind in CRITICAL:
        assessed = [row["critical"][kind] for row in rows if kind in row["critical"]]
        original = sum(values["original"] for values in assessed)
        candidate = sum(values["candidate"] for values in assessed)
        totals["critical_errors"][kind] = dict(
            assessed_clips=len(assessed),
            assessed_original=original if assessed else None,
            assessed_candidate=candidate if assessed else None,
            original=original if len(assessed) == len(rows) else None,
            candidate=candidate if len(assessed) == len(rows) else None)
    return totals


def report(items, repetitions=1000, seed=0):
    if not items:
        raise ValueError("No paired records were selected")
    if any(not isinstance(item, dict) for item in items):
        raise ValueError("Each paired record must be an object")
    ids = [item.get("id") for item in items]
    if any(not isinstance(identifier, str) for identifier in ids) or len(set(ids)) != len(ids):
        raise ValueError("Clip IDs must be unique within the selected split")
    rows = [score_record(item) for item in items]
    languages = sorted({row["language"] for row in rows})
    resources = defaultdict(lambda: defaultdict(list))
    for row in rows:
        for measure in row["measurements"]:
            group = (measure["device"], measure["variant"], measure["phase"], row["language"])
            for key in MEASURES:
                if key in measure:
                    resources[group][key].append(measure[key])
    return dict(scope="Offline paired text/annotation scoring; no inference or proof of phone performance. Missing evidence stays null/unscored.",
                normalization="NFC+casefold word tokens; lexical CER omits punctuation/spacing; literal CER preserves case/punctuation/spacing.",
                bootstrap=dict(samples=repetitions, seed=seed, unit="speaker/session group if provided, otherwise clip (screening only)",
                               minimum_samples=1000, minimum_reference_bearing_groups=2),
                precision_interval_assumption="Wilson assumes independent accepted spans; correlated changes need separate cluster-aware evidence.",
                all_seven_languages_present=set(languages) == LANGUAGES,
                overall=summarize(rows, repetitions, seed),
                languages={language: summarize([row for row in rows if row["language"] == language], repetitions, seed+index+1)
                           for index, language in enumerate(languages)},
                resources=[dict(device=device, variant=variant, phase=phase, language=language,
                                measures={key: dict(n=len(values), p50=percentile(values, .5), p95=percentile(values, .95), maximum=max(values))
                                          for key, values in values_by_key.items()})
                           for (device, variant, phase, language), values_by_key in sorted(resources.items())])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("pairs", type=Path)
    parser.add_argument("--split", choices=("dev", "test"), default="test")
    parser.add_argument("--require-all-languages", action="store_true")
    parser.add_argument("--bootstrap-samples", type=int, default=1000)
    parser.add_argument("--seed", type=int, default=0)
    parser.add_argument("--output", type=Path, default=Path("build/stt-correction-metrics.json"))
    args = parser.parse_args()
    if not 0 <= args.bootstrap_samples <= 10000:
        parser.error("Bootstrap sample count must be between zero and 10000")
    try:
        contents = args.pairs.read_bytes()
        items = [json.loads(line) for line in contents.decode("utf-8").splitlines() if line.strip()]
        if any(not isinstance(item, dict) or item.get("split") not in {"dev", "test"} for item in items):
            raise ValueError("Every record must explicitly belong to dev or test")
        identifiers = [item.get("id") for item in items]
        if any(not isinstance(identifier, str) for identifier in identifiers) or len(set(identifiers)) != len(identifiers):
            raise ValueError("Clip IDs must be unique across dev and test")
        dev_groups = {item["group"] for item in items if item["split"] == "dev" and "group" in item}
        test_groups = {item["group"] for item in items if item["split"] == "test" and "group" in item}
        if dev_groups & test_groups:
            raise ValueError("Speaker/session groups must not cross dev and test")
        selected = [item for item in items if item["split"] == args.split]
        result = report(selected, args.bootstrap_samples, args.seed)
        if args.require_all_languages and not result["all_seven_languages_present"]:
            raise ValueError("The selected split must include all seven languages")
    except (ValueError, KeyError, TypeError, OSError) as error:
        parser.error(str(error))
    result.update(input_sha256=hashlib.sha256(contents).hexdigest(), split=args.split)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(result, indent=2, allow_nan=False) + "\n")
    print(json.dumps(result, indent=2, allow_nan=False))


if __name__ == "__main__":
    main()
