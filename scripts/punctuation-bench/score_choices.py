"""Score the reading screen's nearby spelling choices, one reversible change at a time."""
import argparse
from collections import Counter
import json
from pathlib import Path
import runpy

wer = runpy.run_path(str(Path(__file__).resolve().parents[1] / "score-wer.py"))


def score_note(draft, reference, receipts):
    # Compose offsets count UTF-16 code units. Retain emoji and combining characters outside each edit.
    encoded = draft.strip().encode("utf-16-le")
    reference_words = wer["words"](reference)
    original_errors = wer["distance"](reference_words, wer["words"](draft))
    counts = Counter()
    for row in receipts.splitlines():
        start, end, *choices = row.split("\t")
        left, right = 2 * int(start), 2 * int(end)
        if not 0 <= left < right <= len(encoded):
            raise ValueError("Review span is outside its draft")
        before = encoded[:left].decode("utf-16-le")
        after = encoded[right:].decode("utf-16-le")
        counts["flagged_spans"] += 1
        if choices:
            counts["spans_with_choices"] += 1
        changes = []
        for choice in choices:
            actual = wer["words"](before + choice + after)
            changes.append(wer["distance"](reference_words, actual) - original_errors)
        for delta in changes:
            counts["helpful" if delta < 0 else "harmful" if delta > 0 else "neutral"] += 1
        counts["spans_with_helpful_choice"] += any(delta < 0 for delta in changes)
        if changes:
            counts["first_helpful"] += changes[0] < 0
            counts["first_harmful"] += changes[0] > 0
    return counts


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--manifest", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    scores = []
    for row in json.loads(args.manifest.read_text()):
        draft = Path(row["draft"])
        counts = score_note(draft.read_text(), Path(row["reference"]).read_text(),
                            draft.with_name("choices.tsv").read_text())
        scores.append(dict(id=row["id"], **counts))
    args.output.write_text(json.dumps(scores, indent=2) + "\n")


if __name__ == "__main__":
    main()
