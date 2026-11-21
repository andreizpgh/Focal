"""Evaluate Focal drafts against punctuated transcripts. Only IDs and counts are exported.

The input manifest is a list of {id, language, split: dev|holdout, source, reference,
draft, words}. Paths are local. words is UTF-8 TSV: displayed token, start_ms,
end_ms, confidence (or null), doubtful (true|false). The native runner uses the
Horizon-Labs/punctuation-restoration-small INT8 graph. No network calls.
"""
import argparse
import array
from bisect import bisect_right
from collections import Counter, defaultdict
import csv
from dataclasses import dataclass
import json
import math
from pathlib import Path
import re
import runpy
import subprocess
import unicodedata

review = runpy.run_path(str(Path(__file__).resolve().parents[1] / "bench-review-model.py"))
wer = review["wer"]
# Combining accents used in Latin, Greek and Cyrillic orthographies can survive NFC.
LETTER = r"[^\W_][\u0300-\u036f\u1ab0-\u1aff\u1dc0-\u1dff\ufe20-\ufe2f]*"
WORD = re.compile(rf"(?:{LETTER})+(?:[-‑’'](?:{LETTER})+|(?<=\d)[.,:](?=\d)\d+)*")
SIGNS = {".", "?", "!", "…", ",", ":", ";", "—", "?!"}
END = {".", "!", "?", "…", "?!"}
STRIP = str.maketrans("", "", "«»„“”‘’‹›\"'()[]{}¿¡")
LABELS = ["", ".", ",", "?", "—", ":"]
THRESHOLD = .90


def lexical(text):
    return list(WORD.finditer(text))


def mark(gap, greek):
    sign = "".join(gap.translate(STRIP).split()).replace("...", "…").replace("–", "—")
    if sign == ";" or (greek and sign == ";"):
        return "?"
    return sign if not sign or sign in SIGNS else None


def softmax(logits):
    hi = max(logits)
    weights = [math.exp(x - hi) for x in logits]
    idx = weights.index(max(weights))
    return LABELS[idx], weights[idx] / sum(weights)


def owned_windows(terms, tokenizer):
    if not terms:
        return
    text = " ".join(terms)
    encoded = tokenizer.encode(text, add_special_tokens=False)
    ends = [hi for _, hi in encoded.offsets]
    targets = defaultdict(list)
    offset = 0
    for i, term in enumerate(terms):
        offset += len(term)
        j = bisect_right(ends, offset) - 1
        if j < 0 or ends[j] <= offset - len(term):
            raise ValueError("No model token for lexical word")
        targets[j].append(i)
        offset += 1
    for begin in range(0, len(ends), 96):
        end = min(begin + 96, len(ends))
        left, right = max(0, begin - 20), min(len(ends), end + 20)
        seq = [tokenizer.token_to_id("<bos>"), *encoded.ids[left:right], tokenizer.token_to_id("<eos>")]
        yield seq, [(word, token - left + 1) for token in range(begin, end) for word in targets.get(token, ())]


@dataclass
class Note:
    meta: dict
    terms: list
    gaps: list
    signs: list
    features: list
    gold: dict
    counts: Counter
    eligible: bool


def timed_positions(draft, path):
    positions = []
    flagged = []
    offset = 0
    for row in path.read_text().splitlines():
        word, start, stop, confidence, flag = row.rsplit("\t", 4)
        word = unicodedata.normalize("NFC", word)
        at = draft.index(word, offset)
        if draft[offset:at].strip():
            raise ValueError("Displayed text differs from timed words")
        offset = at + len(word)
        positions.append((offset, int(start), int(stop), 1.0 if confidence == "null" else float(confidence)))
        if flag == "true":
            flagged.append(len(positions) - 1)
    if draft[offset:].strip():
        raise ValueError("Incomplete timing file")
    return positions, flagged


def uses_greek_question(language, word):
    if language == "mixed":
        return any("GREEK" in unicodedata.name(ch, "") for ch in word)
    return language == "el"


def score_openers(counts, actual_gap, reference_gap):
    actual = re.findall("[¿¡]", actual_gap)
    wanted = re.findall("[¿¡]", reference_gap)
    if actual or wanted:
        counts["scored_inverted_openers"] += 1
        counts["wrong_inverted_openers"] += actual != wanted


def without_accents(words):
    return ["".join(ch for ch in unicodedata.normalize("NFD", word)
                    if not unicodedata.category(ch).startswith("M")) for word in words]


def read_note(meta):
    draft = unicodedata.normalize("NFC", Path(meta["draft"]).read_text().strip())
    ref = unicodedata.normalize("NFC", Path(meta["reference"]).read_text().strip())
    h, r = lexical(draft), lexical(ref)
    positions, flagged = timed_positions(draft, Path(meta["words"]))
    ends = [entry[0] for entry in positions]
    owners = [bisect_right(ends, m.start()) for m in h]
    align = review["aligned_words"]([w[0].casefold() for w in h], [w[0].casefold() for w in r])
    reference_words, draft_words = wer.words(ref), wer.words(draft)
    counts = Counter(notes=1, ref_words=len(reference_words), asr_words=len(draft_words),
                     word_errors=wer.distance(reference_words, draft_words),
                     lexical_words=len(h), reference_lexical_words=len(r), matched_words=len(align), flagged=len(flagged))
    if meta["source"] == "FLEURS":
        counts["word_errors_without_accents"] = wer.distance(without_accents(reference_words), without_accents(draft_words))
    by_owner = defaultdict(list)
    for i, owner in enumerate(owners):
        by_owner[owner].append(i)
    # A deleted reference word cannot appear in the UI. Count recoverable
    # spelling errors in displayed tokens separately from WER.
    has_error = {owner for owner, indices in by_owner.items() if any(i not in align for i in indices)}
    counts["displayed_words_with_error"] = len(has_error)
    for owner in flagged:
        if owner in by_owner:
            counts["flagged_matching_words" if owner not in has_error else "flagged_words_with_error"] += 1
            if positions[owner][3] < .35:
                counts["confidence_below_035"] += 1
                counts["confidence_below_035_with_error"] += owner in has_error
    language = meta["language"]
    gaps = [draft[word.end():h[i + 1].start() if i + 1 < len(h) else len(draft)] for i, word in enumerate(h)]
    signs = [mark(gap, uses_greek_question(language, word[0])) for word, gap in zip(h, gaps)]
    if h and r and align.get(0) == 0:
        score_openers(counts, draft[:h[0].start()], ref[:r[0].start()])
    gold = {}
    features = []
    run = 0
    for i, w in enumerate(h):
        run += 1
        if i+1 == len(h):
            features.append(None)
            break
        a, b = positions[owners[i]], positions[owners[i+1]]
        pause = max(0, b[1] - a[2]) / 1000 if owners[i] != owners[i+1] else 0
        next_word = h[i+1][0]
        features.append((1., min(pause, 3.)/3, float(pause >= .4), float(pause >= 1.),
                         float(next_word[0].isupper()), float(a[3] < .5 or b[3] < .5), min(run, 40)/40))
        if signs[i] in END:
            run = 0
        if i not in align or align.get(i+1) != align[i]+1:
            continue
        j = align[i]
        ref_gap = ref[r[j].end():r[j+1].start()]
        score_openers(counts, gaps[i], ref_gap)
        expected = mark(ref_gap, uses_greek_question(language, r[j][0]))
        if signs[i] is None or expected is None:
            continue
        gold[i] = expected
        if "\n\n" in gaps[i]:
            counts["scored_paragraph_breaks"] += 1
            counts["paragraph_breaks_inside_reference_sentence"] += expected not in END
        if meta["source"] in {"Maltese reading", "Publisher reading", "Publisher article"}:
            got, wanted = "\n\n" in gaps[i], "\n\n" in ref_gap
            counts["matching_paragraph_breaks"] += got and wanted
            counts["extra_paragraph_breaks"] += got and not wanted
            counts["missing_paragraph_breaks"] += wanted and not got
    eligible = meta["source"] != "YODAS2" or len(align) / max(len(r),len(h),1) >= .70
    return Note(meta, [w[0] for w in h], gaps, signs, features, gold, counts, eligible)


def sigmoid(z):
    return 1 / (1 + math.exp(-z))


def fit(notes):
    # Equal weight for each language. Never use evaluation notes or subtitle timing as input features.
    data = defaultdict(list)
    for note in notes:
        if note.meta["split"] == "dev" and note.eligible:
            data[note.meta["language"]].extend((note.features[i], y in END) for i,y in note.gold.items())
    if not data:
        raise ValueError("No development material")
    weights = [0.] * 7
    for _ in range(180):
        grad = [0.] * len(weights)
        for rows in data.values():
            if not rows:
                continue
            scale = 1 / (len(rows) * len(data))
            for x,y in rows:
                err = (sigmoid(sum(w*v for w,v in zip(weights,x))) - y)*scale
                for k,v in enumerate(x):
                    grad[k] += err*v
        for k in range(len(weights)):
            weights[k] -= 2.5 * (grad[k] + (.001 * weights[k] if k else 0))
    return weights


def compare(note, proposals):
    counts = Counter(scored_sites=len(note.gold))
    for i, expected in note.gold.items():
        before = note.signs[i]
        after = proposals[i]
        counts["mark_errors"] += after != expected
        counts["missing_ends"] += expected in END and after not in END
        counts["extra_ends"] += expected not in END and after in END
        counts["fixed"] += before != expected and after == expected
        counts["introduced"] += before == expected and after != expected
        counts["still_wrong_after_change"] += before != after and before != expected and after != expected
    return dict(counts)


def timing_cutoff(notes, weights):
    """Choose a development cutoff by net exact fixes, with no language losing marks."""
    candidates = [n / 20 for n in range(3, 20)]
    gains = {cutoff: Counter() for cutoff in candidates}
    for note in notes:
        if note.meta["split"] != "dev" or not note.eligible:
            continue
        for i, wanted in note.gold.items():
            if note.gaps[i].strip():
                continue
            probability = sigmoid(sum(w * x for w, x in zip(weights, note.features[i])))
            for cutoff in candidates:
                if probability >= cutoff:
                    gains[cutoff][note.meta["language"]] += int(wanted == ".") - int(wanted == "")
    usable = [(sum(by_lang.values()), cutoff) for cutoff, by_lang in gains.items()
              if by_lang and min(by_lang.values()) >= 0 and sum(by_lang.values()) > 0]
    return max(usable)[1] if usable else 1.0


def proposed_text(draft, note, signs):
    """Insert only approved signs. Retain every original character, including line breaks."""
    pieces = []
    cursor = 0
    for i, word in enumerate(lexical(draft)):
        if signs[i] == note.signs[i]:
            continue
        if note.gaps[i].strip() or not signs[i]:
            raise ValueError("A suggestion would overwrite an existing mark")
        pieces.append(draft[cursor:word.end()])
        sign = signs[i]
        if sign == "?" and note.meta["language"] == "el":
            sign = ";"
        pieces.append(" —" if sign == "—" else sign)
        cursor = word.end()
    return "".join(pieces) + draft[cursor:]


def review_packet(folder, notes_and_texts):
    """Counterbalance each passage across two independent readers of the language."""
    from os.path import relpath

    folder.mkdir(parents=True, exist_ok=True)
    index = defaultdict(int)
    with (folder / "assignment-key.tsv").open("w", newline="") as key_file:
        key = csv.writer(key_file, delimiter="\t")
        key.writerow(["language", "passage", "reader", "variant", "source_id"])
        for note, original, proposed in notes_and_texts:
            lang = note.meta["language"]
            index[lang] += 1
            passage = f"{index[lang]:02d}"
            for reader in (0, 1):
                directory = folder / lang / f"reader-{reader + 1}"
                directory.mkdir(parents=True, exist_ok=True)
                variant = (index[lang] + reader) % 2
                (directory / f"{passage}.txt").write_text((original, proposed)[variant])
                with (directory / "worksheet.tsv").open("a" if index[lang] > 1 else "w", newline="") as sheet:
                    writer = csv.writer(sheet, delimiter="\t")
                    if index[lang] == 1:
                        writer.writerow(["passage", "audio", "seconds", "word_edits", "mark_edits", "paragraph_edits"])
                    writer.writerow([passage, relpath(note.meta["audio"], directory), "", "", "", ""])
                key.writerow([lang, passage, reader + 1, ("focal", "multilingual")[variant], note.meta["id"]])


def screen(args):
    from tokenizers import Tokenizer
    args.work.mkdir(parents=True, exist_ok=True)
    manifest = json.loads(args.manifest.read_text())
    notes = [read_note(item) for item in manifest]
    if args.timing_weights:
        frozen = json.loads(args.timing_weights.read_text())
        learned = frozen["timing_weights"]
        calibrated_cutoff = frozen["timing_cutoff"]
        if len(learned) != 7 or not all(math.isfinite(w) for w in learned):
            raise ValueError("Expected seven finite timing weights")
    else:
        learned = fit(notes)
        calibrated_cutoff = timing_cutoff(notes, learned)
    tok = Tokenizer.from_file(str(args.model / "tokenizer.json"))
    tok.no_truncation()  # owned_windows supplies bounded context for the entire note.
    batches = [(note, list(owned_windows(note.terms,tok))) for note in notes]
    scores = args.logits or args.work / "logits.bin"
    windows = args.work / "windows.txt"
    with windows.open("w") as output:
        for _, groups in batches:
            for seq, _ in groups:
                print(*seq, file=output)
    if args.runner:
        with (args.work / "native.txt").open("w") as timing:
            subprocess.run([args.runner, args.model / "model_quantized.onnx", windows, "2", "0", scores],
                           stdout=timing, check=True)
    results = []
    packet = []
    with scores.open("rb") as f:
        for note, groups in batches:
            classifier = list(note.signs)
            calibrated = list(note.signs)
            contextual = list(note.signs)
            for seq, targets in groups:
                logits = array.array("f")
                logits.fromfile(f, len(seq) * len(LABELS))
                for i, pos in targets:
                    if note.gaps[i].strip() or note.features[i] is None:
                        continue
                    boundary_probability = sigmoid(sum(w*v for w,v in zip(learned,note.features[i])))
                    if boundary_probability >= THRESHOLD:
                        classifier[i] = "."
                    if boundary_probability >= calibrated_cutoff:
                        calibrated[i] = "."
                    start = pos * len(LABELS)
                    predicted, prob = softmax(logits[start:start + len(LABELS)])
                    if predicted and prob >= THRESHOLD:
                        contextual[i] = predicted
            result = {k: note.meta[k] for k in ("id", "language", "source", "split")}
            result.update(note.counts)
            result["caption_eligible"] = note.eligible
            for name, values in (("focal",note.signs), ("timing",classifier),
                                 ("timing_calibrated",calibrated), ("multilingual",contextual)):
                result[name] = compare(note, values)
            results.append(result)
            held_out = note.meta["split"] == "holdout"
            if note.meta.get("original_split") == "holdout":
                words = max(note.counts["lexical_words"], note.counts["reference_lexical_words"], 1)
                held_out = note.counts["matched_words"] / words >= .70
            if args.review_packet and note.eligible and held_out:
                original = Path(note.meta["draft"]).read_text()
                packet.append((note, original, proposed_text(original, note, contextual)))
        if f.read(1):
            raise ValueError("Extra model output")
    result = {"threshold": THRESHOLD, "timing_weights": learned, "timing_cutoff": calibrated_cutoff, "notes": results}
    temporary = args.output.with_suffix(args.output.suffix + ".part")
    temporary.write_text(json.dumps(result, indent=2) + "\n")
    temporary.replace(args.output)
    if args.review_packet:
        review_packet(args.review_packet, packet)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ("manifest", "model", "work", "output"):
        parser.add_argument("--"+name,type=Path,required=True)
    backend = parser.add_mutually_exclusive_group(required=True)
    backend.add_argument("--runner", type=Path, help="Native ONNX Runtime replay executable")
    backend.add_argument("--logits", type=Path, help="Replay float32 logits for the identical input windows")
    parser.add_argument("--timing-weights", type=Path, help="Use frozen development weights for a new control set")
    parser.add_argument("--review-packet", type=Path, help="Private, counterbalanced reader worksheets and texts")
    screen(parser.parse_args())
