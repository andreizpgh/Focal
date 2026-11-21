"""Convert recorded greedy decisions into bounded single-piece counterfactuals for Focal's WordBuilder."""
import argparse
import json
import math
from pathlib import Path
import unicodedata

MIN_PROB = .10
MIN_RATIO = .35


def pieces(path):
    vocab = {}
    for line in path.read_text().splitlines():
        spelling, index = line.rsplit(" ", 1)
        vocab[int(index)] = " " + spelling[1:] if spelling.startswith("▁") else spelling
    return vocab


def letters(text):
    return bool(text and any(c.isalpha() for c in text) and
                all(c.isalpha() or unicodedata.category(c).startswith("M") for c in text))


def ranked_groups(path):
    current = None
    with path.open() as file:
        for line in file:
            line = line.rstrip("\n")
            if line == "W":
                if current is not None:
                    yield current
                current = []
            elif current is None:
                raise ValueError("Rank rows outside a window")
            else:
                current.append(line.split("\t"))
    if current is not None:
        yield current


def token_groups(path):
    key, start, tokens = None, 0, []
    with path.open() as file:
        for line in file:
            fields = line.rstrip("\n").split("\t")
            if fields[0] == "W":
                if key is not None:
                    yield key, start, tokens
                _, key, start = fields
                tokens = []
            elif fields[0] == "T" and key is not None:
                tokens.append(fields[1:])
            else:
                raise ValueError("Unexpected token receipt")
    if key is not None:
        yield key, start, tokens


def candidate_pieces(tokens, ranks, vocab):
    seen = set()
    for pos, winner, lp, second, lp2, third, lp3 in ranks:
        i, old, logp = int(pos), vocab[int(winner)], float(lp)
        emitted = bytes.fromhex(tokens[i][3]).decode()
        if old != emitted or not math.isclose(float(tokens[i][2]), logp, abs_tol=1e-6) or i in seen:
            raise ValueError("Ranked token differs from the emitted token")
        seen.add(i)
        for rank, (num, alt_logp) in enumerate(((second, lp2), (third, lp3)), 2):
            new = vocab[int(num)]
            probability = math.exp(float(alt_logp))
            ratio = math.exp(float(alt_logp) - logp)
            if probability > math.exp(logp) + 1e-6:
                raise ValueError("Alternative outranks the original")
            if probability < MIN_PROB or ratio < MIN_RATIO:
                continue
            if old.startswith(" ") != new.startswith(" ") or not all(letters(s.strip()) for s in (old, new)):
                continue
            if old.casefold() != new.casefold():
                yield i, rank, old, new, probability, ratio
    # The C++ and text receipts round the threshold at float precision.
    expected = {i for i, token in enumerate(tokens) if float(token[2]) < math.log(.5)}
    if any(abs(float(tokens[i][2]) - math.log(.5)) > 1e-6 for i in seen ^ expected):
        raise ValueError("Missing first-pass ranks")


def write_window(file, key, start, tokens):
    print("W", key, start, sep="\t", file=file)
    for token in tokens:
        print("T", *token, sep="\t", file=file)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path)
    parser.add_argument("vocab", type=Path)
    args = parser.parse_args()
    work = args.work
    vocab = pieces(args.vocab)
    with (work / "variants.tsv").open("w") as output, (work / "variants.jsonl").open("w") as metadata:
        for (key, start, tokens), ranks in zip(token_groups(work / "tokens.tsv"), ranked_groups(work / "ranks.tsv"), strict=True):
            write_window(output, key, start, tokens)
            for i, rank, old, new, prob, ratio in candidate_pieces(tokens, ranks, vocab):
                variant = f"{key}/{i}/{rank}"
                row = dict(key=key, variant=variant, token=i, rank=rank, old=old, prob=prob, ratio=ratio)
                metadata.write(json.dumps(row) + "\n")
                edited = list(tokens)
                edited[i] = [*tokens[i][:3], new.encode().hex()]
                write_window(output, variant, start, edited)


if __name__ == "__main__":
    main()
