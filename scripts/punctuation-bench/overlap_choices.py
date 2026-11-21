"""Audit alternate readings of flagged spans in visible, decoded window context.

context.tsv groups Kotlin WordBuilder receipts by W rows (index, audio start/end,
keep lo/hi in ms, nullable as 'null'). T rows contain start, end, usable, spelling.
The producer excludes hidden windows and applies Focal's doubt rule to raw words.
"""
import argparse
from bisect import bisect_right
import json
from pathlib import Path
import runpy
import unicodedata

score_note = runpy.run_path(str(Path(__file__).with_name("score_choices.py")))["score_note"]


def read_context(path):
    windows = []
    for line in path.read_text().splitlines():
        kind, *fields = line.split("\t")
        if kind == "W":
            _, start, end, low, high = fields
            windows.append((int(start), int(end), None if low == "null" else int(low),
                            None if high == "null" else int(high), []))
        elif kind == "T":
            start, end, usable, text = fields
            windows[-1][-1].append((int(start), int(end), usable == "true", text))
        else:
            raise ValueError("Unexpected context receipt")
    return windows


def eligible_readings(windows, start, end):
    middle = start + (end - start) // 2
    matches = [words for a, b, low, high, words in windows if a <= start and end <= b and
               ((low is not None and low > middle) or (high is not None and high <= middle))]
    for words in matches[:2]:
        selected = [w for w in words if start <= (w[0] + w[1]) // 2 < end]
        if not selected or not all(w[2] for w in selected):
            continue
        intersection = min(end, selected[-1][1]) - max(start, selected[0][0])
        union = max(end, selected[-1][1]) - min(start, selected[0][0])
        if intersection < .60 * union:
            continue
        text = " ".join(w[3] for w in selected)
        left, right = 0, len(text)
        while left < right and not text[left].isalnum():
            left += 1
        while right > left and not (text[right - 1].isalnum() or unicodedata.category(text[right - 1]).startswith("M")):
            right -= 1
        if left < right:
            yield text[left:right]


def audio_menu(folder, draft):
    encoded = draft.strip().encode("utf-16-le")
    ends, timing = [], []
    cursor = 0
    units = 0
    text = draft.strip()
    for row in (folder / "words.tsv").read_text().splitlines():
        word, start, end, _, _ = row.rsplit("\t", 4)
        end_at = text.index(word, cursor) + len(word)
        units += len(text[cursor:end_at].encode("utf-16-le")) // 2
        cursor = end_at
        ends.append(units)
        timing.append((int(start), int(end)))
    contexts = read_context(folder / "context.tsv")
    lines = []
    for row in (folder / "choices.tsv").read_text().splitlines():
        first, last, *_ = row.split("\t")
        begin, end = int(first), int(last)
        times = timing[bisect_right(ends, begin)][0], timing[bisect_right(ends, end - 1)][1]
        source = encoded[begin * 2:end * 2].decode("utf-16-le")
        def key(word):
            return unicodedata.normalize("NFC", word.lower())
        seen = {key(source)}
        readings = []
        for choice in eligible_readings(contexts, *times):
            if key(choice) not in seen:
                seen.add(key(choice))
                readings.append(choice)
        lines.append("\t".join([first, last, *readings]))
    return "\n".join(lines)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    results = []
    for row in json.loads(args.manifest.read_text()):
        draft = Path(row["draft"])
        text = draft.read_text()
        metrics = score_note(text, Path(row["reference"]).read_text(), audio_menu(draft.parent, text))
        results.append(dict(id=row["id"], **metrics))
    args.output.write_text(json.dumps(results, indent=2) + "\n")
