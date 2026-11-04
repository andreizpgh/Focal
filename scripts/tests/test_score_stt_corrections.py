import copy
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest


SCRIPT = Path(__file__).resolve().parents[1] / "score-stt-corrections.py"
SPEC = importlib.util.spec_from_file_location("score_stt_corrections", SCRIPT)
SCORE = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SCORE)


def pair(**updates):
    item = dict(id="synthetic-1", group="speaker-session-1", language="en", split="test",
                reference="Meet on Friday.", original="Meet on fryday.",
                proposals=[dict(start=8, end=14, replacement="Friday", decision="accept", judgement="correct", introduced_errors=0)])
    item.update(updates)
    return item


class CorrectionScoreTest(unittest.TestCase):
    def test_real_fix_improves_paired_word_and_character_errors(self):
        result = SCORE.report([pair()], repetitions=100)
        overall = result["overall"]
        self.assertEqual(1, overall["original_errors"])
        self.assertEqual(0, overall["candidate_errors"])
        self.assertEqual(1, overall["span_precision"])
        self.assertLess(overall["span_precision_lower_95"], .995)
        self.assertIsNone(result["languages"]["en"]["paired_wer_delta_ci95"])

    def test_net_wer_cannot_hide_an_introduced_name_error(self):
        original = "Meet on fryday with Ivan."
        edits = [dict(start=8, end=14, replacement="Friday", decision="accept", judgement="correct", introduced_errors=0),
                 dict(start=20, end=24, replacement="Evan", decision="accept", judgement="incorrect", introduced_errors=1)]
        result = SCORE.report([pair(reference="Meet on Friday with Ivan.", original=original, proposals=edits,
                                   critical_errors={"names": {"original": 0, "candidate": 1}})], repetitions=0)["overall"]
        self.assertEqual(result["original_errors"], result["candidate_errors"])
        self.assertEqual(1, result["adjudicated_introduced_errors"])
        self.assertEqual(.5, result["span_precision"])
        self.assertEqual(1, result["critical_errors"]["names"]["candidate"])
        self.assertEqual(0, result["critical_errors"]["negation"]["assessed_clips"])
        self.assertIsNone(result["critical_errors"]["negation"]["candidate"])

    def test_partial_critical_adjudication_never_looks_like_a_corpus_zero(self):
        assessed = pair(critical_errors={"names": {"original": 0, "candidate": 0}})
        missing = pair(id="second", group="speaker-session-2")
        result = SCORE.report([assessed, missing], repetitions=0)["overall"]["critical_errors"]["names"]
        self.assertEqual(1, result["assessed_clips"])
        self.assertEqual(0, result["assessed_candidate"])
        self.assertIsNone(result["candidate"])
        self.assertIsNone(result["original"])

    def test_bootstrap_requires_replicates_and_multiple_reference_groups(self):
        first = pair()
        second = pair(id="second", group="speaker-session-2")
        silence = pair(id="silence", group="speaker-session-2", reference="", original="", proposals=[])
        self.assertIsNone(SCORE.report([first], repetitions=1000)["overall"]["paired_wer_delta_ci95"])
        self.assertIsNone(SCORE.report([first, silence], repetitions=1000)["overall"]["paired_wer_delta_ci95"])
        self.assertIsNone(SCORE.report([first, second], repetitions=999)["overall"]["paired_wer_delta_ci95"])
        interval = SCORE.report([first, second], repetitions=1000)["overall"]["paired_wer_delta_ci95"]
        self.assertLess(interval[1], 0)

    def test_unlabelled_model_confidence_is_not_human_precision(self):
        item = pair()
        item["proposals"][0].pop("judgement")
        item["proposals"][0].pop("introduced_errors")
        item["proposals"][0]["confidence"] = .9999
        result = SCORE.report([item], repetitions=0)["overall"]
        self.assertIsNone(result["span_precision"])
        self.assertIsNone(result["span_precision_lower_95"])
        self.assertEqual(1, result["unlabelled_accepted_spans"])
        self.assertEqual(1, result["unscored_introduced_error_spans"])

    def test_abstention_preserves_original_and_does_not_inflate_precision(self):
        item = pair()
        item["proposals"][0]["decision"] = "abstain"
        result = SCORE.report([item], repetitions=0)["overall"]
        self.assertEqual(result["original_errors"], result["candidate_errors"])
        self.assertEqual(0, result["changed_clips"])
        self.assertEqual(1, result["abstained_proposals"])
        self.assertIsNone(result["span_precision"])

    def test_missing_proposal_output_is_not_a_successful_abstention(self):
        item = pair()
        del item["proposals"]
        with self.assertRaisesRegex(ValueError, "proposals list"):
            SCORE.report([item], repetitions=0)
        self.assertEqual(0, SCORE.report([pair(proposals=[])], repetitions=0)["overall"]["accepted_spans"])

    def test_codepoint_offsets_survive_supplementary_unicode(self):
        item = pair(original="🙂 Превет", reference="🙂 Привет",
                    proposals=[dict(start=2, end=8, replacement="Привет", decision="accept", judgement="correct", introduced_errors=0)], language="ru")
        item["original_sha256"] = hashlib.sha256(item["original"].encode()).hexdigest()
        self.assertEqual(0, SCORE.report([item], repetitions=0)["overall"]["candidate_errors"])
        self.assertEqual(SCORE.words("café"), SCORE.words("cafe\u0301"))

    def test_bad_mapping_hash_and_overlapping_edits_are_rejected(self):
        invalid = [pair(original_sha256="0"*64), pair(candidate="A silently rewritten document.")]
        item = pair()
        item["proposals"][0]["end"] = 100
        invalid.append(item)
        item = pair()
        item["proposals"].append(dict(start=9, end=10, replacement="X", decision="accept"))
        invalid.append(item)
        item = pair()
        item["proposals"] = [dict(start=1, end=1, replacement="x", decision="accept"), dict(start=1, end=1, replacement="y", decision="accept")]
        invalid.append(item)
        for item in invalid:
            with self.subTest(item=item), self.assertRaises(ValueError):
                SCORE.report([item], repetitions=0)

    def test_missing_speech_reference_does_not_discard_hallucinations(self):
        item = pair(reference="", original="", proposals=[dict(start=0, end=0, replacement="Thank you", decision="accept", judgement="incorrect", introduced_errors=2)])
        result = SCORE.report([item], repetitions=0)["overall"]
        self.assertEqual(1, result["silence_clips"])
        self.assertEqual(2, result["candidate_insertions"])
        self.assertEqual(1, result["originally_correct_clips_harmed"])
        self.assertIsNone(result["candidate_wer"])

    def test_measured_trials_separate_variant_phase_and_do_not_duplicate_quality(self):
        measurements = [dict(device="phone-mid", variant="original", phase="warm", finish_to_ready_ms=20),
                        dict(device="phone-mid", variant="candidate", phase="warm", correction_ms=100, peak_rss_bytes=1000),
                        dict(device="phone-mid", variant="candidate", phase="warm", correction_ms=300),
                        dict(device="phone-mid", variant="candidate", phase="cold", correction_ms=900)]
        result = SCORE.report([pair(measurements=measurements)], repetitions=0)
        self.assertEqual(1, result["overall"]["clips"])
        self.assertEqual(3, len(result["resources"]))
        warm = next(group for group in result["resources"] if group["variant"] == "candidate" and group["phase"] == "warm")
        self.assertEqual(200, warm["measures"]["correction_ms"]["p50"])
        self.assertEqual(290, warm["measures"]["correction_ms"]["p95"])
        self.assertNotIn("energy_mj", warm["measures"])

    def test_bootstrap_uses_session_groups_and_is_reproducible(self):
        records = [pair(id="first"), pair(id="second"), pair(id="third", group="speaker-session-2", proposals=[])]
        first = SCORE.report(records, repetitions=50, seed=7)
        second = SCORE.report(records, repetitions=50, seed=7)
        self.assertEqual(first, second)
        self.assertEqual(2, first["overall"]["independent_groups"])
        self.assertEqual(3, first["overall"]["explicit_group_clips"])

    def test_nonfinite_measurements_and_confidence_are_rejected(self):
        invalid = pair()
        invalid["proposals"][0]["confidence"] = float("nan")
        with self.assertRaises(ValueError):
            SCORE.report([invalid], repetitions=0)
        invalid = pair(measurements=[dict(device="phone-mid", variant="candidate", phase="warm", correction_ms=float("inf"))])
        with self.assertRaises(ValueError):
            SCORE.report([invalid], repetitions=0)

    def test_precision_bound_requires_far_more_than_a_few_perfect_spans(self):
        self.assertLess(SCORE.wilson_lower(20, 20), .995)
        self.assertGreater(SCORE.wilson_lower(800, 800), .995)

    def test_cli_separates_all_seven_languages_and_outputs_no_transcripts(self):
        records = [pair(id="synthetic-"+language, group="session-"+language, language=language) for language in sorted(SCORE.LANGUAGES)]
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary)/"pairs.jsonl"
            output = Path(temporary)/"metrics.json"
            path.write_text("\n".join(json.dumps(item) for item in records))
            result = subprocess.run([sys.executable, str(SCRIPT), str(path), "--require-all-languages", "--bootstrap-samples", "20", "--output", str(output)], capture_output=True, text=True, check=True)
            metrics = json.loads(output.read_text())
            self.assertTrue(metrics["all_seven_languages_present"])
            self.assertEqual(7, len(metrics["languages"]))
            self.assertNotIn("fryday", result.stdout)
            self.assertNotIn("synthetic-en", result.stdout)
            contaminated = copy.deepcopy(records[0])
            contaminated.update(id="dev-other", split="dev")
            path.write_text("\n".join(json.dumps(item) for item in records+[contaminated]))
            rejected = subprocess.run([sys.executable, str(SCRIPT), str(path), "--output", str(output)], capture_output=True, text=True)
            self.assertNotEqual(0, rejected.returncode)
            self.assertIn("must not cross dev and test", rejected.stderr)


if __name__ == "__main__":
    unittest.main()
