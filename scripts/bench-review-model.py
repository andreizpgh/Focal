#!/usr/bin/env python3
"""Screen a local model for word review and punctuation on Focal's public RU/EN DEV clips.

Input JSON maps each clip ID to Focal's formatted words, each with text, confidence, flag.
The default contains 12 public FLEURS clips. The synthetic suite tests given alternatives
and paragraph boundaries. Start a local, cloud-disabled Ollama server with the model installed.
Run: python3 scripts/bench-review-model.py --model NAME --suite speech
Only aggregate scores are printed. Speech references are never sent to the local model.
"""
import argparse
from collections import Counter, defaultdict
import importlib.util
import json
from pathlib import Path
import re
from statistics import median
from urllib.request import build_opener, ProxyHandler, Request

SCORE_FILE = Path(__file__).with_name("score-wer.py")
spec = importlib.util.spec_from_file_location("score_wer", SCORE_FILE)
wer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(wer)

MARKS = ".,:;!?"
TRAILING = re.compile(r"(?<=[^\s.,:;!?])[.,:;!?]+(?=\s|$)")
WORD_PROMPT = (
    "Check only the marked span in a speech transcript. If it is plausible, return KEEP. "
    "If it is a clear recognition error, return only the corrected word or short phrase in the same language. "
    "Keep names, numbers and negation when uncertain. No explanation."
)
PUNCT_PROMPT = (
    "Restore missing sentence punctuation in this speech transcript. Return the full text in its original language. "
    "Copy every word, capital letter and existing correct mark exactly. Only change punctuation. No explanation."
)
CHOICE_PROMPT = (
    "Choose the wording that fits the missing span in this transcript. A is what the recognizer heard. "
    "B is another reading. If uncertain keep A. Reply with just A or B."
)
PARAGRAPH_PROMPT = (
    "The numbered sentences are one short voice note. Insert a paragraph break only at a clear change of topic. "
    "Reply with a JSON array of sentence numbers after which to insert a break, or [] if none. "
    "Do not break after the last sentence. No explanation."
)
BENCHMARK = wer.ROOT / "docs/benchmarks/2026-11-19-review"
FIXTURES = BENCHMARK / "fixtures.json"


def aligned_words(hyp, ref):
    """Map exact matches on a minimum-edit path. Ties prefer diagonal, then a deleted reference word."""
    rows = [list(range(len(ref) + 1))]
    for i, h in enumerate(hyp, 1):
        row = [i]
        prev = rows[-1]
        for j, r in enumerate(ref, 1):
            row.append(min(prev[j - 1] + (h != r), prev[j] + 1, row[-1] + 1))
        rows.append(row)
    matches = {}
    i, j = len(hyp), len(ref)
    while i or j:
        if i and j and rows[i][j] == rows[i - 1][j - 1] + (hyp[i - 1] != ref[j - 1]):
            if hyp[i - 1] == ref[j - 1]:
                matches[i - 1] = j - 1
            i, j = i - 1, j - 1
        elif j and rows[i][j] == rows[i][j - 1] + 1:
            j -= 1
        else:
            i -= 1
    return matches


def selections(words):
    """Natural flags, then two distinct unflagged tap controls, selected without references."""
    i = 0
    while i < len(words):
        start = i
        i += 1
        if not words[start]["flag"]:
            continue
        while i < len(words) and words[i]["flag"] and not words[i - 1]["text"].endswith((".", "!", "?", "…")):
            i += 1
        yield "flag", start, i
    available = [i for i, w in enumerate(words) if not w["flag"] and w["confidence"] is not None
                 and w["text"].strip(MARKS).isalpha()]
    if available:
        low = min(available, key=lambda i: words[i]["confidence"])
        yield "low_tap", low, low + 1
        available.remove(low)
    if available:
        high = max(available, key=lambda i: words[i]["confidence"])
        yield "high_tap", high, high + 1


def surface_boundaries(text):
    """One mark after each lexical word. Ignore boundaries containing unsupported signs."""
    matches = list(wer.WORD.finditer(text))
    terms = [m.group().casefold() for m in matches]
    marks = []
    for i, match in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(text)
        sep = text[match.end():end].strip()
        marks.append(sep if all(c in MARKS for c in sep) else None)
    return terms, marks


def punct_scores(source, reply, reference):
    # Do not apply even a punctuation edit if it changes a word, case, number, identifier or internal mark.
    accepted = bool(reply) and TRAILING.sub("", reply) == TRAILING.sub("", source)
    result = reply if accepted else source
    h, before = surface_boundaries(source)
    r, gold = surface_boundaries(reference)
    _, after = surface_boundaries(result)
    alignment = aligned_words(h, r)
    scores = Counter(clips=1, rejected=int(not accepted))
    for i, j in alignment.items():
        end = i == len(h) - 1 and j == len(r) - 1
        if not end and alignment.get(i + 1) != j + 1:
            continue
        if gold[j] is None or before[i] is None:
            continue
        scores["boundaries"] += 1
        scores["gold_marks"] += bool(gold[j])
        scores["before_errors"] += before[i] != gold[j]
        scores["after_errors"] += after[i] != gold[j]
        scores["fixed"] += before[i] != gold[j] and after[i] == gold[j]
        scores["damaged"] += before[i] == gold[j] and after[i] != gold[j]
    return scores


def word_scores(words, start, stop, reply, reference):
    original = " ".join(words)
    span = " ".join(words[start:stop])
    # Eligibility is intentionally narrow. Suggestions can contain up to three words and internal apostrophes/hyphens.
    valid = bool(re.fullmatch(r"[^\W_]+(?:[-'’][^\W_]+)*(?: [^\W_]+(?:[-'’][^\W_]+)*){0,2}", reply)) and len(reply) <= 64
    keep = reply == "KEEP"
    alternative = span if keep or not valid else reply
    edited = " ".join([*words[:start], alternative, *words[stop:]])
    baseline = wer.distance(wer.words(reference), wer.words(original))
    after = wer.distance(wer.words(reference), wer.words(edited))
    changed = wer.words(span) != wer.words(alternative)
    return Counter(queries=1, changed=int(changed), rejected=int(not keep and not valid),
                   improved=int(after < baseline), harmed=int(after > baseline),
                   changed_tie=int(changed and after == baseline), error_delta=after - baseline)


def evaluate(inputs, clips, ask):
    scores = defaultdict(Counter)
    for clip in clips:
        words = inputs[clip["id"]]
        surfaces = [w["text"] for w in words]
        source = " ".join(surfaces)
        lang, ref = clip["language"], clip["text"]
        for group, start, stop in selections(words):
            # Only the previously recognized context and span reach the model.
            marked = " ".join([*surfaces[max(0, start - 12):start], "[[", *surfaces[start:stop],
                               "]]", *surfaces[stop:stop + 12]])
            reply = ask(WORD_PROMPT, marked, 24)
            scores[lang, group].update(word_scores(surfaces, start, stop, reply, ref))
        for group, draft in [("punct_asr", source), ("punct_stripped", TRAILING.sub("", source))]:
            reply = ask(PUNCT_PROMPT, draft, 128)
            scores[lang, group].update(punct_scores(draft, reply, ref))
    return {lang: {group: dict(v) for (l, group), v in sorted(scores.items()) if l == lang}
            for lang in sorted({clip["language"] for clip in clips})}


def speech_baseline(inputs, clips):
    """Count selected spans with a substituted or inserted word on the same minimum-edit path."""
    counts = defaultdict(Counter)
    for clip in clips:
        surfaces = inputs[clip["id"]]
        tokens = [wer.words(w["text"]) for w in surfaces]
        hyp = [token for group in tokens for token in group]
        ref = wer.words(clip["text"])
        aligned = aligned_words(hyp, ref)
        row = counts[clip["language"]]
        row.update(clips=1, reference_words=len(ref), word_errors=wer.distance(hyp, ref))
        for name, start, stop in selections(surfaces):
            first = sum(map(len, tokens[:start]))
            last = first + sum(map(len, tokens[start:stop]))
            row[name + "_with_unmatched_word"] += any(i not in aligned for i in range(first, last))
    return dict(counts)


def paragraph_breaks(reply, size):
    try:
        items = json.loads(reply)
    except ValueError:
        return None
    if not isinstance(items, list) or any(type(i) is not int or not 1 <= i < size for i in items):
        return None
    return set(items) if items == sorted(set(items)) else None


def synthetic_screen(fixtures, ask):
    choices, breaks = Counter(), Counter()
    for case in fixtures["choices"]:
        # Put the same correct answer in either position, keeping sentence context identical.
        for correct_first in (True, False):
            a, b = case["correct"], case["other"]
            if not correct_first:
                a, b = b, a
            reply = ask(CHOICE_PROMPT, f"{case['context']}\nA: {a}\nB: {b}", 6)
            choices["queries"] += 1
            choices["rejected"] += reply not in ("A", "B")
            choices["fixed"] += not correct_first and reply == "B"
            choices["damaged"] += correct_first and reply == "B"
    for case in fixtures["paragraphs"]:
        sentences = case["sentences"]
        gold = set(case["after"])
        lines = [f"{i}. {s}" for i, s in enumerate(sentences, 1)]
        reply = ask(PARAGRAPH_PROMPT, "\n".join(lines), 20)
        result = paragraph_breaks(reply, len(lines))
        breaks["rejected"] += result is None
        result = result or set()
        breaks["notes"] += 1
        breaks["boundaries"] += len(sentences) - 1
        breaks["gold_breaks"] += len(gold)
        breaks["found"] += len(gold & result)
        breaks["extra"] += len(result - gold)
        breaks["missed"] += len(gold - result)
        breaks["exact_notes"] += gold == result
    return {"word_choice": dict(choices), "paragraphs": dict(breaks)}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("inputs", type=Path, nargs="?", default=BENCHMARK / "fleurs-dev-words.json",
                        help="formatted word receipts for the speech suite")
    parser.add_argument("--model", required=True)
    parser.add_argument("--port", type=int, default=11434)
    parser.add_argument("--suite", choices=("speech", "synthetic", "structured"), default="speech")
    args = parser.parse_args()
    if args.suite == "speech":
        inputs = json.loads(args.inputs.read_text())
        clips = [c for c in wer.load_references(wer.REFERENCES) if c["split"] == "dev" and c["language"] in {"en", "ru"}]
        if set(inputs) != {c["id"] for c in clips}:
            parser.error("expected exactly the RU and EN development clip IDs")
    base = f"http://127.0.0.1:{args.port}"
    local = build_opener(ProxyHandler({}))

    def post(path, body):
        request = Request(base + path, data=json.dumps(body).encode(), headers={"Content-Type": "application/json"})
        with local.open(request, timeout=120) as response:
            return json.load(response)

    times = defaultdict(list)
    options = dict(temperature=0, seed=42, num_thread=2, num_gpu=0, num_ctx=512)
    # Warm once, outside the latency samples. Both stop tokens and the no-thinking prefix come from the model template.
    post("/api/generate", {"model": args.model, "keep_alive": "2m", "options": options})
    tasks = {WORD_PROMPT: "word", PUNCT_PROMPT: "punct", CHOICE_PROMPT: "choice", PARAGRAPH_PROMPT: "paragraph"}

    def ask(instruction, text, limit):
        task = tasks[instruction]
        output = {}
        if args.suite == "structured":
            if task == "choice":
                output["format"] = {"type": "string", "enum": ["A", "B"]}
                instruction += ' Encode your answer as a JSON string, "A" or "B".'
            else:
                count = len(text.splitlines()) - 1
                output["format"] = {"type": "array", "items": {"type": "integer", "enum": list(range(1, count + 1))},
                                    "maxItems": count}
        result = post("/api/chat", dict(model=args.model, messages=[{"role": "system", "content": instruction},
                      {"role": "user", "content": text}], options={**options, "num_predict": limit},
                      think=False, stream=False, keep_alive="2m", **output))
        times[task].append(result["total_duration"] / 1e9)
        if result.get("done_reason") == "length":
            return ""
        reply = result["message"]["content"].strip()
        if args.suite == "structured" and task == "choice":
            try:
                value = json.loads(reply)
                return value if value in ("A", "B") else ""
            except ValueError:
                return ""
        return reply

    try:
        if args.suite == "speech":
            scores = evaluate(inputs, clips, ask)
            baseline = speech_baseline(inputs, clips)
        else:
            scores = synthetic_screen(json.loads(FIXTURES.read_text()), ask)
            baseline = None
        print(json.dumps({"model": args.model, "baseline": baseline, "scores": scores, "warm_median_seconds":
                         {k: round(median(v), 3) for k, v in times.items()}, "requests":
                         {k: len(v) for k, v in times.items()}}, indent=2))
    finally:
        post("/api/generate", {"model": args.model, "keep_alive": 0})


if __name__ == "__main__":
    main()
