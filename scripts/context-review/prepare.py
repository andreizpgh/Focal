"""Plan one wider audio crop per distinct set of original decoding windows."""
import argparse
from bisect import bisect_right
from collections import Counter
import hashlib
import json
from pathlib import Path
import runpy

read_context = runpy.run_path(str(Path(__file__).resolve().parents[1] /
                                  "punctuation-bench/overlap_choices.py"))["read_context"]


def crop(original_start, original_end, duration):
    # Keep the entire original interval. No identical or minimally changed decode.
    left = max(0, original_start - 6000)
    right = min(duration, original_end + 6000)
    if right - left > 30_000:
        if original_end - original_start > 30_000:
            return None
        left = max(left, min((original_start + original_end - 30_000) // 2, right - 30_000))
        right = left + 30_000
    if (right - left) - (original_end - original_start) < 1000:
        return None
    return left, right


def timed_words(note):
    text = Path(note["draft"]).read_text().strip()
    words, ends = [], []
    cursor = units = 0
    for line in Path(note["words"]).read_text().splitlines():
        word, start, end, *_ = line.rsplit("\t", 4)
        offset = text.index(word, cursor)
        if text[cursor:offset].strip():
            raise ValueError("Missing timed word")
        stop = offset + len(word)
        units += len(text[cursor:stop].encode("utf-16-le")) // 2
        ends.append(units)
        words.append((word, int(start), int(end)))
        cursor = stop
    if text[cursor:].strip():
        raise ValueError("Draft extends past timed words")
    return words, ends


def owners(folder):
    context = read_context(folder / "context.tsv")
    by_time = {}
    for left, right, low, high, words in context:
        for start, end, _, _ in words:
            midpoint = (start + end) // 2
            if (low is None or low <= midpoint) and (high is None or midpoint < high):
                by_time[start, end] = (left, right)
    original = [tuple(map(int, line.split()[1:])) for line in
                (folder.parent / "windows").read_text().splitlines()]
    return by_time, original


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("cases", type=Path)
    parser.add_argument("work", type=Path)
    args = parser.parse_args()
    args.work.mkdir(exist_ok=True, parents=True)
    cases = {c["id"]: c for line in args.cases.read_text().splitlines() if (c := json.loads(line))}
    crops, notes = {}, []
    for note in json.loads(args.manifest.read_text()):
        note_id = note["id"]
        split = note.get("original_split", note["split"])
        if split not in ("dev", "holdout"):
            continue
        case = cases[note_id]
        if any(case[key] != note[key] for key in ("audio_sha256", "reference_sha256")):
            raise ValueError(f"Corpus changed for {note_id}")
        if note["source"] == "YODAS2" and not case["evaluation"]["caption_eligible"]:
            continue
        for key in ("audio", "reference"):
            with Path(note[key]).open("rb") as file:
                if hashlib.file_digest(file, "sha256").hexdigest() != case[key + "_sha256"]:
                    raise ValueError(f"{key} failed the published checksum for {note_id}")
        folder = Path(note["draft"]).parent
        row = dict(id=note_id, language=note["language"], split=split, source=note["source"], spans=[])
        notes.append(row)
        lines = (folder / "choices.tsv").read_text().splitlines()
        if not lines:
            continue
        timed, offsets = timed_words(note)
        by_time, original = owners(folder)
        pcm = folder / "pcm"
        duration = pcm.stat().st_size // 32
        for line in lines:
            start, end, *_ = line.split("\t")
            begin, stop = int(start), int(end)
            first, last = bisect_right(offsets, begin), bisect_right(offsets, stop - 1)
            span = dict(begin=begin, end=stop, first=first, last=last)
            row["spans"].append(span)
            windows = []
            for _, a, b in timed[first:last + 1]:
                owner = by_time.get((a, b))
                if owner is None:
                    possible = [w for w in original if w[0] <= (a + b) // 2 < w[1]]
                    if len(possible) != 1:
                        break
                    owner = possible[0]
                windows.append(owner)
            if len(windows) != last - first + 1:
                span["skip"] = "ambiguous_owner"
                continue
            bounds = crop(min(a for a, _ in windows), max(b for _, b in windows), duration)
            if bounds is None:
                span["skip"] = "no_wider_crop"
                continue
            left, right = bounds
            key = f"{note_id}_{left}_{right}"
            span.update(key=key, start_ms=left)
            crops[key] = (pcm, left, right)
    (args.work / "plan.json").write_text(json.dumps(notes, indent=2) + "\n")
    with (args.work / "requests.tsv").open("w") as out:
        for key, (pcm, left, right) in sorted(crops.items()):
            print(key, pcm, left, right, sep="\t", file=out)
    print(len(notes), "notes,", len(crops), "unique crops,",
          Counter(s.get("skip", "planned") for n in notes for s in n["spans"]))


if __name__ == "__main__":
    main()
