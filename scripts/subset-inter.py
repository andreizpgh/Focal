#!/usr/bin/env python3
"""Rebuild app/src/main/res/font/inter_var.ttf from the official Inter release.

The app bundles a subset of Inter's variable font (wght 100-900, opsz 14-32). This script
downloads the pinned release archive, checks its SHA-256, extracts InterVariable.ttf and
subsets it to the Unicode ranges the UI needs (Latin with extensions, combining marks,
Greek, Cyrillic, general punctuation, the euro and numero signs, arrows and the minus
sign), keeping the variation tables and the layout features the typography uses
(tnum, case, calt, locl, plus ccmp, kern, mark and mkmk for correct shaping). Glyphs
outside the subset fall back to the system sans.

Requires fontTools (pip install fonttools). The result is compared with the pinned
digest; a different fontTools version may produce a different (equally valid) file, in
which case the script reports the new digest instead of writing silently.

  python3 scripts/subset-inter.py            # download, verify, subset, compare
  python3 scripts/subset-inter.py --zip ZIP  # use a local copy of the release archive
"""
import argparse
import hashlib
import io
from pathlib import Path
import sys
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]
RELEASE = "https://github.com/rsms/inter/releases/download/v4.1/Inter-4.1.zip"
RELEASE_SHA256 = "9883fdd4a49d4fb66bd8177ba6625ef9a64aa45899767dde3d36aa425756b11e"
SOURCE_SHA256 = "4989b125924991b90d05b2d16e0e388c48f7d5bb8b30539bbf9c755278d0ccaf"  # InterVariable.ttf
OUTPUT = ROOT / "app/src/main/res/font/inter_var.ttf"
OUTPUT_SHA256 = "3b404150557f11c9347284044b76da00ff6e319031123a0daa671039348c0621"  # fontTools 4.62.1
UNICODES = "U+0000-024F,U+0300-036F,U+0370-03FF,U+0400-04FF,U+2000-206F,U+20AC,U+2116,U+2190-2193,U+2212"


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--zip", type=Path, help="a local copy of Inter-4.1.zip")
    args = parser.parse_args()
    try:
        from fontTools import subset
    except ImportError:
        print("fontTools is required: pip install fonttools", file=sys.stderr)
        return 2

    archive = args.zip.read_bytes() if args.zip else urllib.request.urlopen(RELEASE, timeout=120).read()
    if sha256(archive) != RELEASE_SHA256:
        print(f"Release archive digest mismatch: {sha256(archive)}", file=sys.stderr)
        return 1
    source = zipfile.ZipFile(io.BytesIO(archive)).read("InterVariable.ttf")
    if sha256(source) != SOURCE_SHA256:
        print(f"InterVariable.ttf digest mismatch: {sha256(source)}", file=sys.stderr)
        return 1

    with tempfile.TemporaryDirectory() as work:
        font_in, font_out = Path(work, "InterVariable.ttf"), Path(work, "inter_var.ttf")
        font_in.write_bytes(source)
        subset.main([
            str(font_in), f"--output-file={font_out}", f"--unicodes={UNICODES}",
            "--layout-features+=tnum,case", "--layout-features-=dnom,frac,numr",
            "--name-IDs=*", "--name-languages=*", "--notdef-outline", "--no-hinting",
        ])
        result = font_out.read_bytes()
    digest = sha256(result)
    if digest != OUTPUT_SHA256:
        print(f"The subset differs from the pinned one ({digest}); check the fontTools version "
              f"and update OUTPUT_SHA256 if the new file is intended.", file=sys.stderr)
        return 1
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    OUTPUT.write_bytes(result)
    print(f"{OUTPUT.relative_to(ROOT)}: {len(result)} bytes, sha256 {digest}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
