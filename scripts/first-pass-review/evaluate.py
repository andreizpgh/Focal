"""Compare first-pass token alternatives with the source draft and its existing review menu."""
import argparse
from collections import Counter, defaultdict
from functools import lru_cache
import hashlib
import json
import math
from pathlib import Path
import runpy
import unicodedata

from variants import letters, token_groups

ROOT = Path(__file__).resolve().parents[1]
wer = runpy.run_path(str(ROOT / "score-wer.py"))
context = runpy.run_path(str(ROOT / "context-review/prepare.py"))
audio_menu = runpy.run_path(str(ROOT / "punctuation-bench/overlap_choices.py"))["audio_menu"]
POLICIES = ("existing", "broad", "strict", "strict_new", "eligible_pool")


def norm(text):
    return unicodedata.normalize("NFC", text).casefold()


def core(text):
    left, right = 0, len(text)
    while left < right and not text[left].isalnum():
        left += 1
    while right > left and not (text[right - 1].isalnum() or unicodedata.category(text[right - 1]).startswith("M")):
        right -= 1
    return text[left:right]


def word_groups(path):
    key, words = None, []
    with path.open() as stream:
        for line in stream:
            kind, *row = line.rstrip("\n").split("\t")
            if kind == "W":
                if key is not None:
                    yield key, words
                key, = row
                words = []
            elif kind == "T" and key is not None:
                a, b, _, unknown, text = row
                words.append((int(a), int(b), unknown == "true", text))
            else:
                raise ValueError("Invalid WordBuilder receipt")
    if key is not None:
        yield key, words


def lexical_variants(work):
    meta = {r["variant"]: r for l in (work / "variants.jsonl").read_text().splitlines() if (r := json.loads(l))}
    variants = defaultdict(list)
    for key, words in word_groups(work / "words.tsv"):
        if "/" not in key:
            original_key, original = key, words
            continue
        m = meta.pop(key)
        if original_key != m["key"]:
            raise ValueError("Variant attached to the wrong window")
        if [w[:3] for w in words] != [w[:3] for w in original]:
            continue
        changes = [(w, other) for w, other in zip(original, words) if w != other]
        if len(changes) != 1:
            continue
        (start, end, unknown, text), (_, _, _, edited) = changes[0]
        old, new = core(text), core(edited)
        if unknown or not letters(old) or not letters(new) or norm(old) == norm(new):
            continue
        variants[original_key, start, end, norm(old)].append((m["prob"], m["ratio"], new,
                                                               norm(old) == norm(m["old"].strip())))
    if meta:
        raise ValueError("Missing WordBuilder variants")
    return variants


def parity(work, notes):
    indexed = defaultdict(dict)
    for key, _, tokens in token_groups(work / "tokens.tsv"):
        name, num = key.rsplit(":", 1)
        indexed[name][int(num)] = tokens
    valid, counts = set(), Counter()
    max_difference = 0.
    for n in notes:
        windows = indexed.get(n["id"], {})
        if not windows:
            continue
        archived = defaultdict(list)
        for line in Path(n["draft"]).with_name("tokens").read_text().splitlines():
            filename, *fields = line.split("\t")
            index = int(Path(filename).stem)
            if index in windows:
                archived[index].append(fields)
        for index, new in windows.items():
            old = archived[index]
            counts["windows"] += 1
            if old == new:
                counts["identical_receipts"] += 1
            if len(old) != len(new) or any(x[3] != y[3] for x, y in zip(old, new)):
                counts["token_sequence_changed"] += 1
                continue
            if any(tuple(map(float, x[:2])) != tuple(map(float, y[:2])) for x, y in zip(old, new)):
                counts["timing_changed"] += 1
                continue
            errors = [abs(float(x[2]) - float(y[2])) for x, y in zip(old, new)]
            diff = max(errors, default=0)
            max_difference = max(max_difference, diff)
            if diff > 1e-4 or any((float(x[2]) < math.log(.5)) != (float(y[2]) < math.log(.5)) for x, y in zip(old, new)):
                counts["confidence_mismatch"] += 1
                continue
            valid.add(f"{n['id']}:{index}")
    counts["eligible_windows"] = len(valid)
    counts["max_logprob_difference_for_matching_token_times"] = max_difference
    return valid, counts


def menus(options):
    result = {}
    for name in ("broad", "strict"):
        accepted = [(ratio, prob, text) for prob, ratio, text, whole, attested in options if
                    name == "broad" or (prob >= .20 and ratio >= .60 and (whole or attested))]
        ordered = {}
        for _, _, text in sorted(accepted, key=lambda x: (-x[0], -x[1], x[2])):
            ordered.setdefault(norm(text), text)
        result[name] = list(ordered.values())[:2 if name == "broad" else 1]
        if name == "broad":
            result["eligible_pool"] = list(ordered.values())
    return result


def receipt_menu(text):
    rows = {}
    for line in text.splitlines():
        start, end, *words = line.split("\t")
        rows[int(start), int(end)] = words
    return rows


def span_replacements(span, encoded, timed, ends, variants):
    for i, owner in enumerate(span["owners"], span["first"]):
        text, start, end = timed[i]
        original = core(text)
        if not original:
            continue
        offset = text.index(original)
        left = ends[i] - len(text[offset:].encode("utf-16-le")) // 2
        right = left + len(original.encode("utf-16-le")) // 2
        if not span["begin"] <= left < right <= span["end"]:
            continue
        before = encoded[2 * span["begin"]:2 * left].decode("utf-16-le")
        after = encoded[2 * right:2 * span["end"]].decode("utf-16-le")
        for variant in variants.get((owner, start, end, norm(original)), ()):
            yield i, before, variant, after


def suggestions_for_span(span, encoded, timed, ends, flags, variants):
    options = []
    for i, before, (prob, ratio, replacement, whole), after in span_replacements(span, encoded, timed, ends, variants):
        known = any(not flags[j] and norm(core(timed[j][0])) == norm(replacement)
                    for j in range(max(0, i - 48), min(len(flags), i + 49)))
        options.append((prob, ratio, before + replacement + after, whole, known))
    return menus(options)


def score(note, variants, valid, dictation_key, *, suggest=suggestions_for_span, policies=POLICIES,
          compare_with=None, observe=None):
    timed, ends = context["timed_words"](note)
    draft_path = Path(note["draft"])
    draft = draft_path.read_text().strip()
    encoded = draft.encode("utf-16-le")
    flags = [line.rsplit("\t", 1)[1] == "true" for line in Path(note["words"]).read_text().splitlines()]
    existing = receipt_menu(draft_path.with_name("choices.tsv").read_text())
    overlap = receipt_menu(audio_menu(draft_path.parent, draft)) if note["spans"] else {}
    reference_path = Path(note["reference"])
    with reference_path.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != note["reference_sha256"]:
            raise ValueError("Reference failed its checksum")
    reference = wer["words"](reference_path.read_text())
    repeated = note["id"] == "russian-dictation-repeated"
    baseline = wer["distance"](reference, wer["words"](draft)) if note["spans"] and not repeated else 0
    result = dict(id=note["id"], split=note["split"], language=note["language"], source=note["source"],
                  flags=len(note["spans"]), scored=0, matched=0, policies={k: Counter() for k in policies})
    for span in note["spans"]:
        start, end = span["begin"], span["end"]
        choices = {name: [] for name in policies}
        if all(key in valid for key in span["owners"]):
            result["matched"] += 1
            choices = suggest(span, encoded, timed, ends, flags, variants)
        stored = {}
        for word in overlap[start, end] + existing[start, end]:
            stored.setdefault(norm(word), word)
        choices["existing"] = list(stored.values())
        choices["strict_new"] = [v for v in choices["strict"] if norm(v) not in stored]
        interval = dictation_key[timed[span["first"]][1]] if repeated else None
        scoreable = not repeated or interval is not None
        result["scored"] += scoreable

        @lru_cache(maxsize=None)
        def delta(value):
            if repeated:
                gold = reference[interval[0]:interval[1]]
                old = wer["words"](encoded[2 * start:2 * end].decode("utf-16-le"))
                return wer["distance"](gold, wer["words"](value)) - wer["distance"](gold, old)
            changed = encoded[:2 * start] + value.encode("utf-16-le") + encoded[2 * end:]
            return wer["distance"](reference, wer["words"](changed.decode("utf-16-le"))) - baseline

        if observe and scoreable:
            observe(span, choices, delta)
        for name, values in choices.items():
            if not values:
                continue
            counts = result["policies"][name]
            counts["offered"] += 1
            if not scoreable:
                counts["unscored_offers"] += 1
                continue
            errors = [delta(v) for v in values]
            counts["any_helpful"] += min(errors) < 0
            counts["first_helpful"] += errors[0] < 0
            counts["first_harmful"] += errors[0] > 0
            counts["first_error_delta"] += errors[0]
            for change in errors:
                counts["helpful" if change < 0 else "harmful" if change > 0 else "neutral"] += 1
            if name == "strict_new" and min(errors) < 0:
                counts["new_helpful_span"] += not any(delta(v) < 0 for v in stored.values())
            if compare_with and name in compare_with and min(errors) < 0:
                counts["helpful_beyond_comparison"] += not any(
                    delta(v) < 0 for other in compare_with[name] for v in choices[other])
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path)
    parser.add_argument("previous_results", type=Path)
    args = parser.parse_args()
    work = args.work
    notes = json.loads((work / "plan.json").read_text())
    previous = json.loads(args.previous_results.read_text())
    key = {r["start_ms"]: r["range"] for r in previous["russian_repeated_key_textual_check"]}
    valid, diagnostic = parity(work, notes)
    variants = lexical_variants(work)
    with (work / "cases.jsonl").open("w") as out:
        for note in notes:
            out.write(json.dumps(score(note, variants, valid, key), separators=(",", ":")) + "\n")
    (work / "parity.json").write_text(json.dumps(diagnostic, indent=2) + "\n")


if __name__ == "__main__":
    main()
