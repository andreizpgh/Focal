"""Encode word starts and lexical continuation pieces for the pinned Ultra vocabulary."""
from pathlib import Path
import argparse
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1] / "first-pass-review"))
from variants import letters, pieces


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tokens", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    vocab = pieces(args.tokens)
    if set(vocab) != set(range(8193)) or vocab[8192] != "<blk>":
        raise ValueError("Expected the pinned Parakeet Ultra vocabulary")
    flags = bytearray(len(vocab))
    for i, piece in vocab.items():
        if letters(piece.lstrip(" ")):
            flags[i] = 2 if piece.startswith(" ") else 1
    args.output.write_bytes(flags)


if __name__ == "__main__":
    main()
