#!/usr/bin/env python3
"""Score transcripts against reference transcriptions: word and character errors per language.

References are a JSON file in the format of docs/benchmarks/fleurs-references.json: a "clips" list
of {id, split, language, text, reference_sha256}. Every reference text is checked against its
SHA-256 first, so an edited reference cannot pass unnoticed. Hypotheses are a JSON object that maps
a clip id to the recognized text: {"fleurs-en_us-dev-...": "Then Lakkha Singh took the lead ..."}.

Normalisation is the one behind Focal's published figures: Unicode NFC, case folding, then maximal
runs of letters and digits (punctuation and spacing are ignored). Word errors are the edit distance
between the word sequences; character errors are the edit distance between the words joined
without separators. --fold-yo also folds Russian ё into е (FLEURS references use ё inconsistently);
the published figures do not fold it.

  python3 scripts/score-wer.py hypotheses.json                    # all 84 clips
  python3 scripts/score-wer.py hypotheses.json --split dev        # the DEV set only
  python3 scripts/score-wer.py hypotheses.json --json             # machine-readable totals

Only aggregate counts are printed, never transcripts. A clip missing from the hypotheses is an error
(exit status 2) unless --allow-missing is given, which leaves it out of the totals.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import sys
import unicodedata

ROOT = Path(__file__).resolve().parents[1]
REFERENCES = ROOT / "docs/benchmarks/fleurs-references.json"
WORD = re.compile(r"[^\W_]+")


def words(text, fold_yo=False):
    """Normalised words: NFC, case-folded, letter and digit runs only."""
    folded = unicodedata.normalize("NFC", text).casefold()
    if fold_yo:
        folded = folded.replace("ё", "е")
    return WORD.findall(folded)


def distance(reference, hypothesis):
    """Unit-cost Levenshtein distance between two sequences (insertions, deletions, substitutions)."""
    if len(reference) < len(hypothesis):
        reference, hypothesis = hypothesis, reference
    previous = list(range(len(hypothesis) + 1))
    for i, item in enumerate(reference, 1):
        current = [i]
        for j, other in enumerate(hypothesis, 1):
            current.append(min(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + (item != other)))
        previous = current
    return previous[-1]


def score(reference_text, hypothesis_text, fold_yo=False):
    """Counts for one clip: reference words and characters and the errors against them."""
    ref = words(reference_text, fold_yo)
    hyp = words(hypothesis_text, fold_yo)
    ref_chars = "".join(ref)
    return {
        "words": len(ref),
        "word_errors": distance(ref, hyp),
        "characters": len(ref_chars),
        "character_errors": distance(ref_chars, "".join(hyp)),
    }


def load_references(path):
    with path.open(encoding="utf-8") as source:
        clips = json.load(source)["clips"]
    seen = set()
    for clip in clips:
        if clip["id"] in seen:
            raise ValueError(f"duplicate reference id {clip['id']}")
        seen.add(clip["id"])
        if hashlib.sha256(clip["text"].encode("utf-8")).hexdigest() != clip["reference_sha256"]:
            raise ValueError(f"reference text of {clip['id']} does not match its SHA-256")
    return clips


def add(total, counts):
    for key, value in counts.items():
        total[key] = total.get(key, 0) + value


def rates(counts):
    out = dict(counts)
    out["wer"] = counts["word_errors"] / counts["words"] if counts.get("words") else None
    out["cer"] = counts["character_errors"] / counts["characters"] if counts.get("characters") else None
    return out


def evaluate(clips, hypotheses, fold_yo=False):
    """Totals overall and per language over the clips that have a hypothesis."""
    overall, languages = {}, {}
    for clip in clips:
        if clip["id"] not in hypotheses:
            continue
        counts = score(clip["text"], hypotheses[clip["id"]], fold_yo)
        counts["clips"] = 1
        add(overall, counts)
        add(languages.setdefault(clip["language"], {}), counts)
    return {"overall": rates(overall) if overall else None,
            "languages": {language: rates(counts) for language, counts in sorted(languages.items())}}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("hypotheses", type=Path, help='JSON object {"clip id": "recognized text"}')
    parser.add_argument("--references", type=Path, default=REFERENCES, help="reference file (default: %(default)s)")
    parser.add_argument("--split", choices=["dev", "test"], help="score only this split")
    parser.add_argument("--fold-yo", action="store_true", help="fold Russian ё into е before scoring")
    parser.add_argument("--allow-missing", action="store_true", help="leave clips without a hypothesis out")
    parser.add_argument("--json", action="store_true", help="print the totals as JSON")
    args = parser.parse_args(argv)

    try:
        clips = load_references(args.references)
        with args.hypotheses.open(encoding="utf-8") as source:
            hypotheses = json.load(source)
    except (OSError, ValueError, KeyError) as error:
        print(f"score-wer: {error}", file=sys.stderr)
        return 2
    if not isinstance(hypotheses, dict) or not all(isinstance(v, str) for v in hypotheses.values()):
        print("score-wer: hypotheses must be a JSON object of id -> text", file=sys.stderr)
        return 2
    if args.split:
        clips = [clip for clip in clips if clip["split"] == args.split]
    known = {clip["id"] for clip in clips}
    missing = sorted(known - hypotheses.keys())
    if missing and not args.allow_missing:
        print(f"score-wer: {len(missing)} clips have no hypothesis (first: {missing[0]})", file=sys.stderr)
        return 2
    unknown = sorted(hypotheses.keys() - {clip["id"] for clip in load_references(args.references)})
    if unknown:
        print(f"score-wer: {len(unknown)} hypothesis ids are not reference clips (first: {unknown[0]})", file=sys.stderr)
        return 2

    result = evaluate(clips, hypotheses, args.fold_yo)
    if args.json:
        print(json.dumps(result, indent=2))
        return 0
    if result["overall"] is None:
        print("No clips scored.")
        return 0
    print(f"{'language':<10}{'clips':>6}{'word errors':>18}{'WER':>8}{'char errors':>18}{'CER':>8}")
    rows = list(result["languages"].items()) + [("all", result["overall"])]
    for language, counts in rows:
        print(f"{language:<10}{counts['clips']:>6}{counts['word_errors']:>9} / {counts['words']:<6}"
              f"{counts['wer'] * 100:>7.1f}%{counts['character_errors']:>9} / {counts['characters']:<6}"
              f"{counts['cer'] * 100:>7.2f}%")
    return 0


if __name__ == "__main__":
    sys.exit(main())
