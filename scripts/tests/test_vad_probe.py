"""Exercise the host VAD contract with a synthetic backend, never model inference."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[2]
STUB = r'''
#include <whisper.h>
#include <cassert>
#include <cmath>
#include <cstdlib>
#include <iostream>
#include <string>
#include <vector>
struct whisper_vad_context { std::vector<float> probabilities; };
struct whisper_vad_segments { std::vector<std::pair<float, float>> ranges; };
static ggml_log_callback callback = nullptr;
static void *callbackData = nullptr;
static std::string mode() { const char *p = std::getenv("VAD_STUB_MODE"); return p ? p : ""; }
void whisper_log_set(ggml_log_callback cb, void *data) { callback = cb; callbackData = data; }
whisper_vad_context_params whisper_vad_default_context_params() { return {}; }
whisper_vad_params whisper_vad_default_params() { return {}; }
whisper_vad_context *whisper_vad_init_from_file_with_params(const char *, whisper_vad_context_params p) {
    assert(!p.use_gpu && p.n_threads == (mode() == "custom" ? 4 : 1));
    std::cerr << "synthetic VAD model open\n";
    return mode() == "load-failure" ? nullptr : new whisper_vad_context;
}
void whisper_vad_free(whisper_vad_context *p) { delete p; }
bool whisper_vad_detect_speech(whisper_vad_context *p, const float *samples, int count) {
    assert(count == 1200 && samples[0] == -1.f && samples[1] == -1.f/32768 && samples[2] == 0.f && samples[3] == 32767.f/32768);
    assert(samples[4] == 0 && samples[count-1] == 0);
    p->probabilities = {.1f, .8f, .2f};
    if (mode() == "wrong-count") p->probabilities.pop_back();
    if (mode() == "invalid-probability") p->probabilities[0] = NAN;
    if (mode() == "compute-error") callback(GGML_LOG_LEVEL_ERROR, "synthetic compute error\n", callbackData);
    return mode() != "detect-failure";
}
int whisper_vad_n_probs(whisper_vad_context *p) { return p->probabilities.size(); }
float *whisper_vad_probs(whisper_vad_context *p) { return p->probabilities.data(); }
whisper_vad_segments *whisper_vad_segments_from_probs(whisper_vad_context *, whisper_vad_params p) {
    if (mode() == "custom") assert(p.threshold == .75f && p.min_speech_duration_ms == 100 && p.min_silence_duration_ms == 400 && p.max_speech_duration_s == 15 && p.speech_pad_ms == 250);
    else assert(p.threshold == .5f && p.min_speech_duration_ms == 250 && p.min_silence_duration_ms == 600 && p.max_speech_duration_s == 16 && p.speech_pad_ms == 500);
    assert(p.samples_overlap == 0);
    if (mode() == "segment-failure") return nullptr;
    auto *s = new whisper_vad_segments;
    if (mode() != "silence") s->ranges.push_back({1, 10});
    if (mode() == "fractional-range") s->ranges[0] = {1.5f, 10};
    if (mode() == "out-of-bounds-range") s->ranges[0] = {1, 11};
    if (mode() == "overlapping-ranges") s->ranges = {{0, 5}, {4, 10}};
    if (mode() == "padding-only-range") s->ranges = {{8, 10}};
    return s;
}
int whisper_vad_segments_n_segments(whisper_vad_segments *s) { return s->ranges.size(); }
float whisper_vad_segments_get_segment_t0(whisper_vad_segments *s, int i) { return s->ranges[i].first; }
float whisper_vad_segments_get_segment_t1(whisper_vad_segments *s, int i) { return s->ranges[i].second; }
void whisper_vad_free_segments(whisper_vad_segments *s) { delete s; }
'''


@unittest.skipUnless(shutil.which("c++"), "A host C++ compiler and OpenSSL headers are required")
class VadProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        stub = cls.directory / "vad-stub.cpp"
        stub.write_text(STUB)
        cls.probe = cls.directory / "vad-probe"
        result = subprocess.run(["c++", "-std=c++17", "-O0", "-Wall", "-Wextra", "-Werror",
                                 str(ROOT / "scripts/vad-probe.cpp"), str(stub), "-I",
                                 str(ROOT / "third_party/whisper.cpp/include"), "-I",
                                 str(ROOT / "third_party/whisper.cpp/ggml/include"), "-lcrypto", "-o", str(cls.probe)],
                                capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise AssertionError(result.stderr)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=self.directory)
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name)
        self.model = self.path / "model.bin"
        self.model.write_bytes(struct.pack("<II", 0x67676d6c, 10) + b"silero-16k" +
                               struct.pack("<IIIII", 5, 1, 2, 512, 64) + bytes(26))
        self.audio = self.path / "input.pcm"
        self.audio.write_bytes(struct.pack("<hhhh", -32768, -1, 0, 32767) + bytes(2392))
        self.output = self.path / "result.json"
        self.model_hash = hashlib.sha256(self.model.read_bytes()).hexdigest()

    def run_probe(self, *extra, mode="", samples="1200", model_hash=None, required=True):
        args = [str(self.probe), str(self.model), str(self.audio), str(self.output)]
        if required:
            args += ["--samples", samples, "--model-sha256", model_hash or self.model_hash]
        return subprocess.run(args + list(extra), env={**os.environ, "VAD_STUB_MODE": mode},
                              capture_output=True, text=True, timeout=10)

    def assert_failed_before_inference(self, result):
        self.assertEqual(result.returncode, 1, result.stderr)
        self.assertNotIn("synthetic VAD model open", result.stderr)
        self.assertFalse(self.output.exists())

    def test_pcm_conversion_probability_windows_settings_and_centisecond_ranges(self):
        result = self.run_probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        data = json.loads(self.output.read_text())
        self.assertEqual(data["model_sha256"], self.model_hash)
        self.assertEqual(data["pcm_sha256"], hashlib.sha256(self.audio.read_bytes()).hexdigest())
        self.assertEqual((data["sample_rate"], data["input_samples"], data["window_samples"],
                          data["probability_windows"], data["padded_samples"]), (16000, 1200, 512, 3, 1536))
        self.assertEqual(data["ranges"], [dict(native_start_cs=1, native_end_cs=10, start_sample=160, end_sample=1200)])
        self.assertEqual(data["padding_only_ranges_discarded"], 0)
        self.assertEqual(len(data["probabilities"]), 3)
        self.assertAlmostEqual(data["probabilities"][1], .8)
        self.assertEqual(data["settings"]["use_gpu"], False)
        self.assertEqual(data["settings"]["samples_overlap_seconds"], 0)
        for field in ("load_seconds", "detect_seconds", "segment_seconds"):
            self.assertGreaterEqual(data[field], 0)
        self.assertGreater(data["peak_rss_bytes"], 0)
        self.assertEqual(self.output.stat().st_mode & 0o777, 0o600)
        self.assertNotIn(str(self.path), self.output.read_text())

    def test_explicit_settings_reach_backend_without_aliases(self):
        result = self.run_probe("--threads", "4", "--threshold", ".75", "--min-speech-ms", "100",
                                "--min-silence-ms", "400", "--max-speech-seconds", "15", "--pad-ms", "250", mode="custom")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(self.output.read_text())["settings"], dict(use_gpu=False, threads=4,
                         threshold=.75, min_speech_ms=100, min_silence_ms=400, max_speech_seconds=15,
                         pad_ms=250, samples_overlap_seconds=0))

    def test_invalid_or_missing_parameters_fail_before_model_open(self):
        for args in (("--threads", "0"), ("--threads", "5"), ("--threads", "1.5"), ("--threshold", "nan"),
                     ("--threshold", "inf"), ("--threshold", "1"), ("--threshold", "0"),
                     ("--threshold", "0.5junk"), ("--min-speech-ms", "0"), ("--min-silence-ms", "5001"),
                     ("--max-speech-seconds", "15.5"), ("--max-speech-seconds", "1"), ("--pad-ms", "2001"),
                     ("--vsd", "200"), ("--threads", "1", "--threads", "1"), ("--threshold",)):
            with self.subTest(args=args):
                self.assert_failed_before_inference(self.run_probe(*args))
        self.assert_failed_before_inference(self.run_probe(required=False))
        for count in ("0", "6400001", "-1", "99999999999999999999999"):
            self.assert_failed_before_inference(self.run_probe(samples=count))

    def test_truncated_odd_empty_oversized_and_missing_pcm_fail_before_model_open(self):
        original = self.audio.read_bytes()
        for changed in (original[:-1], original[:-2], b"", original + b"\0\0"):
            with self.subTest(size=len(changed)):
                self.audio.write_bytes(changed)
                self.assert_failed_before_inference(self.run_probe())
        self.audio.unlink()
        self.assert_failed_before_inference(self.run_probe())

    def test_model_identity_and_window_header_fail_closed(self):
        self.assert_failed_before_inference(self.run_probe(model_hash="f" * 64))
        data = bytearray(self.model.read_bytes())
        struct.pack_into("<I", data, 30, 256)
        self.model.write_bytes(data)
        self.assert_failed_before_inference(self.run_probe(model_hash=hashlib.sha256(data).hexdigest()))

    def test_existing_output_and_input_aliases_are_preserved(self):
        for source in (self.model, self.audio):
            with self.subTest(source=source.name):
                before = source.read_bytes()
                os.link(source, self.output)
                result = self.run_probe()
                self.assertEqual(result.returncode, 1)
                self.assertEqual(source.read_bytes(), before)
                self.assertNotIn("synthetic VAD model open", result.stderr)
                self.output.unlink()
        for target in (self.audio, self.path / "missing.pcm"):
            self.output.symlink_to(target)
            self.assertEqual(self.run_probe().returncode, 1)
            self.assertTrue(self.output.is_symlink())
            self.output.unlink()
        self.output.write_text("keep previous evidence")
        self.assertEqual(self.run_probe().returncode, 1)
        self.assertEqual(self.output.read_text(), "keep previous evidence")

    def test_backend_failures_invalid_probabilities_and_ranges_leave_no_result(self):
        for mode in ("load-failure", "detect-failure", "compute-error", "wrong-count", "invalid-probability",
                     "segment-failure", "fractional-range", "out-of-bounds-range", "overlapping-ranges"):
            with self.subTest(mode=mode):
                result = self.run_probe(mode=mode)
                self.assertEqual(result.returncode, 1, result.stderr)
                self.assertFalse(self.output.exists())

    def test_silence_and_padding_only_ranges_emit_no_original_audio_range(self):
        for mode, discarded in (("silence", 0), ("padding-only-range", 1)):
            with self.subTest(mode=mode):
                result = self.run_probe(mode=mode)
                self.assertEqual(result.returncode, 0, result.stderr)
                data = json.loads(self.output.read_text())
                self.assertEqual(data["ranges"], [])
                self.assertEqual(data["padding_only_ranges_discarded"], discarded)
                self.output.unlink()


if __name__ == "__main__":
    unittest.main()
