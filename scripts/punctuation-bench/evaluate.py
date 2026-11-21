"""Compare punctuation at aligned word boundaries on continuous, already formatted ASR."""
import argparse
import array
from collections import Counter
from dataclasses import dataclass
import importlib.util
import json
import math
from pathlib import Path
import re
import subprocess
import resource
import time

WORD = re.compile(r"[^\W_]+(?:[-‑’'][^\W_]+|(?<=\d)[.,:](?=\d)\d+)*")
SIGNS = {".", ",", "?", "!", ":", ";", "—", "…", "?!"}
TERMINAL = {".", "?", "!", "…", "?!"}
LABELS = {"PERIOD": ".", "COMMA": ",", "QUESTION": "?", "TIRE": "—", "VOSKL": "!",
          "DVOETOCHIE": ":", "PERIODCOMMA": ";", "DEFIS": "-", "QUESTIONVOSKL": "?!",
          "MNOGOTOCHIE": "…", "O": ""}
# Use exactly the same minimum-edit alignment as the word-review experiment.
spec = importlib.util.spec_from_file_location("review_bench", Path(__file__).resolve().parents[1] / "bench-review-model.py")
review = importlib.util.module_from_spec(spec)
spec.loader.exec_module(review)


@dataclass(frozen=True)
class Site:
    word: str
    end: int
    gap: str

    @property
    def mark(self):
        clean = self.gap.translate(str.maketrans("", "", '«»„“”"()[]{}'))
        clean = "".join(clean.split()).replace("...", "…").replace("–", "—")
        return clean if clean in SIGNS or not clean else None


def sites(text):
    words = list(WORD.finditer(text))
    return [Site(w[0], w.end(), text[w.end():words[i + 1].start() if i + 1 < len(words) else len(text)])
            for i, w in enumerate(words)]


def windows(sites_, tokenizer):
    ids = [tokenizer.encode(s.word.lower(), add_special_tokens=False).ids for s in sites_]
    # Reserve both context margins even for interior blocks. Overlong units get a single unknown ID.
    unk = tokenizer.token_to_id("[UNK]")
    ids = [word if 0 < len(word) <= 80 else [unk] for word in ids]
    start = 0
    while start < len(ids):
        end, owned = start, 0
        while end < len(ids) and owned + len(ids[end]) <= 80:
            owned += len(ids[end])
            end += 1
        left, right = start, end
        used = 0
        while left and used + len(ids[left - 1]) <= 24:
            left -= 1
            used += len(ids[left])
        used = 0
        while right < len(ids) and used + len(ids[right]) <= 24:
            used += len(ids[right])
            right += 1
        sequence = [tokenizer.token_to_id("[CLS]")]
        positions = []
        for i in range(left, right):
            if start <= i < end:
                positions.append(len(sequence))
            sequence.extend(ids[i])
        sequence.append(tokenizer.token_to_id("[SEP]"))
        yield sequence, positions
        start = end


def predict(raw, windows_, labels):
    floats = array.array("f")
    floats.frombytes(raw)
    offset = 0
    result = []
    for ids, positions in windows_:
        for p in positions:
            start = (offset + p) * len(labels)
            logits = floats[start:start + len(labels)]
            high = max(logits)
            weights = Counter()
            for mark, logit in zip(labels, logits):
                weights[mark] += math.exp(logit - high)
            mark, weight = weights.most_common(1)[0]
            result.append((mark, weight / sum(weights.values())))
        offset += len(ids)
    if len(floats) != offset * len(labels):
        raise ValueError("Unexpected logits length")
    return result


def insertions(text, entries, predictions, threshold=.90):
    parts = []
    start = 0
    for s, (mark, confidence) in zip(entries, predictions, strict=True):
        if s.gap.strip() or mark not in SIGNS or confidence < threshold:
            continue
        parts.append(text[start:s.end])
        parts.append(" —" if mark == "—" else mark)
        start = s.end
    return "".join(parts) + text[start:]


def score(candidate, baseline, gold):
    metrics = Counter()
    for i, target in gold.items():
        base, got = baseline[i], candidate[i]
        if got != target:
            metrics["review_sites"] += 1
            kind = "missing" if not got else "extra" if not target else "wrong_type"
            metrics[kind] += 1
        if got != base:
            kind = "fixed" if got == target else "introduced" if base == target else "still_wrong"
            metrics[kind] += 1
        for field, marks in (("sentence", TERMINAL), ("comma", {","})):
            if got in marks and target in marks:
                metrics[field + "_tp"] += 1
            elif got in marks:
                metrics[field + "_fp"] += 1
            elif target in marks:
                metrics[field + "_fn"] += 1
    return dict(sorted(metrics.items()))


def reference_sites(draft, gold, repeated):
    def keys(entries):
        return [s.word.casefold().replace("ё", "е") for s in entries]

    hyp, ref = keys(draft), keys(gold)
    if repeated:
        # Compare only the centre of six exact consecutive words, unique in the printed key.
        # Repetitions of the passage are expected in the recording. Fragment edges are unscored.
        centres = {}
        for i in range(2, len(ref) - 3):
            context = tuple(ref[i - 2:i + 4])
            centres[context] = None if context in centres else i
        aligned = {}
        for i in range(2, len(hyp) - 3):
            j = centres.get(tuple(hyp[i - 2:i + 4]))
            if j is not None:
                aligned[i] = j
        matched = None
    else:
        pairs = review.aligned_words(hyp, ref)
        aligned = {i: j for i, j in pairs.items() if pairs.get(i + 1) == j + 1
                   or (i == len(hyp) - 1 and j == len(ref) - 1)}
        matched = len(pairs)
    return {i: gold[j].mark for i, j in aligned.items()
            if draft[i].mark is not None and gold[j].mark is not None}, matched


def evaluate(args):
    from tokenizers import Tokenizer
    text = args.asr.read_text().strip()
    draft = sites(text)
    args.out.mkdir(parents=True, exist_ok=True)
    prep = time.perf_counter()
    before_tokenizer_kib = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    tok = Tokenizer.from_file(str(args.model / "tokenizer.json"))
    groups = list(windows(draft, tok))
    prep_ms = (time.perf_counter() - prep) * 1000
    tokenizer_peak_kib = resource.getrusage(resource.RUSAGE_SELF).ru_maxrss
    ids_file, logits = args.out / "windows.txt", args.out / "logits.bin"
    ids_file.write_text("".join(" ".join(map(str, ids)) + "\n" for ids, _ in groups))
    if args.logits:
        logits = args.logits
    else:
        with (args.out / "timing.txt").open("w") as timing:
            subprocess.run([args.runner.resolve(), args.model / "rupunct_small_int8.onnx", ids_file,
                            str(args.threads), str(args.repeats), logits], check=True, stdout=timing)
    config = json.loads((args.model / "config.json").read_text())["id2label"]
    labels = [LABELS[config[str(i)].rsplit("_", 1)[-1]] for i in range(len(config))]
    predictions = predict(logits.read_bytes(), groups, labels)
    corrected = insertions(text, draft, predictions)
    after = sites(corrected)
    if [s.word for s in after] != [s.word for s in draft] or corrected.count("\n") != text.count("\n"):
        raise ValueError("Lexical sequence or paragraph breaks changed")
    baseline = [s.mark for s in draft]
    # Rebuilding every mark is a diagnostic that can remove correct punctuation.
    rebuilt = [mark if mark in SIGNS else "" for mark, _ in predictions]
    (args.out / "proposed.txt").write_text(corrected + "\n")
    (args.out / "workload.json").write_text(json.dumps({
        "asr_words": len(draft), "paragraphs": len(text.split("\n\n")),
        "window_lengths": [len(ids) for ids, _ in groups], "tokenizer_load_and_encode_ms": round(prep_ms, 3),
        "python_peak_before_tokenizer_kib": before_tokenizer_kib, "python_peak_after_tokenizer_kib": tokenizer_peak_kib,
    }, indent=2) + "\n")
    reference = args.reference or args.repeated_reference
    if reference is None:
        return
    gold = sites(reference.read_text().strip())
    comparable, matched = reference_sites(draft, gold, args.repeated_reference is not None)
    if not comparable:
        raise ValueError("No comparable boundaries")
    marks = [s.mark for s in after]
    changes = [{"word_index": i, "before": baseline[i], "after": m, "scored": i in comparable,
                "target": comparable.get(i)} for i, m in enumerate(marks) if m != baseline[i]]
    report = {"ref_words": len(gold), "asr_words": len(draft), "matched_words": matched,
              "scored_sites": len(comparable), "reference_marks_scored": sum(bool(x) for x in comparable.values()),
              "unchanged_words": len(after), "paragraphs": len(text.split("\n\n")),
              "all_insertions": len(changes), "scored_insertions": sum(c["scored"] for c in changes),
              "window_lengths": [len(ids) for ids, _ in groups], "tokenizer_load_and_encode_ms": round(prep_ms, 3),
              "baseline": score(baseline, baseline, comparable), "fill90": score(marks, baseline, comparable),
              "rebuild": score(rebuilt, baseline, comparable)}
    report["exploratory_thresholds"] = {}
    for threshold in (.50, .75, .95):
        output = sites(insertions(text, draft, predictions, threshold))
        report["exploratory_thresholds"][str(threshold)] = score([s.mark for s in output], baseline, comparable)
    (args.out / "changes.json").write_text(json.dumps(changes, ensure_ascii=False, indent=2))
    (args.out / "scores.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    refs = parser.add_mutually_exclusive_group()
    refs.add_argument("--reference", type=Path, help="Independent punctuated reference, read once")
    refs.add_argument("--repeated-reference", type=Path, help="Printed key for a dictation with repeated phrases")
    inputs = parser.add_mutually_exclusive_group(required=True)
    inputs.add_argument("--runner", type=Path, help="Native executable for local inference")
    inputs.add_argument("--logits", type=Path, help="Score native float32 outputs captured on another CPU")
    for name in ["model", "asr", "out"]:
        parser.add_argument("--" + name, type=Path, required=True)
    parser.add_argument("--threads", type=int, default=2)
    parser.add_argument("--repeats", type=int, default=20)
    evaluate(parser.parse_args())
