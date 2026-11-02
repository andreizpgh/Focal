# Prepare continuous speech-gate evidence

`scripts/prepare-gate-experiment.py` selects the first three development clips per
language from an existing hashed manifest, verifies the source PCM16 WAVs, and
streams one continuous recording per language/scenario. It downloads nothing and
does no inference. Held-out clips cannot fill a missing development selection.

Provide the cached project Kotlin **2.1.20** compiler classpath and matching
standard-library jar. E4 evaluation requires that compiler version.
The script compiles the current production gate, transcript logic and both replay
entrypoints into fresh private classes before running; a prebuilt jar is not
accepted as evidence of current source. Outside a recognition timing window, run:

```sh
printf '%s' "$FOCAL_KOTLIN_COMPILER_CP" > /private/kotlin-compiler-classpath.txt
python3 scripts/prepare-gate-experiment.py \
  --manifest build/stt/corpus/manifest.json \
  --output build/stt/gate-experiment-verified \
  --compiler-classpath-file /private/kotlin-compiler-classpath.txt \
  --kotlin-stdlib "$FOCAL_KOTLIN_STDLIB"
python3 -m unittest discover -s scripts/tests -p test_prepare_gate_experiment.py
```

The compiler classpath file contains cached compiler dependency jars separated
by the platform classpath separator; a final `*` expands sorted jar files. The
stdlib is a separate cached jar. Choose a new output directory, or an empty
directory already restricted to the current user. Outputs under the repository
must be Git-ignored. Any containing Git worktree must ignore the output directory
itself, not just individual output filenames. Source WAV containers are limited
to 4 MiB. The script uses owner-only directory permissions on Unix;
check the parent ACLs on Windows. An explicit `--java` path can select the desired
cached runtime. No source references are copied into derived metadata or printed.

The entire manifest is checked before selection: byte-identical PCM and declared
speaker/session groups may not cross dev/test splits. Different speakers are not
guaranteed when group identifiers are absent. Source audio is checked again after
each stream is written; do not modify the corpus during preparation.

Each language has two seconds of inserted leading and trailing silence and 1.2
seconds between clips. Scenarios preserve PCM amplitude or apply gains of 0.1 and
0.03, using float64 multiplication and nearest-integer, ties-to-even rounding.
The script records exact original clip sample intervals and hashes every derived
PCM and actual-Kotlin range TSV. The experiment manifest records the original
manifest/provenance hashes, selected source audio hashes, the gate/replay source
hashes, compiler dependency and runtime-jar hashes, and every fresh compiled
class hash and compiler version. Use a trusted cached Java/Kotlin toolchain;
recorded hashes identify its inputs but do not establish compiler correctness.
Host replay is not proof of Android ABI or runtime equivalence. Compiler, source
and runtime inputs must remain stable throughout the experiment. Older manually compiled reports without `fresh_replay_build` are
historical and unattested; do not use them for new E4 inference.

Three separate thirty-second controls contain zeros, a constant microphone DC
offset, or seeded uniform low-level integer noise. The last is synthetic noise;
it does not establish behavior in a real room. Candidate work on these controls
is measured, without inferring hallucinations from gate activity alone.

Report **source-clip coverage, not phoneme-aligned spoken coverage**. Original
clips retain their own leading, trailing and internal silence. Coverage merges
overlapping gate ranges before counting source samples; decoder work separately
counts the real overlap and inserted-silence portions of each range. An uncovered
source interval is evidence for listening/review, not a counted word omission.

The output contains private/local PCM and source identifiers. Keep all generated
files ignored, including the experiment manifest and TSVs. Selected aggregate
results may be reported after reviewing these limits. Recognition quality,
boundary omissions/repetition and hallucinations require decoding the same
streams, applying the actual [Transcript replay](transcript-replay.md) operations,
and comparing against references separately.
