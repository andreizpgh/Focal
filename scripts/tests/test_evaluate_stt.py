"""Synthetic metric and fake-process regressions; these do not measure ASR quality."""
import argparse
import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import struct
import sys
import tempfile
import unittest
from unittest import mock
import wave

SCRIPT = Path(__file__).resolve().parents[1] / "evaluate-stt.py"
SPEC = importlib.util.spec_from_file_location("evaluate_stt", SCRIPT)
EVALUATE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(EVALUATE)


def fixture(**fields):
    return dict(audio="example.wav", language="ru", text="Привет, мир!", **fields)


class MetricsTest(unittest.TestCase):
    def test_literal_polish_is_not_lexical_improvement(self):
        metrics = EVALUATE.score("Привет, мир!", "привет мир", False, 2, 1)
        self.assertEqual(metrics["word_errors"], 0)
        self.assertEqual(metrics["character_errors"], 0)
        self.assertEqual(metrics["normalized_character_errors"], 0)
        self.assertGreater(metrics["literal_character_errors"], 0)

    def test_space_boundary_has_its_own_character_metric(self):
        metrics = EVALUATE.score("a b", "ab", False, 2, 1)
        self.assertEqual(metrics["character_errors"], 0)
        self.assertEqual(metrics["normalized_character_errors"], 1)
        self.assertEqual(metrics["word_errors"], 2)

    def test_micro_rates_weight_reference_counts_and_include_silence_insertions(self):
        records = [{"metrics": EVALUATE.score("one two three", "one two", False, 3, 1)},
                   {"metrics": EVALUATE.score("four", "four", False, 1, 3)},
                   {"metrics": EVALUATE.score("", "invented", True, 60, 8)}]
        total = EVALUATE.aggregate(records)
        self.assertEqual(total["words"], 4)
        self.assertEqual(total["word_errors"], 2)
        self.assertEqual(total["wer"], 0.5)
        self.assertEqual(total["silence_inserted_words_per_minute"], 1)
        self.assertEqual(total["wall_seconds_p50"], 3)
        self.assertAlmostEqual(total["wall_seconds_p95"], 7.5)

    def test_silence_only_denominators_remain_undefined(self):
        total = EVALUATE.aggregate([{"metrics": EVALUATE.score("", "noise words", True, 30, 1)}])
        for key in ("wer", "cer", "lexical_cer", "normalized_cer", "literal_cer"):
            self.assertIsNone(total[key])
        self.assertEqual(total["silence_inserted_words_per_minute"], 4)
        json.dumps(total, allow_nan=False)

    def probe_document(self):
        return dict(model=dict(multilingual=True), probe=dict(schema=1, load_seconds=1, decode_seconds=2, peak_rss_bytes=4096),
                    transcription=[dict(offsets={"from": 0, "to": 300}, text=" real ", probabilities=[.9, .4], noSpeech=.1),
                                   dict(offsets={"from": 200, "to": 1000}, text=" padding ", probabilities=[], noSpeech=.9)])

    def test_private_probe_evidence_retains_confidence_without_changing_scoring(self):
        document = self.probe_document()
        before = EVALUATE.decoded_text(document, .2)
        diagnostics = EVALUATE.probe_diagnostics(document, .2)
        self.assertEqual(EVALUATE.decoded_text(document, .2), before)
        self.assertEqual(before, ("real", 1))
        self.assertEqual(diagnostics["segments"][0]["probabilities"], [.9, .4])
        self.assertEqual(diagnostics["segments"][0]["offsets"], {"from": 0, "to": 300})
        self.assertTrue(diagnostics["segments"][0]["in_original_audio"])
        self.assertFalse(diagnostics["segments"][1]["in_original_audio"])
        self.assertIsNone(EVALUATE.probe_diagnostics({"transcription": []}, .2))

    def test_invalid_probe_phase_or_confidence_values_are_rejected(self):
        for change in (lambda d: d["probe"].update(load_seconds=float("nan")),
                       lambda d: d["probe"].update(peak_rss_bytes=True),
                       lambda d: d["transcription"][0].update(probabilities=[float("nan")]),
                       lambda d: d["transcription"][0].update(noSpeech=-1)):
            document = copy.deepcopy(self.probe_document())
            change(document)
            with self.assertRaises(ValueError):
                EVALUATE.probe_diagnostics(document, .2)

    def test_internal_phase_measurements_do_not_replace_process_wall_cost(self):
        records = [dict(metrics=EVALUATE.score("one", "one", False, 2, 17), probe=dict(load_seconds=1, decode_seconds=4, peak_rss_bytes=1000)),
                   dict(metrics=EVALUATE.score("two", "two", False, 2, 23), probe=dict(load_seconds=3, decode_seconds=8, peak_rss_bytes=2000))]
        total = EVALUATE.aggregate(records)
        phases = EVALUATE.aggregate_probe(records)
        self.assertEqual(total["wall_seconds"], 40)
        self.assertEqual(total["wall_seconds_p50"], 20)
        self.assertEqual(phases["load_seconds"]["p50"], 2)
        self.assertEqual(phases["decode_seconds"]["total"], 12)
        self.assertEqual(phases["peak_rss_bytes"]["maximum"], 2000)


class ManifestTest(unittest.TestCase):
    def test_split_filter_preserves_manifest_identity(self):
        selected = EVALUATE.validate_manifest([fixture(id="held", split="test"), fixture(id="tune", split="dev")], "dev")
        self.assertEqual([item["id"] for item in selected], ["tune"])

    def test_group_leakage_is_rejected_even_when_other_split_is_filtered(self):
        with self.assertRaisesRegex(ValueError, "both dev and test"):
            EVALUATE.validate_manifest([fixture(id="one", split="dev", group="speaker-1"),
                                        fixture(id="two", split="test", group="speaker-1")], "dev")

    def test_missing_groups_are_not_fabricated_and_missing_splits_fail_filtering(self):
        self.assertNotIn("group", EVALUATE.validate_manifest([fixture()])[0])
        with self.assertRaisesRegex(ValueError, "explicit split"):
            EVALUATE.validate_manifest([fixture()], "test")

    def test_blank_reference_requires_explicit_silence_and_unique_ids(self):
        with self.assertRaisesRegex(ValueError, "silence:true"):
            EVALUATE.validate_manifest([dict(audio="silence.wav", language="en", text="")])
        self.assertEqual(len(EVALUATE.validate_manifest([dict(audio="silence.wav", language="en", text="", silence=True)])), 1)
        with self.assertRaisesRegex(ValueError, "unique"):
            EVALUATE.validate_manifest([fixture(id="same"), fixture(id="same")])

    def test_records_in_tracked_tree_are_refused_and_ignored_build_is_allowed(self):
        with self.assertRaisesRegex(ValueError, "Git-ignored"):
            EVALUATE.private_records_path(EVALUATE.REPOSITORY / "docs/private-hypotheses.jsonl")
        path = EVALUATE.REPOSITORY / "build/private-hypotheses.jsonl"
        self.assertEqual(EVALUATE.private_records_path(path), path)

    def test_expected_audio_hash_is_checked_before_inference(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            path = directory / "example.wav"
            with wave.open(str(path), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(bytes(32000))
            corpus = EVALUATE.validate_manifest([fixture(audio_sha256=EVALUATE.sha256(path).upper())])
            self.assertEqual(EVALUATE.verify_audio_manifest(corpus, directory)["fixture-1"]["audio_sha256"], EVALUATE.sha256(path))
            corpus[0]["audio_sha256"] = "0" * 64
            with self.assertRaisesRegex(ValueError, "does not match"):
                EVALUATE.verify_audio_manifest(corpus, directory)

    def test_reheadered_duplicate_audio_cannot_cross_splits(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            original, reheadered = directory / "example.wav", directory / "other.wav"
            with wave.open(str(original), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(bytes(32000))
            source = original.read_bytes()
            # Different container metadata must not disguise the same PCM.
            reheadered.write_bytes(source[:4] + struct.pack("<I", len(source) + 4) + source[8:] + b"JUNK\x04\x00\x00\x00data")
            self.assertNotEqual(EVALUATE.sha256(original), EVALUATE.sha256(reheadered))
            corpus = EVALUATE.validate_manifest([fixture(id="development", split="dev"),
                                                dict(id="held", audio="other.wav", language="en", text="Other reference", split="test")])
            with self.assertRaisesRegex(ValueError, "both dev and test"):
                EVALUATE.verify_audio_manifest(corpus, directory)

    def test_repeated_same_language_audio_is_rejected_but_silence_can_cover_languages(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            path = directory / "example.wav"
            with wave.open(str(path), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(bytes(32000))
            corpus = EVALUATE.validate_manifest([fixture(id="one", split="dev"), fixture(id="two", split="dev")])
            with self.assertRaisesRegex(ValueError, "same language and split"):
                EVALUATE.verify_audio_manifest(corpus, directory)
            silence = [dict(id=language, audio="example.wav", language=language, text="", silence=True, split="dev")
                       for language in ("en", "ru")]
            self.assertEqual(len(EVALUATE.verify_audio_manifest(EVALUATE.validate_manifest(silence), directory)), 2)

    def test_outputs_cannot_overwrite_audio_in_a_filtered_out_split(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            for name, sample in (("dev.wav", b"\x00\x00"), ("held.wav", b"\x01\x00")):
                with wave.open(str(directory / name), "wb") as wav:
                    wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                    wav.writeframes(sample * 16000)
            held_out = directory / "held.wav"
            original_hash = EVALUATE.sha256(held_out)
            manifest = directory / "manifest.json"
            manifest.write_text(json.dumps([dict(id="tune", audio="dev.wav", language="en", text="Dev reference", split="dev"),
                                            dict(id="held", audio="held.wav", language="en", text="Test reference", split="test")]))
            # This deliberately nonexecutable decoder proves the guard runs before inference.
            cli, model = directory / "cli", directory / "model.bin"
            cli.write_bytes(b"not an executable")
            model.write_bytes(b"not weights")
            for option in ("--output", "--records-output"):
                with self.subTest(option=option):
                    command = [sys.executable, str(SCRIPT), str(manifest), "--cli", str(cli), "--model", str(model),
                               "--split", "dev", "--output", str(directory / "metrics.json"), option, str(held_out)]
                    result = subprocess.run(command, capture_output=True, text=True, timeout=10)
                    self.assertEqual(result.returncode, 2, result.stderr)
                    self.assertIn("must not overwrite inputs", result.stderr)
                    self.assertEqual(EVALUATE.sha256(held_out), original_hash)


class DecoderTest(unittest.TestCase):
    def args(self, **changes):
        values = dict(cli=Path("cli"), model=Path("model"), baseline=False, threads=4, beam_size=3)
        return argparse.Namespace(**(values | changes))

    def test_command_matches_jni_and_keeps_reference_out_of_process(self):
        command = EVALUATE.decoder_command(self.args(), Path("clip.wav"), "ru", Path("result"), 768)
        for flag in ("-ng", "-fa", "-sns", "-oj"):
            self.assertIn(flag, command)
        for flag in ("-nt", "-ojf", "--prompt", "-ml", "-sow"):
            self.assertNotIn(flag, command)
        self.assertEqual(command[command.index("-mc") + 1], "64")
        self.assertEqual(command[command.index("-ac") + 1], "768")
        self.assertEqual(command[command.index("-bs") + 1], "3")

    def test_historical_mode_is_explicit_and_overrides_threads_and_beams(self):
        command = EVALUATE.decoder_command(self.args(baseline=True, threads=4, beam_size=5), Path("clip.wav"), "en", Path("result"), 0)
        self.assertEqual(command[command.index("-t") + 1], "2")
        self.assertEqual(command[command.index("-bs") + 1], "1")
        self.assertEqual(command[command.index("-ml") + 1], "180")
        self.assertIn("-sow", command)
        self.assertNotIn("-ac", command)

    def test_auto_context_rounding_is_bounded_and_full_is_default(self):
        for seconds, expected in ((0.01, 768), (13, 768), (14, 1024), (20, 1280), (29, 1500), (30, 1500)):
            self.assertEqual(EVALUATE.effective_audio_context("auto", seconds), expected)
        self.assertEqual(EVALUATE.effective_audio_context(0, 8), 0)
        self.assertEqual(EVALUATE.effective_audio_context(1024, 8), 1024)

    def test_padding_only_and_invalid_segments_cannot_become_hypothesis(self):
        document = {"model": {"multilingual": True}, "transcription": [
            {"offsets": {"from": 0, "to": 300}, "text": " real "},
            {"offsets": {"from": 200, "to": 1000}, "text": " invented "},
            {"offsets": {"from": 50, "to": 50}, "text": " invalid "}]}
        self.assertEqual(EVALUATE.decoded_text(document, 0.2), ("real", 2))

    def test_millisecond_tail_uses_integer_pcm_duration(self):
        document = {"model": {"multilingual": True}, "transcription": [
            {"offsets": {"from": 1000, "to": 1010}, "text": " tail "}]}
        # 16016 PCM frames are exactly 1001ms; float int(seconds*1000) is 1000.
        self.assertEqual(EVALUATE.decoded_text(document, 16016 / 16000), ("tail", 0))

    def test_truncated_wav_fails_before_decode(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "clip.wav"
            with wave.open(str(path), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(bytes(32000))
            path.write_bytes(path.read_bytes()[:-10])
            with self.assertRaisesRegex(ValueError, "truncated"):
                EVALUATE.read_audio(path)

    def test_end_to_end_fake_decoder_private_records_and_aggregate_only_stdout(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            driver = directory / "repository/scripts/evaluate-stt.py"
            driver.parent.mkdir(parents=True)
            driver.write_bytes(SCRIPT.read_bytes())
            evaluator_hash = EVALUATE.sha256(driver)
            audio = directory / "clip.wav"
            with wave.open(str(audio), "wb") as wav:
                wav.setparams((1, 2, 16000, 0, "NONE", "not compressed"))
                wav.writeframes(bytes(6400))
            model = directory / "model.bin"
            model.write_bytes(b"synthetic model marker, not weights")
            cli = directory / "fake-cli"
            cli.write_text("#!/usr/bin/env python3\n" +
                           "import json, pathlib, sys, wave\n" +
                           "args = sys.argv[1:]\n" +
                           "assert '-nt' not in args and '-ojf' not in args\n" +
                           "with wave.open(args[args.index('-f') + 1]) as wav: assert wav.getnframes() == 16000\n" +
                           "document = {'model': {'multilingual': True, 'audio': {'ctx': 1500}}, 'transcription': [\n" +
                           "{'offsets': {'from': 0, 'to': 200}, 'text': ' unique hypothesis '},\n" +
                           "{'offsets': {'from': 200, 'to': 1000}, 'text': ' discarded padding '}]}\n" +
                           "pathlib.Path(args[args.index('-of') + 1] + '.json').write_text(json.dumps(document))\n" +
                           f"pathlib.Path({str(driver)!r}).write_text('changed after evaluator startup')\n" +
                           f"pathlib.Path({str(directory / 'corpus.json')!r}).write_text('changed after manifest snapshot')\n")
            cli.chmod(0o755)
            manifest = directory / "corpus.json"
            manifest.write_text(json.dumps([dict(id="private-fixture", audio="clip.wav", language="en",
                                                text="unique reference", split="dev", audio_sha256=EVALUATE.sha256(audio))]))
            manifest_hash = EVALUATE.sha256(manifest)
            output, records = directory / "metrics.json", directory / "records.jsonl"
            result = subprocess.run([sys.executable, str(driver), str(manifest), "--cli", str(cli), "--model", str(model),
                                     "--split", "dev", "--audio-context", "auto", "--output", str(output),
                                     "--records-output", str(records)], capture_output=True, text=True, timeout=15)
            self.assertEqual(result.returncode, 0, result.stderr)
            report = json.loads(output.read_text())
            record = json.loads(records.read_text())
            self.assertEqual(report["micro"]["wer"], 0.5)
            self.assertEqual(report["grouping"]["clips_without_group"], 1)
            self.assertEqual(record["reference"], "unique reference")
            self.assertEqual(record["hypothesis"], "unique hypothesis")
            self.assertEqual(record["effective_audio_context"], 768)
            self.assertEqual(record["discarded_padding_or_invalid_segments"], 1)
            self.assertEqual(record["audio_sha256"], EVALUATE.sha256(audio))
            self.assertEqual(record["pcm_sha256"], hashlib.sha256(bytes(6400)).hexdigest())
            self.assertEqual(report["evaluator_sha256"], evaluator_hash)
            self.assertNotEqual(report["evaluator_sha256"], EVALUATE.sha256(driver))
            self.assertEqual(report["manifest_sha256"], manifest_hash)
            self.assertNotEqual(report["manifest_sha256"], EVALUATE.sha256(manifest))
            self.assertEqual(record["provenance"]["selected_corpus_sha256"], report["selected_corpus_sha256"])
            for private in ("unique reference", "unique hypothesis", "private-fixture", "discarded padding"):
                self.assertNotIn(private, result.stdout)

    def test_shared_dependency_hash_includes_engine_library(self):
        with tempfile.TemporaryDirectory() as temporary:
            library = Path(temporary) / "libwhisper.so"
            library.write_bytes(b"synthetic shared engine")
            fake_link = subprocess.CompletedProcess([], 0, f"libwhisper.so => {library} (0xabc)\n", "")
            fake_git = subprocess.CompletedProcess([], 0, "source-commit\n", "")
            with mock.patch.object(EVALUATE.platform, "system", return_value="Linux"), \
                    mock.patch.object(EVALUATE.subprocess, "run", side_effect=[fake_link, fake_git]):
                provenance = EVALUATE.engine_provenance(SCRIPT)
            self.assertEqual(provenance["dependency_hash_status"], "hashed")
            self.assertEqual(provenance["shared_libraries"][0]["sha256"], EVALUATE.sha256(library))


if __name__ == "__main__":
    unittest.main()
