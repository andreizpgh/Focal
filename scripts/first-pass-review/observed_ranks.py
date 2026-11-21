"""Join stock-runtime logit differences with the actual emitted-token log probabilities."""
import argparse
import math
from pathlib import Path

from variants import pieces, ranked_groups, token_groups


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("work", type=Path)
    parser.add_argument("vocab", type=Path)
    args = parser.parse_args()
    work = args.work
    vocab = pieces(args.vocab)
    with (work / "ranks.tsv").open("w") as out:
        for (_, _, tokens), ranks in zip(token_groups(work / "tokens.tsv"), ranked_groups(work / "logits.tsv"), strict=True):
            print("W", file=out)
            for i, (token, rank) in enumerate(zip(tokens, ranks, strict=True)):
                winner, second, second_diff, third, third_diff = rank
                original = bytes.fromhex(token[3]).decode()
                if vocab[int(winner)] != original or not float(third_diff) <= float(second_diff) <= 0:
                    raise ValueError("Logits do not identify the token returned by the stock recognizer")
                logp = float(token[2])
                if logp < math.log(.5):
                    print(i, winner, token[2], second, logp + float(second_diff), third,
                          logp + float(third_diff), sep="\t", file=out)


if __name__ == "__main__":
    main()
