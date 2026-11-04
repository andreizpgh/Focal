# Host Silero VAD probe

`scripts/vad-probe.cpp` calls the pinned engine's Silero 5.1.2 VAD API on one
bounded recording. This is an offline host experiment, not Android streaming
integration or a phone performance result. No ASR or downloads run in the probe.
Use only the checksum-verified model recorded by the experiment's provenance.

Build against the existing host engine:

```sh
bash scripts/build-vad-probe.sh
python3 -m unittest discover -s scripts/tests -p test_vad_probe.py
build/whisper-host/bin/vad-probe /private/model.bin /private/input.pcm \
  /private/new-result.json --samples 480000 --model-sha256 "$FOCAL_VAD_SHA256" \
  --threads 1 --threshold 0.5 --min-speech-ms 250 --min-silence-ms 600 \
  --max-speech-seconds 16 --pad-ms 500
```

Input is raw little-endian, mono PCM16 at 16 kHz, with **1–6,400,000 samples**
(at most 400 seconds). `--samples` is mandatory and must match the file exactly;
raw PCM has no header from which to detect an even-byte truncation. The probe
checks the model hash and fixed 16 kHz/512-sample-window header before loading,
then rechecks both input identities after inference. This is not a full validator
for arbitrary untrusted native model structures. Existing outputs, hard links and
symlinks are refused; newly created JSON has owner-only permissions. The POSIX
host implementation targets Linux/macOS.

JSON records all effective settings, model/PCM hashes, exact original and padded
sample counts, the per-window probabilities, native ranges, load/detect/segment
wall times and process peak RSS sampled after inference and input revalidation,
before JSON serialization. It contains no source paths or transcript. Keep
probabilities, PCM and raw engine diagnostics private alongside the experiment.
The experiment runner must separately retain executable/source and dynamic engine
library hashes; the probe's model digest does not identify its executable.

The native probability step is **512 samples / 32 ms**. The last window is padded
with zeroes by the engine. Native segment getters return **integer centiseconds**,
not seconds or milliseconds. Ranges are rounded to 10 ms by the native API; the
probe reports those native values and multiplies by 160 to obtain sample bounds,
clipping to the original PCM. These are not sample-accurate speech alignments.
Ranges wholly within final padding are counted separately and excluded.

The pinned native segment algorithm truncates a floating maximum-duration value
to whole seconds, so this CLI accepts integer seconds only. It can subsequently
merge adjacent segments beyond that maximum. An ASR runner must enforce its own
bounded chunk length and overlap policy after VAD. `samples_overlap` is fixed to
zero: it affects native audio-copy integration, which this probe does not use.

The pinned detector can log a compute error but return success with partial
probabilities. The probe captures native error logs and fails closed, also refusing
invalid probabilities, unexpected window counts and malformed ranges. It never
publishes such partial results. Caller-side timeouts remain necessary; this
single-process host probe does not implement cancellation or real-time capture.
