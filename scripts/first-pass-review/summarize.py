"""Publish aggregate counts and paired measurements without audio, text, or token traces."""
import argparse
from collections import Counter
import json
from pathlib import Path
from statistics import median

from variants import token_groups, ranked_groups


def read_jsonl(path):
    return [json.loads(line) for line in path.read_text().splitlines()]


def summarize(notes):
    policies = {}
    for note in notes:
        for name, counts in note["policies"].items():
            policies.setdefault(name, Counter()).update(counts)
    return dict(notes=len(notes), flagged_spans=sum(n["flags"] for n in notes),
                spans_with_verified_windows=sum(n["matched"] for n in notes),
                notes_with_flags=sum(bool(n["flags"]) for n in notes),
                notes_with_new_helpful_option=sum(bool(n["policies"]["strict_new"].get("new_helpful_span"))
                                                  for n in notes),
                policies=policies)


def observer_agreement(work, stock):
    def receipts(directory):
        return {key: (tokens, ranks) for (key, _, tokens), ranks in
                zip(token_groups(directory / "tokens.tsv"), ranked_groups(directory / "ranks.tsv"), strict=True)}

    original, rebuilt = receipts(stock), receipts(work)
    if original.keys() != rebuilt.keys():
        raise ValueError("Quality and timing collectors saw different windows")
    counts = Counter()
    maximum = 0.
    for key, (tokens, ranked) in original.items():
        other_tokens, other_ranked = rebuilt[key]
        if tokens != other_tokens:
            continue
        counts["identical_baseline_windows"] += 1
        if [r[0] for r in ranked] != [r[0] for r in other_ranked]:
            raise ValueError("Collectors disagree on the uncertain decisions")
        for left, right in zip(ranked, other_ranked):
            counts["compared_uncertain_pieces"] += 1
            if any(left[i] != right[i] for i in (1, 3, 5)):
                counts["different_rank_ids"] += 1
                continue
            maximum = max(maximum, *(abs(float(left[i]) - float(right[i])) for i in (2, 4, 6)))
    counts["max_matching_logprob_difference"] = maximum
    return counts


def timing(work, prefix):
    rows = {}
    peaks = {"off": [], "on": []}
    latency = {"off": {}, "on": {}}
    tokens = []
    for trial in ("off1", "on1", "on2", "off2"):
        lines = [line.split("\t") for line in (work / f"{prefix}-{trial}.times").read_text().splitlines()]
        if lines[0][0] != "load" or not lines[1][0].startswith("warmup_"):
            raise ValueError("Unrecognized warm-up")
        mode = trial[:-1]
        peaks[mode].append(max(int(r[3]) for r in lines))
        trial_times = {}
        for key, duration, elapsed, _ in lines[2:]:
            if key in trial_times:
                raise ValueError("Repeated timed crop")
            rows[key] = int(duration)
            trial_times[key] = int(elapsed)
            latency[mode].setdefault(key, []).append(int(elapsed))
        tokens.append(list(token_groups(work / f"{prefix}-{trial}.tokens")))
    window_keys = [row[0] for row in tokens[0]]
    if any([row[0] for row in trial] != window_keys for trial in tokens[1:]):
        raise ValueError("On/off runs used different windows")
    if not all(len(times) == 2 for run in latency.values() for times in run.values()):
        raise ValueError("Incomplete paired rounds")
    measurements = [dict(key=key, duration_ms=duration, off_ms=latency["off"][key], on_ms=latency["on"][key])
                    for key, duration in rows.items()]
    differences = [median(r["on_ms"]) - median(r["off_ms"]) for r in measurements]
    total = {mode: sum(sum(pair) for pair in series.values()) for mode, series in latency.items()}
    out = dict(measurements=measurements, paired_median_delta_ms=median(differences),
               combined_time_delta_percent=100 * (total["on"] / total["off"] - 1),
               max_rss_kib={mode: max(peaks[mode]) for mode in peaks},
               identical_on_off_receipts=all(tokens[0] == trial for trial in tokens[1:]),
               windows_with_identical_receipts_all_four=sum(all(trial[i] == tokens[0][i] for trial in tokens[1:])
                                                           for i in range(1, len(window_keys))))
    if prefix == "android":
        shipped = list(token_groups(work / "android-stock.tokens"))
        if [k for k, _, _ in shipped] != [k for k, _, _ in tokens[0]]:
            raise ValueError("Packaged recognizer used different crops")
        out["stock_reference_windows"] = len(shipped) - 1
        out["windows_with_identical_stock_tokens"] = sum(
            [r[3] for r in new] == [r[3] for r in old]
            for (_, _, new), (_, _, old) in zip(tokens[0][1:], shipped[1:]))
        out["guest_api"] = int((work / "guest-api").read_text())
        out["guest_memory_kib"] = int((work / "guest-memory").read_text().split()[1])
    return out


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    work = args.work
    stock = work / "stock"
    notes = read_jsonl(stock / "cases.jsonl")
    primary = [n for n in notes if not n["id"].startswith("russian-")]
    holdout = [n for n in primary if n["split"] == "holdout"]
    outputs = dict(splits={split: summarize([n for n in primary if n["split"] == split])
                           for split in ("dev", "holdout")},
                   by_holdout_language={language: summarize([n for n in holdout if n["language"] == language])
                                        for language in sorted({n["language"] for n in holdout})},
                   separate_russian_checks=[n for n in notes if n["id"].startswith("russian-")],
                   stock_parity=json.loads((stock / "parity.json").read_text()),
                   research_rebuild_parity=json.loads((work / "parity.json").read_text()),
                   agreement_between_collectors=observer_agreement(work, stock),
                   performance={platform: timing(work, prefix) for platform, prefix in
                                (("host", "measure"), ("android_x86_64", "android"))})
    probe = work / "android-observed.json"
    if probe.is_file():
        outputs["android_stock_check"] = json.loads(probe.read_text())
    windows = (stock / "requests.tsv").read_text().splitlines()
    outputs["observations"] = dict(windows=len(windows),
                                   audio_ms=sum(int(r[3]) - int(r[2]) for row in windows if (r := row.split("\t"))),
                                   emitted_pieces=sum(len(tokens) for _, _, tokens in token_groups(stock / "tokens.tsv")),
                                   uncertain_pieces=sum(len(group) for group in ranked_groups(stock / "ranks.tsv")))
    args.output.mkdir(exist_ok=True, parents=True)
    (args.output / "results.json").write_text(json.dumps(outputs, indent=2) + "\n")
    (args.output / "cases.jsonl").write_bytes((stock / "cases.jsonl").read_bytes())


if __name__ == "__main__":
    main()
