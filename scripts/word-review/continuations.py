"""Score bounded word continuations against the same saved drafts, review menus and references."""
import argparse
from collections import Counter, defaultdict
from dataclasses import dataclass
import json
import math
from pathlib import Path
from statistics import median
import sys
from decision import decision_screen

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "first-pass-review"))
import evaluate as first
from summarize import summarize, timing
from variants import pieces, ranked_groups, token_groups

POLICIES = ("existing", "piece_pool", "fixed_pool", "word_pool", "broad", "strict", "strict_new", "corroborated")
COMPARISONS = {
    "word_pool": ("existing", "piece_pool", "fixed_pool"),
    "broad": ("existing", "piece_pool"),
    "strict_new": ("existing", "piece_pool"),
    "corroborated": ("existing",),
}


@dataclass(frozen=True)
class Candidate:
    text: str
    prob: float
    ratio: float
    delta: float | None
    shift: int


def milliseconds(seconds):
    return math.floor(float(seconds) * 1000 + .5)


def window_candidates(rows, tokens, offset, words, vocab, kinds, counts):
    surfaces = [bytes.fromhex(t[3]).decode() for t in tokens]
    timed_words = defaultdict(list)
    for start, end, unknown, text in words:
        if not unknown:
            timed_words[first.norm(first.core(text)), end].append(start)
    if not rows or rows[-1][0] != "N":
        raise ValueError("Incomplete window")
    seeds, paths, joins, predictions = map(int, rows[-1][1:])
    if not (0 <= paths <= 3 * seeds <= 24 and joins <= 24 * paths and predictions <= 8 * paths):
        raise ValueError("Decoder exceeded the branch budget")
    if paths != sum(row[0] == "S" for row in rows):
        raise ValueError("Branch starts missing from the trace")
    counts.update(windows=1, seeds=seeds, paths=paths, joiner_calls=joins, predictor_calls=predictions)
    forks = set()
    for tag, *row in rows[:-1]:
        seed, stop = map(int, row[:2])
        if not 0 <= seed < stop <= len(tokens):
            raise ValueError("Branch outside the original token range")
        left = seed
        while left and kinds.get(surfaces[left]) == 1 and kinds.get(surfaces[left - 1]):
            left -= 1
        old = "".join(surfaces[left:stop]).strip()
        old_key = first.norm(old)
        prefix = "".join(surfaces[left:seed])
        if tag == "S":
            seed_id, lp, ratio = row[2:5]
            forks.add((seed, stop, seed_id, lp, ratio))
            replacement = vocab[int(seed_id)] + "".join(surfaces[seed + 1:stop])
            delta, shift = None, 0
        elif tag == "C":
            lp, ratio, delta, shift, eos, *ids = row[2:]
            if (seed, stop, ids[0], lp, ratio) not in forks:
                raise ValueError("Completion does not match its branch start")
            replacement = "".join(vocab[int(x)] for x in ids)
            delta, shift = float(delta), int(shift)
            counts["completed"] += 1
            counts["end_of_window"] += int(eos)
        else:
            raise ValueError("Unknown path record")
        if abs(float(lp) - float(ratio) - float(tokens[seed][2])) > 1e-5:
            raise ValueError("Branch and original token scores disagree")
        new = (prefix + replacement).strip()
        if old_key == first.norm(new):
            continue
        last = tokens[stop - 1]
        end = offset + milliseconds(last[0]) + max(milliseconds(last[1]), 40)  # Pinned WordBuilder floor in ms
        seed_ms = offset + milliseconds(tokens[seed][0])
        starts = [t for t in timed_words[old_key, end] if t <= seed_ms <= end]
        if len(starts) == 1:
            yield (starts[0], end, old_key), Candidate(new, math.exp(float(lp)), math.exp(float(ratio)), delta, shift)


def word_menus(options):
    def best(items, limit=None):
        unique = {}
        for _, _, _, text in sorted(items, key=lambda x: (-x[0], -x[1], -x[2], x[3])):
            unique.setdefault(first.norm(text), text)
        return list(unique.values())[:limit]

    fixed, complete, broad, strict = [], [], [], []
    for c, surface in options:
        if c.delta is None:
            fixed.append((c.ratio, c.prob, 0, surface))
            continue
        item = (c.delta, c.ratio, c.prob, surface)
        complete.append(item)
        if c.prob >= .08 and c.ratio >= .25 and c.delta >= -3:
            broad.append(item)
        if c.prob >= .15 and c.ratio >= .4 and c.delta >= -.8 and c.shift == 0:
            strict.append(item)
    return dict(fixed_pool=best(fixed), word_pool=best(complete), broad=best(broad, 2), strict=best(strict, 1))


def suggest(span, encoded, timed, ends, flags, indexes, *, features=None):
    paths, singles = indexes
    options, corroborated = [], []
    for i, before, candidate, after in first.span_replacements(span, encoded, timed, ends, paths):
        item = (candidate, before + candidate.text + after)
        options.append(item)
        if candidate.delta is None:
            continue
        target = first.norm(candidate.text).split()
        lo, hi = max(0, i - 48), min(len(timed), i + 49)
        known = False
        for j in range(lo, hi - len(target) + 1):
            stop = j + len(target)
            if j <= span["last"] and stop > span["first"] or any(flags[j:stop]):
                continue
            if target == [first.norm(first.core(t[0])) for t in timed[j:stop]]:
                corroborated.append(item)
                known = True
                break
        if features is not None:
            old = first.norm(first.core(timed[i][0]))
            new = first.norm(candidate.text)
            priority = candidate.delta, candidate.ratio, candidate.prob
            key = first.norm(item[1])
            if key not in features or priority > features[key][0]:
                features[key] = (priority, (
                    candidate.prob, candidate.ratio, max(-8, min(8, candidate.delta)) / 8,
                    abs(candidate.shift) / 2, int(len(target) > 1), int(known),
                    first.wer["distance"](old, new) / max(len(old), len(new), 1)))
    result = word_menus(options)
    result["corroborated"] = word_menus(corroborated)["broad"][:1]
    result["piece_pool"] = first.suggestions_for_span(span, encoded, timed, ends, flags, singles)["eligible_pool"]
    return result


def score_corpus(work, earlier, vocab_path, previous_results):
    notes = json.loads((earlier / "plan.json").read_text())
    previous = json.loads(previous_results.read_text())
    key = {r["start_ms"]: r["range"] for r in previous["russian_repeated_key_textual_check"]}
    vocab = pieces(vocab_path)
    flags = (work / "types.bin").read_bytes()
    kinds = {word: flags[index] for index, word in vocab.items()}
    stock_words = {k: w for k, w in first.word_groups(earlier / "stock/words.tsv") if "/" not in k}
    singles = first.lexical_variants(earlier / "stock")
    variants, counts, valid = defaultdict(list), Counter(), set()
    stock_valid, _ = first.parity(earlier / "stock", notes)
    for trace, baseline, replay in zip(ranked_groups(work / "paths.tsv"), token_groups(work / "stock.tokens"),
                                       token_groups(work / "branched.tokens"), strict=True):
        name, offset, tokens = replay
        if name != baseline[0] or offset != baseline[1]:
            raise ValueError("Mismatched window order or origin")
        counts["total_windows"] += 1
        if baseline != replay or name not in stock_valid:
            continue
        valid.add(name)
        for loc, candidate in window_candidates(trace, tokens, int(offset), stock_words[name], vocab, kinds, counts):
            variants[(name, *loc)].append(candidate)
    if not valid:
        raise ValueError("No exact baseline matches")
    cases, samples = [], []
    for note in notes:
        features = {}

        def collect(*args):
            features.clear()
            return suggest(*args, features=features)

        def observe(span, choices, distance_change):
            existing = {first.norm(v) for v in choices["existing"]}
            options = [(features[k][1], distance_change(text)) for text in choices["word_pool"]
                       if (k := first.norm(text)) not in existing]
            samples.append((note["id"], note["language"], note["split"], options))

        primary_note = not note["id"].startswith("russian-")
        cases.append(first.score(note, (variants, singles), valid, key, suggest=collect if primary_note else suggest,
                                 policies=POLICIES, compare_with=COMPARISONS, observe=observe if primary_note else None))
    primary = [n for n in cases if not n["id"].startswith("russian-")]
    holdout = [n for n in primary if n["split"] == "holdout"]
    return dict(exact_baseline=counts, decision_model=decision_screen(samples),
                splits={s: summarize([n for n in primary if n["split"] == s])
                                               for s in ("dev", "holdout")},
                by_holdout_language={lang: summarize([n for n in holdout if n["language"] == lang])
                                     for lang in sorted({n["language"] for n in holdout})},
                russian_checks=[n for n in cases if n["id"].startswith("russian-")])


def android_cost(work):
    result = timing(work, "android")
    baseline = (work / "android-stock.tokens").read_bytes()
    expected = list(ranked_groups(work / "android-on1.paths"))
    if expected != list(ranked_groups(work / "android-on2.paths")):
        raise ValueError("Non-deterministic Android candidates")
    result["packaged_receipts_equal_in_all_rounds"] = all(
        baseline == (work / f"android-{r}.tokens").read_bytes() for r in ("off1", "on1", "on2", "off2", "cost1", "cost2"))
    keys = [key for key, _, _ in token_groups(work / "android-stock.tokens")]
    if keys[1:] != [m["key"] for m in result["measurements"]]:
        raise ValueError("Unexpected timed window order")
    for trial in ("cost1", "cost2"):
        for i, (group, reference) in enumerate(zip(ranked_groups(work / f"android-{trial}.paths"), expected, strict=True)):
            times = [tuple(map(int, r[1:])) for r in group if r[0] == "U"]
            if len(times) != 1 or [r for r in group if r[0] != "U"] != reference:
                raise ValueError("Clock probe changed the paths or failed to measure a window")
            if i:  # discard first-window warm-up
                row = result["measurements"][i - 1]
                observe, follow = times[0]
                row.setdefault("observation_us", []).append(observe)
                row.setdefault("continuation_us", []).append(follow)
                row["branch_starts"] = int(reference[-1][2])
    direct = [median([a + b for a, b in zip(r["observation_us"], r["continuation_us"], strict=True)]) / 1000
              for r in result["measurements"]]
    result["median_direct_cost_ms"] = median(direct)
    result["max_direct_cost_ms"] = max(direct)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path)
    parser.add_argument("earlier", type=Path)
    parser.add_argument("vocab", type=Path)
    parser.add_argument("previous_results", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--android", action="store_true", help="Include paired and directly timed Android measurements")
    args = parser.parse_args()
    data = score_corpus(args.work, args.earlier, args.vocab, args.previous_results)
    if args.android:
        data["android_x86_64"] = android_cost(args.work)
    args.output.write_text(json.dumps(data, indent=2) + "\n")


if __name__ == "__main__":
    main()
