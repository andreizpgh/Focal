"""Pack the pinned WordPiece vocabulary for memory-mapped, allocation-light lookup on Android."""
import argparse
import json
from pathlib import Path
import struct


def pack(tokenizer, output):
    model = json.loads(tokenizer.read_text())["model"]
    if model["type"] != "WordPiece" or model["continuing_subword_prefix"] != "##":
        raise ValueError("Expected the RUPunct WordPiece tokenizer")
    entries = sorted((token.encode(), i) for token, i in model["vocab"].items())
    offset = 4 + 8 * len(entries)
    with output.open("wb") as f:
        f.write(struct.pack("<I", len(entries)))
        for token, token_id in entries:
            f.write(struct.pack("<II", offset, token_id))
            offset += len(token)
        for token, _ in entries:
            f.write(token)


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("tokenizer", type=Path)
    parser.add_argument("output", type=Path)
    args = parser.parse_args()
    pack(args.tokenizer, args.output)
