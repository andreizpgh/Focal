#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
cmake -S third_party/whisper.cpp -B build/whisper-host \
  -DCMAKE_BUILD_TYPE=Release -DGGML_NATIVE=OFF -DGGML_OPENMP=OFF \
  -DWHISPER_BUILD_TESTS=OFF -DWHISPER_BUILD_SERVER=OFF -DWHISPER_CURL=OFF
cmake --build build/whisper-host --target whisper-cli --parallel 2
build/whisper-host/bin/whisper-cli \
  -m app/src/main/assets/ggml-base-q5_1.bin \
  -f third_party/whisper.cpp/samples/jfk.wav -l en -t 2 -nt -otxt -of build/jfk
python3 - <<'PY'
from pathlib import Path
text = Path('build/jfk.txt').read_text().lower()
assert 'ask not' in text and 'country' in text, f'Unexpected transcription: {text!r}'
print('Real-model English speech smoke test passed.')
PY
