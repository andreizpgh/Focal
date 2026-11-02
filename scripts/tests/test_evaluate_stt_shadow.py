"""Synthetic pairing/selection regressions; actual Kotlin is used by the research run."""
import copy
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

SPEC = importlib.util.spec_from_file_location("shadow", Path(__file__).resolve().parents[1] / "evaluate-stt-shadow.py")
SHADOW = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SHADOW)


def record(identifier="sample", reference="right", hypothesis="wrong", probability=.1, wall=2):
    profile = dict(schema=1, threads=4, beam_size=3, effective_audio_context=768, load_seconds=.2,
                   decode_seconds=1, peak_rss_bytes=4096, initial_prompt_sha256=SHADOW.text_hash(""),
                   initial_prompt_bytes=0, initial_prompt_utf16_units=0,
                   segments=[dict(offsets={"from": 0, "to": 1000}, text=hypothesis,
                                  probabilities=[probability], noSpeech=.1, in_original_audio=bool(hypothesis))])
    return dict(id=identifier, language="en", split="dev", group=None, silence=False,
                audio_sha256=SHADOW.text_hash(identifier), pcm_sha256=SHADOW.text_hash(identifier + "-pcm"),
                reference=reference, hypothesis=hypothesis, effective_audio_context=768, probe=profile,
                metrics=SHADOW.EVAL.score(reference, hypothesis, False, 1, wall))


def run_metadata(records, model="1" * 64):
    settings = dict(threads=4, beam_size=3, audio_context="auto")
    report = dict(settings=settings, settings_sha256=SHADOW.EVAL.json_hash(settings), selected_split="dev",
                  evaluator_sha256="2" * 64, decoder_sha256="3" * 64, model_sha256=model, manifest_sha256="4" * 64,
                  selected_corpus_sha256=SHADOW.EVAL.json_hash([{key: item[key] for key in SHADOW.IDENTITY} for item in records]),
                  shared_libraries=[dict(name="libwhisper.so.1", sha256="5" * 64), dict(name="libggml.so", sha256="6" * 64)])
    for item in records:
        item["provenance"] = {key: report[key] for key in SHADOW.PROVENANCE}
    return report


class ShadowTest(unittest.TestCase):
    def test_selection_cannot_use_reference_hypothesis_or_cached_error_counts(self):
        baseline = record()
        calls = []
        def reviewer(no_speech, probabilities):
            calls.append((no_speech, probabilities))
            return probabilities[0] < .5
        before = SHADOW.select(baseline, reviewer)
        baseline.update(reference="entirely different reference", hypothesis="different hypothesis", metrics={"word_errors": 999})
        self.assertEqual(SHADOW.select(baseline, reviewer), before)
        self.assertEqual(calls, [(.1, [.1]), (.1, [.1])])

    def test_padding_and_no_eligible_segments_do_not_trigger(self):
        baseline = record()
        baseline["probe"]["segments"][0]["in_original_audio"] = False
        with patch("builtins.print", side_effect=AssertionError("No transcript output")):
            selected = SHADOW.select(baseline, lambda *_: self.fail("Padding entered selection"))
        self.assertFalse(selected["flagged"])
        self.assertEqual(selected["eligible_segments"], 0)

    def test_pairing_rejects_source_settings_engine_and_flag_mismatches(self):
        first, second = [record()], [record(hypothesis="right", wall=5)]
        reports = run_metadata(first), run_metadata(second, "7" * 64)
        self.assertEqual(len(SHADOW.validate_pairs(first, reports[0], second, reports[1])), 1)
        mutations = (
            lambda a, ar, b, br: b[0].update(pcm_sha256="8" * 64),
            lambda a, ar, b, br: b[0].update(reference="other"),
            lambda a, ar, b, br: b[0].update(id="other"),
            lambda a, ar, b, br: br["settings"].update(threads=1),
            lambda a, ar, b, br: br["shared_libraries"][0].update(sha256="8" * 64),
            lambda a, ar, b, br: a[0]["probe"]["segments"][0].update(in_original_audio=False),
        )
        for change in mutations:
            with self.subTest(change=change):
                a, ar, b, br = copy.deepcopy((first, reports[0], second, reports[1]))
                change(a, ar, b, br)
                with self.assertRaises(ValueError):
                    SHADOW.validate_pairs(a, ar, b, br)

    def test_confidence_cannot_be_attached_to_another_hypothesis_in_either_pass(self):
        for changed_pass in (0, 1):
            first, second = [record()], [record(hypothesis="right")]
            reports = run_metadata(first), run_metadata(second, "7" * 64)
            changed = (first, second)[changed_pass][0]
            changed["hypothesis"] = "unrelated cached decode"
            changed["metrics"] = SHADOW.EVAL.score(changed["reference"], changed["hypothesis"], False, 1, 2)
            with self.assertRaisesRegex(ValueError, "reconstruct"):
                SHADOW.validate_pairs(first, reports[0], second, reports[1])

    def test_mixed_runner_identities_require_opt_in_and_exact_witness(self):
        first, second = [record()], [record(hypothesis="right")]
        first_report, second_report = run_metadata(first), run_metadata(second, "7" * 64)
        second_report.update(decoder_sha256="8" * 64, evaluator_sha256="9" * 64)
        second[0]["provenance"].update(decoder_sha256="8" * 64, evaluator_sha256="9" * 64)
        with self.assertRaisesRegex(ValueError, "identities differ"):
            SHADOW.validate_pairs(first, first_report, second, second_report)
        self.assertEqual(len(SHADOW.validate_pairs(first, first_report, second, second_report, True)), 1)
        witness = [record()]
        witness_report = run_metadata(witness)
        witness_report.update(decoder_sha256="8" * 64, evaluator_sha256="9" * 64)
        self.assertEqual(SHADOW.validate_compatibility(first, first_report, witness, witness_report, second_report), 1)
        witness[0]["hypothesis"] = "another prediction"
        with self.assertRaisesRegex(ValueError, "predictions"):
            SHADOW.validate_compatibility(first, first_report, witness, witness_report, second_report)

    def test_run_provenance_and_reference_cohort_hash_are_verified(self):
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "cached.jsonl"
            records = [record()]
            report = run_metadata(records)
            path.write_text(json.dumps(records[0]) + "\n")
            path.with_suffix(".json").write_text(json.dumps(report))
            self.assertEqual(len(SHADOW.load_run(path, "dev")[0]), 1)
            records[0]["reference"] = "changed after inference"
            path.write_text(json.dumps(records[0]) + "\n")
            with self.assertRaisesRegex(ValueError, "corpus hash"):
                SHADOW.load_run(path, "dev")

    def test_harm_and_unflagged_errors_are_not_cancelled_by_other_improvements(self):
        specifications = (("one", "right", "wrong", "right", .1),
                          ("two", "correct", "correct", "incorrect", .1),
                          ("three", "spoken", "mistake", "spoken", .9),
                          ("four", "present", "", "present", .9))
        outcomes = []
        for identifier, reference, first, second, probability in specifications:
            baseline = record(identifier, reference, first, probability)
            candidate = record(identifier, reference, second, probability, wall=5)
            decision = SHADOW.select(baseline, lambda _, probabilities: probabilities[0] < .5)
            outcomes.append(SHADOW.score_pair(baseline, candidate, decision))
        total = SHADOW.summarize(outcomes)
        self.assertEqual(total["flagged_clips"], 2)
        self.assertEqual(total["clips_improved"], 1)
        self.assertEqual(total["clips_worsened"], 1)
        self.assertEqual(total["initially_lexically_correct_clips_harmed"], 1)
        self.assertEqual(total["unflagged_word_errors"], 2)
        self.assertEqual(total["no_eligible_segment_clips"], 1)
        self.assertEqual(total["baseline"]["word_errors"], total["shadow"]["word_errors"])
        self.assertEqual(total["cached_cold_seconds"]["estimated_shadow"], 18)
        self.assertAlmostEqual(total["baseline_error_word_coverage"], 1/3)

    def test_cached_metric_tampering_is_rejected_after_selection(self):
        baseline, second = record(), record(hypothesis="right")
        baseline["metrics"]["word_errors"] = 99
        with self.assertRaisesRegex(ValueError, "Cached text metrics"):
            SHADOW.score_pair(baseline, second, dict(flagged=True))

    def test_wer_ties_cannot_hide_character_error_increases(self):
        baseline = record(reference="cat", hypothesis="cot")
        candidate = record(reference="cat", hypothesis="longwrong", wall=5)
        result = SHADOW.score_pair(baseline, candidate, dict(flagged=True, flagged_segments=[0], eligible_segments=1))
        total = SHADOW.summarize([result])
        self.assertEqual(total["clips_worsened"], 0)
        self.assertEqual(total["clips_with_increased_lexical_character_errors"], 1)
        self.assertGreater(total["added_character_errors_in_worsened_clips"], 0)

    def test_actual_kotlin_command_is_pinned_and_receives_only_confidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            jar, stdlib = directory / "replay.jar", directory / "stdlib.jar"
            jar.write_bytes(b"synthetic replay marker")
            stdlib.write_bytes(b"synthetic stdlib marker")
            classpath = directory / "classpath.txt"
            classpath.write_text(os.pathsep.join(map(str, (jar, stdlib))))
            reviewer = SHADOW.KotlinReview(classpath, SHADOW.EVAL.sha256(jar))
            self.addCleanup(reviewer.temporary.cleanup)
            original_hash = SHADOW.EVAL.sha256(jar)
            jar.write_bytes(b"concurrently rebuilt policy")
            with patch.object(SHADOW.subprocess, "run", return_value=subprocess.CompletedProcess([], 0, "true\n", "")) as run:
                self.assertTrue(reviewer(.1, [.9, .2]))
                self.assertTrue(reviewer(.1, [.9, .2]))
            self.assertEqual(run.call_count, 1)
            self.assertEqual(run.call_args.args[0][-4:], ["app.focal.tools.TranscriptReplay", "review", "0.1", "0.9,0.2"])
            snapshot = Path(run.call_args.args[0][3].split(os.pathsep)[0])
            self.assertEqual(SHADOW.EVAL.sha256(snapshot), original_hash)
            self.assertIn(jar, reviewer.paths)
            self.assertIn(snapshot, reviewer.paths)
            with self.assertRaisesRegex(ValueError, "pinned hash"):
                SHADOW.KotlinReview(classpath, "0" * 64)


if __name__ == "__main__":
    unittest.main()
