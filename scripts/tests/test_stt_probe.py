"""Compile a synthetic Whisper backend; no model loading or speech inference."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import struct
import subprocess
import tempfile
import unittest
import wave

ROOT = Path(__file__).resolve().parents[2]
STUB = r'''
#include <whisper.h>
#include <cassert>
#include <cmath>
#include <cstring>
#include <iostream>
struct whisper_context {};
static whisper_context context;
whisper_context_params whisper_context_default_params() { return {}; }
whisper_full_params whisper_full_default_params(whisper_sampling_strategy strategy) { whisper_full_params p{}; p.strategy = strategy; return p; }
whisper_context *whisper_init_from_file_with_params(const char *, whisper_context_params p) {
    assert(!p.use_gpu && p.flash_attn); std::cerr << "synthetic model open\n"; return &context;
}
void whisper_free(whisper_context *) {}
int whisper_is_multilingual(whisper_context *) { return 1; }
int whisper_model_n_audio_ctx(whisper_context *) { return 1500; }
int whisper_full(whisper_context *, whisper_full_params p, const float *audio, int count) {
    assert(count == 16000 && audio[0] == -1.f && audio[1] == -1.f/32768 && audio[2] == 0.f && audio[3] == 32767.f/32768);
    assert(audio[4] == 0 && audio[count-1] == 0 && p.strategy == WHISPER_SAMPLING_BEAM_SEARCH && p.n_threads == 4);
    assert(p.no_context && !p.no_timestamps && !p.token_timestamps && !p.translate && !p.detect_language);
    assert(p.n_max_text_ctx == 64 && p.suppress_blank && p.suppress_nst && p.temperature == 0 && p.temperature_inc == 0);
    assert(p.greedy.best_of == 1 && p.beam_search.beam_size == 3 && p.audio_ctx == 768 && std::strcmp(p.language,"ru") == 0);
    assert(!p.print_progress && !p.print_realtime && !p.print_timestamps && !p.print_special);
    return 0;
}
int whisper_full_n_segments(whisper_context *) { return 1; }
int64_t whisper_full_get_segment_t0(whisper_context *, int) { return 0; }
int64_t whisper_full_get_segment_t1(whisper_context *, int) { return 50; }
const char *whisper_full_get_segment_text(whisper_context *, int) { return " Привет \"мир\" \\\n\t\001🙂 "; }
float whisper_full_get_segment_no_speech_prob(whisper_context *, int) { return NAN; }
int whisper_full_n_tokens(whisper_context *, int) { return 3; }
whisper_token whisper_token_eot(whisper_context *) { return 100; }
whisper_token_data whisper_full_get_token_data(whisper_context *, int, int i) {
    whisper_token_data token{}; token.id = i == 2 ? 100 : i; token.p = i == 1 ? NAN : .9f; return token;
}
'''


@unittest.skipUnless(shutil.which("c++"), "A host C++ compiler and OpenSSL development headers are required")
class ProbeTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.temporary = tempfile.TemporaryDirectory()
        cls.addClassCleanup(cls.temporary.cleanup)
        cls.directory = Path(cls.temporary.name)
        stub = cls.directory / "whisper-stub.cpp"
        stub.write_text(STUB)
        cls.probe = cls.directory / "stt-probe"
        result = subprocess.run(["c++", "-std=c++17", "-O0", "-Wall", "-Wextra", "-Werror", str(ROOT / "scripts/stt-probe.cpp"),
                                 str(stub), "-I", str(ROOT / "third_party/whisper.cpp/include"), "-I", str(ROOT / "third_party/whisper.cpp/ggml/include"),
                                 "-lcrypto", "-o", str(cls.probe)], capture_output=True, text=True, timeout=60)
        if result.returncode:
            raise AssertionError(result.stderr)

    def setUp(self):
        temporary = tempfile.TemporaryDirectory(dir=self.directory)
        self.addCleanup(temporary.cleanup)
        self.path = Path(temporary.name)
        self.audio = self.path / "input.wav"
        with wave.open(str(self.audio), "wb") as wav:
            wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
            wav.writeframes(struct.pack("<hhhh", -32768, -1, 0, 32767) + bytes(632))
        self.model = self.path / "synthetic.bin"
        self.model.write_bytes(b"synthetic marker, never read by the fake backend")
        self.output = self.path / "result"

    def run_probe(self, *extra):
        return subprocess.run([str(self.probe), "-m", str(self.model), "-f", str(self.audio), "-l", "ru", "-of", str(self.output),
                               "-t", "4", "-bs", "3", "-bo", "1", "-tp", "0", "-tpi", "0", "-mc", "64", "-ng", "-fa", "-sns", "-oj", "-ac", "768", *extra],
                              capture_output=True, text=True, timeout=10)

    def test_jni_params_short_padding_timestamps_json_and_confidence(self):
        result = self.run_probe()
        self.assertEqual(result.returncode, 0, result.stderr)
        document = json.loads(self.output.with_suffix(".json").read_text())
        segment, profile = document["transcription"][0], document["probe"]
        self.assertEqual(segment["offsets"], {"from": 0, "to": 500})
        self.assertEqual(segment["text"], ' Привет "мир" \\\n\t\x01🙂 ')
        self.assertEqual(segment["probabilities"], [.9, 0])
        self.assertEqual(segment["noSpeech"], 1)
        self.assertEqual(profile["original_audio_frames"], 320)
        self.assertEqual(profile["decode_audio_frames"], 16000)
        self.assertEqual(profile["initial_prompt_sha256"], hashlib.sha256(b"").hexdigest())
        self.assertGreaterEqual(profile["load_seconds"], 0)
        self.assertGreaterEqual(profile["decode_seconds"], 0)
        self.assertGreater(profile["peak_rss_bytes"], 0)

    def test_malformed_riff_padding_and_partial_header_fail_before_model_open(self):
        original = self.audio.read_bytes()
        for trailing in (b"JUNK\x01\0\0\0x", b"junk"):
            with self.subTest(trailing=trailing):
                body = original + trailing
                self.audio.write_bytes(body[:4] + struct.pack("<I", len(body) - 8) + body[8:])
                result = self.run_probe()
                self.assertEqual(result.returncode, 1)
                self.assertNotIn("synthetic model open", result.stderr)

    def test_hardlinked_output_cannot_truncate_any_input(self):
        prompt = self.path / "context.txt"
        prompt.write_text("prior ASR context")
        target = self.output.with_suffix(".json")
        for source in (self.audio, self.model, prompt):
            with self.subTest(source=source.name):
                before = source.read_bytes()
                os.link(source, target)
                result = self.run_probe("--prompt-file", str(prompt))
                self.assertEqual(result.returncode, 1)
                self.assertEqual(source.read_bytes(), before)
                target.unlink()

    def test_prompt_utf8_limits_and_hash_preserve_privacy(self):
        prompt = self.path / "context.txt"
        accepted = "я" * 238 + "🙂"
        prompt.write_text(accepted)
        result = self.run_probe("--prompt-file", str(prompt))
        self.assertEqual(result.returncode, 0, result.stderr)
        raw = self.output.with_suffix(".json").read_text()
        profile = json.loads(raw)["probe"]
        self.assertEqual(profile["initial_prompt_utf16_units"], 240)
        self.assertEqual(profile["initial_prompt_sha256"], hashlib.sha256(accepted.encode()).hexdigest())
        self.assertNotIn(accepted, raw)
        for invalid in (accepted.encode() + b"x", b"x\0y", b"\xc0\x80", b"\xed\xa0\x80", b"\xf4\x90\x80\x80", b"\xe2", b"x" * 961):
            with self.subTest(invalid=invalid[:10]):
                prompt.write_bytes(invalid)
                result = self.run_probe("--prompt-file", str(prompt))
                self.assertEqual(result.returncode, 1)
                self.assertNotIn("synthetic model open", result.stderr)

    def test_historical_alignment_and_changed_fixed_params_are_rejected(self):
        for arguments in (("-ml", "180"), ("-nt",), ("-tpi", ".2"), ("-mc", "128")):
            with self.subTest(arguments=arguments):
                result = self.run_probe(*arguments)
                self.assertEqual(result.returncode, 1)
                self.assertNotIn("synthetic model open", result.stderr)


if __name__ == "__main__":
    unittest.main()
