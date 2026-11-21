"""Evaluate reference-blind, timed alternatives as individual reversible edits."""
import argparse
from collections import Counter
from functools import lru_cache
import json
from pathlib import Path
import runpy
import unicodedata

from prepare import timed_words

ROOT = Path(__file__).resolve().parents[1]
wer = runpy.run_path(str(ROOT / "score-wer.py"))
audio_menu = runpy.run_path(str(ROOT / "punctuation-bench/overlap_choices.py"))["audio_menu"]
POLICIES = ("stored", "timing", "anchored", "anchored_new", "unfiltered")


def edge_trim(text):
    left, right = 0, len(text)
    while left < right and not text[left].isalnum():
        left += 1
    while right > left and not (text[right - 1].isalnum() or unicodedata.category(text[right - 1]).startswith("M")):
        right -= 1
    return text[left:right]


def read_crops(path):
    crops = {}
    for line in path.read_text().splitlines():
        kind, *fields = line.split("\t")
        if kind == "W":
            key, = fields
            if key in crops:
                raise ValueError("Duplicate crop receipt")
            crops[key] = []
        elif kind == "T":
            start, end, confidence, unknown, text = fields
            if unknown not in ("true", "false"):
                raise ValueError("Invalid unknown-piece flag")
            crops[key].append((text, int(start), int(end), float(confidence) if confidence != "null" else 0.,
                               unknown == "true"))
        else:
            raise ValueError("Unknown crop receipt")
    return crops


def same_anchor(original, decoded):
    spelling = wer["words"](original[0])
    return bool(spelling and spelling == wer["words"](decoded[0]) and
                abs((original[1] + original[2]) - (decoded[1] + decoded[2])) <= 800)


def extract(timed, first, last, crop, source):
    start, end = timed[first][1], timed[last][2]
    selected = [i for i, (_, a, b, _, _) in enumerate(crop) if 2 * start <= a + b < 2 * end]
    if not selected:
        return {}
    lo, hi = selected[0], selected[-1]
    if selected != list(range(lo, hi + 1)) or hi - lo > last - first + 1:
        return {}
    words = crop[lo:hi + 1]
    if any(unknown for _, _, _, _, unknown in words):
        return {}
    candidate = edge_trim(" ".join(w[0] for w in words))
    lexemes = wer["words"](candidate)
    if not lexemes or lexemes == wer["words"](source):
        return {}
    intersection = min(end, words[-1][2]) - max(start, words[0][1])
    union = max(end, words[-1][2]) - min(start, words[0][1])
    if intersection < .60 * union:
        return {}
    result = {"unfiltered": candidate}
    conf = min(w[3] for w in words)
    if conf >= .50:
        result["timing"] = candidate
    left_anchor = first > 0 and lo > 0 and same_anchor(timed[first - 1], crop[lo - 1])
    right_anchor = last + 1 < len(timed) and hi + 1 < len(crop) and same_anchor(timed[last + 1], crop[hi + 1])
    if conf >= .70 and (left_anchor or right_anchor):
        result["anchored"] = candidate
    return result


def receipts(data):
    result = {}
    for line in data.splitlines():
        a, b, *choices = line.split("\t")
        result[int(a), int(b)] = choices
    return result


def local_reference(reference, before, after, source_size):
    # A repeated classroom passage has no linear whole-recording reference.
    if len(before) < 3 or len(after) < 3:
        return None
    matches = []
    for left in range(3, len(reference) - 2):
        if reference[left - 3:left] != before[-3:]:
            continue
        for right in range(left, min(left + 2 * source_size + 3, len(reference) - 2)):
            if reference[right:right + 3] == after[:3]:
                matches.append(reference[left:right])
    return matches[0] if len(matches) == 1 else None


def score_note(note, original, crops, repeated_key=False):
    draft = Path(original["draft"])
    text = draft.read_text().strip()
    encoded = text.encode("utf-16-le")
    timed, _ = timed_words(original)
    nearby = receipts(draft.with_name("choices.tsv").read_text())
    overlap = receipts(audio_menu(draft.parent, text)) if note["spans"] else {}
    reference = wer["words"](Path(original["reference"]).read_text())
    baseline = wer["distance"](reference, wer["words"](text)) if note["spans"] and not repeated_key else 0
    result = dict(id=note["id"], language=note["language"], split=note["split"], source=note["source"],
                  flags=len(note["spans"]), scored_flags=0, taps_with_wider_audio=0, skip=Counter(),
                  policies={p: Counter() for p in POLICIES})
    for s in note["spans"]:
        a, b = s["begin"], s["end"]
        source = encoded[a * 2:b * 2].decode("utf-16-le")
        spellings = {}
        for word in overlap[a, b] + nearby[a, b]:
            spellings.setdefault(unicodedata.normalize("NFC", word.lower()), word)
        stored = list(spellings.values())
        menus = {"stored": stored}
        if "key" in s:
            result["taps_with_wider_audio"] += 1
            for policy, word in extract(timed, s["first"], s["last"], crops[s["key"]], source).items():
                menus[policy] = [word]
            stored_words = [wer["words"](v) for v in stored]
            if "anchored" in menus and wer["words"](menus["anchored"][0]) not in stored_words:
                menus["anchored_new"] = menus["anchored"]
        else:
            result["skip"][s["skip"]] += 1

        local = None
        if repeated_key:
            local = local_reference(reference, wer["words"](encoded[:a * 2].decode("utf-16-le")),
                                    wer["words"](encoded[b * 2:].decode("utf-16-le")), len(wer["words"](source)))
        scoreable = not repeated_key or local is not None
        result["scored_flags"] += scoreable

        @lru_cache(maxsize=None)
        def delta(choice):
            if repeated_key:
                return wer["distance"](local, wer["words"](choice)) - wer["distance"](local, wer["words"](source))
            edited = encoded[:a * 2] + choice.encode("utf-16-le") + encoded[b * 2:]
            return wer["distance"](reference, wer["words"](edited.decode("utf-16-le"))) - baseline

        for policy, choices in menus.items():
            if not choices:
                continue
            counter = result["policies"][policy]
            counter["offered"] += 1
            if not scoreable:
                counter["unscored_offers"] += 1
                continue
            scores = [delta(v) for v in choices]
            counter["any_helpful"] += min(scores) < 0
            if policy == "anchored_new" and min(scores) < 0:
                counter["new_helpful_span"] += not any(delta(word) < 0 for word in stored)
            for value in scores:
                counter["helpful" if value < 0 else "harmful" if value > 0 else "neutral"] += 1
            counter["first_word_errors_delta"] += scores[0]
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("work", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--repeated-key", action="store_true",
                        help="Score only spans bracketed by unique three-word flanks in the printed key.")
    args = parser.parse_args()
    notes = json.loads((args.work / "plan.json").read_text())
    original = {r["id"]: r for r in json.loads(args.manifest.read_text())}
    crops = read_crops(args.work / "words.tsv")
    expected = {s["key"] for n in notes for s in n["spans"] if "key" in s}
    if expected != crops.keys():
        raise ValueError("Crop receipts differ from the plan")
    result = [score_note(n, original[n["id"]], crops, args.repeated_key) for n in notes]
    args.output.write_text("".join(json.dumps(x, separators=(",", ":")) + "\n" for x in result))


if __name__ == "__main__":
    main()
