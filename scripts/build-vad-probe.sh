#!/usr/bin/env bash
# Compile the host-only VAD probe against the already-built pinned engine.
set -euo pipefail
cd "$(dirname "$0")/.."
probe_build_dir="$(cd "${1:-build/whisper-host}" && pwd)"
test -f "$probe_build_dir/src/libwhisper.so" || {
  echo 'Build the pinned host whisper library before building the VAD probe.' >&2
  exit 1
}
mkdir -p "$probe_build_dir/bin"
probe_compiler="${CXX:-c++}"
"$probe_compiler" -std=c++17 -O2 -Wall -Wextra -Werror scripts/vad-probe.cpp \
  -I third_party/whisper.cpp/include -I third_party/whisper.cpp/ggml/include \
  -L "$probe_build_dir/src" -lwhisper -lcrypto \
  -Wl,-rpath,"$probe_build_dir/src" -Wl,-rpath,"$probe_build_dir/ggml/src" \
  -o "$probe_build_dir/bin/vad-probe"
echo "$probe_build_dir/bin/vad-probe"
