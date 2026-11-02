#!/usr/bin/env python3
"""Measure a pinned whisper-cli against an explicit, consented local corpus.

Manifest: JSON array of {"audio": "relative/path.wav", "language": "ru",
"text": "Reference transcript"}. No recordings or transcripts are uploaded.
The aggregate report contains metrics only. Host timings are not phone timings.
"""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import re
import subprocess
import tempfile
import time
import unicodedata
import wave

LANGUAGES = {"en", "ru", "de", "fr", "es", "it", "pt"}


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def words(text):
    return re.findall(r"[^\W_]+", unicodedata.normalize("NFC", text).casefold())


def distance(reference, hypothesis):
    row = list(range(len(hypothesis) + 1))
    for i, left in enumerate(reference, 1):
        next_row = [i]
        for j, right in enumerate(hypothesis, 1):
            next_row.append(min(next_row[-1] + 1, row[j] + 1, row[j - 1] + (left != right)))
        row = next_row
    return row[-1]


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("manifest", type=Path)
    parser.add_argument("--cli", type=Path, default=Path("build/whisper-host/bin/whisper-cli"))
    parser.add_argument("--model", type=Path, default=Path("app/src/main/assets/ggml-base-q5_1.bin"))
    parser.add_argument("--threads", type=int, default=4, choices=range(1, 5))
    parser.add_argument("--baseline", action="store_true", help="Previous greedy decoder, two threads, word alignment")
    parser.add_argument("--require-all-languages", action="store_true")
    parser.add_argument("--output", type=Path, default=Path("build/stt-metrics.json"))
    parser.add_argument("--clip-timeout", type=int, default=180, help="Maximum decoder seconds per clip")
    args = parser.parse_args()
    if args.clip_timeout <= 0:
        parser.error("Clip timeout must be positive")
    if not args.model.is_file() or not args.cli.is_file():
        parser.error("Supply an existing verified model and built whisper-cli")
    corpus = json.loads(args.manifest.read_text())
    if not corpus or any(item["language"] not in LANGUAGES or not words(item["text"]) for item in corpus):
        parser.error("Supply nonempty references with supported language codes")
    if args.require_all_languages and {item["language"] for item in corpus} != LANGUAGES:
        parser.error("Release evaluation requires all seven supported languages")
    totals = {}
    with tempfile.TemporaryDirectory(prefix="focal-eval-") as temporary:
        for index, item in enumerate(corpus):
            audio = (args.manifest.parent / item["audio"]).resolve()
            with wave.open(str(audio)) as wav:
                if (wav.getnchannels(), wav.getframerate(), wav.getsampwidth()) != (1, 16000, 2):
                    parser.error("Fixtures must be mono 16 kHz PCM16 WAV")
                seconds = wav.getnframes() / wav.getframerate()
            if not 0 < seconds <= 30:
                parser.error("Each fixture must contain between 0 and 30 seconds of audio")
            output = Path(temporary) / str(index)
            command = [str(args.cli.resolve()), "-m", str(args.model.resolve()), "-f", str(audio),
                       "-l", item["language"], "-t", str(2 if args.baseline else args.threads),
                       "-bs", str(1 if args.baseline else 3), "-bo", "1", "-tp", "0", "-tpi", "0",
                       "-sns", "-nt", "-otxt", "-of", str(output)]
            if args.baseline:
                command += ["-ml", "180", "-sow"]
            start = time.monotonic()
            result = subprocess.run(command, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=args.clip_timeout)
            elapsed = time.monotonic() - start
            if result.returncode:
                raise RuntimeError(f"Decoder failed on fixture {index + 1}; exit {result.returncode}")
            expected = words(item["text"])
            actual = words(output.with_suffix(".txt").read_text())
            reference_chars, actual_chars = "".join(expected), "".join(actual)
            count = totals.setdefault(item["language"], dict(clips=0, words=0, word_errors=0, characters=0, character_errors=0, audio_seconds=0, wall_seconds=0))
            for key, value in dict(clips=1, words=len(expected), word_errors=distance(expected, actual),
                                   characters=len(reference_chars), character_errors=distance(reference_chars, actual_chars),
                                   audio_seconds=seconds, wall_seconds=elapsed).items():
                count[key] += value
    for values in totals.values():
        values["wer"] = values["word_errors"] / values["words"]
        values["cer"] = values["character_errors"] / values["characters"]
        values["real_time_factor"] = values["wall_seconds"] / values["audio_seconds"]
    report = {"scope": "Host decoder only; includes model load; excludes capture, queue and thermal delays",
              "mode": "baseline" if args.baseline else "beam3",
              "threads": 2 if args.baseline else args.threads,
              "model_sha256": sha256(args.model), "model_bytes": args.model.stat().st_size,
              "decoder_sha256": sha256(args.cli),
              "host": {"system": platform.system(), "architecture": platform.machine()},
              "languages": totals}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
